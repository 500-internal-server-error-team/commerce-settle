# Kafka 토픽 파티션 수 재검토

- 제안일: 2026-11-12
- 상태: 제안·시험 전. 파티션 수는 정하지 않았으며 아래 시험 결과로 결정
- 범위: `commerce-settle.{commerce,payment,settlement}.v1` 원본 토픽과 DLT의 파티션 수, 이에 맞춘 Consumer 동시 실행 수
- 연결 문서: [병렬도 결정 절차](0015-parallelism-decision-and-load-scope.md), [Kafka 전달과 처리 용량](0005-kafka-event-delivery-and-capacity.md#파티션과-동시-실행), [#318](https://github.com/500-internal-server-error-team/commerce-settle/issues/318)

## 배경

[ADR 0005](0005-kafka-event-delivery-and-capacity.md#파티션과-동시-실행)는 원본 토픽당 파티션 3개로 시작했습니다. EC2 2대에서 Kafka와 앱이 자원을 나눠 쓰는 지금 구성에서 3개가 적정한지는 측정한 적이 없습니다. [ADR 0015](0015-parallelism-decision-and-load-scope.md)는 파티션 수 변경을 병렬도 시험에서 빼고 후속 ADR로 미뤘습니다. 이 ADR은 그 후속으로, 파티션 축소가 자원·재기동·처리량에 주는 영향을 비교해 적정 개수를 정하기 위한 제약·시험 조건·판정 기준을 결과를 보기 전에 고정합니다. 증설은 다루지 않습니다.

### 현재 구성

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 토픽 | 서비스별 원본 1개(보존 24시간)와 DLT 1개(7일), 알림 DLT 1개. 모두 파티션 3·복제 1 | 각 서비스의 `KafkaTopicConfiguration`, `scripts/smoke.py` |
| 메시지 key | 이벤트의 `aggregateId`. 같은 집계의 이벤트는 같은 파티션에서 순서대로 처리 | 각 서비스의 `OutboxRelay` |
| `payment.v1` 그룹 | commerce 토픽 수신과 settlement 응답 수신이 같은 설정 `PAYMENT_KAFKA_LISTENER_CONCURRENCY`(기본 3)를 씀 | `payment/src/main/resources/application.yml` |
| `commerce.v1`·`settlement.v1`·`commerce-notifications.v1` 그룹 | 동시 실행 1 | 각 서비스 `application.yml` |
| 수신 뒤 처리 | Listener는 Inbox 저장 후 ACK만 하고, 업무 처리는 DB를 폴링하는 처리기·Worker가 맡음 | `OrderPaymentRequestedListener`, `PaymentRequestProcessor` |

수신과 업무 처리가 분리돼 있어서 파티션 수는 **Inbox 저장 속도의 상한**을 정하고, 결제 확정 처리량을 직접 정하지는 않습니다.

### 현재 배정

2026-11-12 develop 기준 로컬 스택에서 금융 흐름과 알림 수신을 모두 켜고 `kafka-consumer-groups.sh --describe --all-groups --members`로 확인했습니다. 배정되지 않은 파티션이나 파티션을 받지 못한 Consumer는 없습니다.

| 그룹 | Consumer 수 | 배정 |
| --- | --- | --- |
| `payment.v1` | 6 (commerce 토픽 수신 3, settlement 응답 수신 3) | Consumer마다 파티션 1개 |
| `commerce.v1` | 1 | `payment.v1` 0·1·2 |
| `settlement.v1` | 1 | `commerce.v1`·`payment.v1`의 6개 |
| `commerce-notifications.v1` | 1 | `payment.v1`·`settlement.v1`의 6개 |

파티션을 나눠 병렬로 읽는 그룹은 `payment.v1`뿐입니다. 그중 settlement 응답 수신 Consumer 3개는 환불 흐름에서만 메시지를 받고, DLT는 격리된 메시지가 있을 때만 쓰입니다.

### 기존 측정

2026-11-03 [결제 부하 시험](https://github.com/500-internal-server-error-team/commerce-settle/blob/7a3affbd34cde810951d21e35aefcb4aa557dafc/tests/load/README.md#시험-환경)의 측정 4에서 Listener 스레드 1개가 파티션 3개를 읽을 때 수신이 초당 약 190건에서 막혔습니다. Listener 동시 실행을 3으로 올린 측정 5에서는 400/초 단계에서도 Kafka lag가 최대 309건이었고, 병목은 결제 요청 처리기로 옮겨 갔습니다. 같은 시험의 `restart`에서는 Worker 재기동 뒤 수신이 약 40초 더 멈췄으며, [#253](https://github.com/500-internal-server-error-team/commerce-settle/issues/253)이 기본 `session.timeout.ms` 45초를 원인 후보로 조사 중입니다.

## 검증할 효과

파티션 축소로 기대하는 효과와 우려하는 손실을 아래처럼 나누고, 측정으로 확인된 항목만 결정 근거로 씁니다.

| 항목 | 기대·우려 | 판단 지표 | 사전 예상 |
| --- | --- | --- | --- |
| 자원 사용 | 파티션·세그먼트·Consumer 스레드가 줄어 Kafka와 payment-worker의 CPU·메모리가 줄어듦 | `resource_sample.py`의 컨테이너 CPU·메모리, Kafka 세그먼트 파일 수 | 토픽 7개×3 규모에서는 차이가 작을 수 있음 |
| 재기동 복구 | rebalance에서 재배정할 파티션이 줄어 수신 재개가 빨라짐 | Worker 재기동 완료부터 Inbox 수신 재개까지의 시간, 최대 lag | 멈춤이 세션 만료 대기라면 파티션 수와 무관 |
| 처리량·지연 | 수신 병렬도가 줄어 처리량이 떨어지거나 지연이 늘 수 있음 | 결제 확정 TPS, 수신→확정 p99, 최대 lag | 파티션 1개는 Listener 1스레드가 되어 측정 4처럼 약 190/초에서 막힐 가능성 |
| 유입 대비 Consumer | 유입이 적은 토픽의 Consumer가 과함 | 토픽별 실제 유입량, settlement 응답 수신 스레드의 빈 poll 비율 | 응답 수신은 환불 흐름에서만 유입 |

## 변경 시 제약

- **Kafka는 기존 토픽의 파티션 수를 줄이지 못합니다.** 코드의 `partitions(3)`을 낮춰도 Spring `KafkaAdmin`은 기존 토픽에 "different partition count" 로그만 남기고 바꾸지 않습니다(spring-kafka 3.3.16에서 확인). 이미 있는 환경은 토픽을 삭제·재생성해야 합니다.
- 재생성 전에 모든 그룹의 lag와 미발행 Outbox가 0이어야 합니다. 원본 토픽 보존이 24시간이므로 남은 메시지를 옮기지 않고 지우면 누락입니다. 재생성 뒤 각 그룹의 최초 offset을 `scripts/prepare_financial_kafka.py`로 다시 준비하고, `auto.offset.reset=none`은 유지합니다.
- 같은 그룹의 활성 Consumer 수는 파티션 수를 넘을 수 없습니다. 파티션을 줄이면 `PAYMENT_KAFKA_LISTENER_CONCURRENCY`도 같이 낮추고, 남는 스레드가 생기지 않게 합니다.
- `scripts/smoke.py`는 `PartitionCount: 3`을 검사하므로 결정한 값으로 함께 바꿉니다.
- DLT는 격리된 메시지만 들어가 유입이 적으므로, 원본 토픽과 따로 정할 수 있습니다.

## 비교 후보

| 후보 | 원본 토픽 | DLT | payment Listener 동시 실행 |
| --- | --- | --- | --- |
| A. 현행(기준선) | 3 | 3 | 3 |
| B. DLT만 축소 | 3 | 1 | 3 |
| C. 2개 | 2 | 1 | 2 |
| D. 1개 | 1 | 1 | 1 |

파티션 수와 Listener 동시 실행은 같은 그룹에서 함께 바뀌어야 하므로, ADR 0015의 단일 변수 원칙에서 한 변수로 봅니다. B는 원본 토픽의 처리 경로를 바꾸지 않으므로 처리량 시험 없이 자원 비교만 합니다. commerce 토픽 수신과 settlement 응답 수신의 동시 실행 수를 따로 둘지는 유입량 측정 결과로 판단합니다.

## 시험 계획

### 환경과 고정 조건

[ADR 0015](0015-parallelism-decision-and-load-scope.md)의 판정 순서와 [병렬도 단일 변수 시험 절차](../../tests/load/README.md#병렬도-단일-변수-시험-절차)를 따릅니다.

- 기존 [결제 부하 도구](../../tests/load/README.md)의 별도 Compose 프로젝트에서 실행하고, 개발 볼륨과 공용 `.env`는 바꾸지 않습니다.
- 파티션 수가 다른 후보는 각각 빈 스택에서 시작합니다. 같은 스택에서는 기존 토픽의 파티션을 줄일 수 없기 때문입니다. 같은 후보의 반복은 같은 스택에서 실행 번호(`LOAD_NAMESPACE`)로 데이터를 나눕니다.
- Outbox in-flight 32·승인 6·결제 요청 3·DB 풀 20과 Compose 자원 한도는 고정하고, 실제 값은 `summary.json`의 `workerSettings`로 확인합니다.
- 실험 시작 전 미발행 Outbox·Kafka lag·이전 실행의 잔여 처리가 0인지 확인합니다.
- 시험 PC·Docker 자원·이미지 revision을 실행 기록에 남깁니다.

### 도구

후보는 [결제 부하 도구](../../tests/load/README.md#실행)의 `LOAD_KAFKA_PARTITIONS`·`LOAD_KAFKA_DLT_PARTITIONS`·`LOAD_PAYMENT_KAFKA_LISTENER_CONCURRENCY`로 지정합니다. `up`이 앱보다 먼저 토픽을 만들고, 앱의 토픽 자동 생성은 끕니다. 자동 생성을 켜 두면 앱이 기동하면서 기존 토픽의 파티션을 코드 값 3까지 늘리는 것을 확인했습니다. `ramp`는 실제 파티션 수와 Consumer 배정을 `summary.json`의 `kafka`에 남기므로, 실행마다 후보가 실제로 적용됐는지 확인합니다. 자원은 `tests/load/resource_sample.py`로 시험과 같은 시간 동안 기록합니다.

같은 스택에서 실행 번호를 바꿔 반복하기 전에, `reconcile`이 다른 실행 번호의 마일리지 지갑을 초기 잔액 누락으로 세는 문제를 해결해야 합니다. 해결 전에는 후보·반복마다 새 스택을 사용합니다.

### 실행

| 시험 | 입력 | 반복 | 보는 항목 |
| --- | --- | --- | --- |
| 처리량 | `ramp --stages 300:30 --mix card,mixed,easy,mileage` | 후보 A·C·D 각 3회 | 처리량·지연, 자원 사용 |
| 재기동 | `scenario restart` | 후보 A·C·D 각 3회 | 재기동 복구 |
| 유휴 자원 | 금융 흐름을 켠 유휴 상태 5분 | 후보 A·B·C·D 각 1회 | 자원 사용, 유입 대비 Consumer |

모든 실행 뒤 `reconcile`이 통과해야 결과로 인정합니다. 대조가 실패한 실행은 성능 비교에서 제외하고 원인을 따로 기록합니다. 반복이 모자라 판정할 수 없으면 판정을 보류하고 그대로 기록합니다.

### 판정 기준

결과를 보기 전에 다음 기준을 정하고, 시험을 시작한 뒤에는 바꾸지 않습니다.

- **처리량 유지**: 결제 확정 TPS가 후보 A 대비 5% 이상 낮지 않고, 수신→확정 p99가 20% 이상 늘지 않아야 합니다.
- **재기동**: 수신 재개 시간이 후보 A보다 길어지지 않아야 합니다.
- **채택 조건**: 처리량 유지와 재기동 기준을 통과한 후보 중 파티션이 가장 적은 것을 고르되, 자원 감소가 측정으로 확인될 때만 원본 토픽을 바꿉니다. 자원 차이가 측정 오차 수준이면 재생성 비용을 이유로 현행을 유지하고, B(DLT만 축소)의 채택 여부만 따로 판단합니다.
- 정합성은 성능보다 우선하며 [ADR 0015의 다섯 항목](0015-parallelism-decision-and-load-scope.md#7-정합성-검증) 중 결제 경로에 해당하는 항목을 실행마다 확인합니다.

## 결정

시험 전입니다. 결과와 선택 이유는 이 절에 기록합니다.

## 결과와 한계

- 단일 Windows PC의 Docker Desktop 결과이며 EC2의 절대 성능을 뜻하지 않습니다. 후보 간 상대 비교에만 씁니다.
- 결제 수신 경로만 처리량을 측정합니다. commerce·settlement·알림 그룹은 지금도 동시 실행 1이라 파티션 수가 처리 병렬도를 바꾸지 않습니다.
- 브로커 1대·복제 1 구성이므로 복제·리더 분산에 따른 파티션 비용은 다루지 않습니다.
- 30초 단계 시험이며 15분 지속 TPS 판정을 대신하지 않습니다.
