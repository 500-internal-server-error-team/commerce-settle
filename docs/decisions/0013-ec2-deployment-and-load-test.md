# EC2 2대 배포와 부하 시험 자원 운영

- 제안일: 2026-11-09
- 상태: 제안·팀 검토 중. AWS 배포·성능·저장 용량은 미검증
- 범위: 2주 시험 예산, EC2 사양, 시험 데이터 초기화 조건

## 배포안과 선택 이유

[기존 배포 계획](../architecture.md#인프라-구조도)의 ECS 클러스터와 **EC2 총 2대**를 유지하고, 앱은 CPU, 데이터·관측은 메모리 여유를 우선하는 혼합 사양을 제안합니다. 평소에는 작은 사양을 사용하다 시험 시간에만 올립니다. 현재 실행·종료 방법은 [개발 환경](../development.md)과 [부하 시험 도구](../../tests/load/README.md)가 기준입니다.

| 역할 | 배치 | 평소 | 시험 시작 사양 |
| --- | --- | --- | --- |
| 앱 | Gateway·Commerce·Payment API/Worker·Settlement·Finance Mock | `t4g.large` — 2 vCPU·8GiB | `c7g.xlarge` — 4 vCPU·8GiB |
| 데이터·관측 | PostgreSQL·Kafka·Redis·관측 도구 | `t4g.large` — 2 vCPU·8GiB | `m7g.xlarge` — 4 vCPU·16GiB |

**호스트마다 16GiB가 최소라는 근거는 없습니다.** 위 사양은 측정 시작점이며 1,000 TPS를 보장하지 않습니다. 앱은 CPU를 지속 사용하는 `c7g` 후보로 시작하고, 메모리 압박·GC·종료가 관측되면 조정합니다. `c7g.2xlarge`는 16GiB와 함께 CPU도 8 vCPU로 늘어나므로 메모리만 필요하다는 이유로 선택하지 않습니다.

데이터 호스트는 Redis 데이터, PostgreSQL 버퍼, PostgreSQL·Kafka가 사용하는 OS 페이지 캐시와 관측 도구가 메모리를 공유하므로 16GiB를 출발점으로 둡니다. 현재 Redis의 `maxmemory`는 `128mb`이므로 Redis가 있다는 사실만으로 16GiB를 최소치로 잡지는 않습니다. RAM 증설은 일부 읽기 I/O와 메모리 압박을 줄일 수 있지만 WAL 동기 기록·디스크 지연·IOPS·DB 잠금의 해결을 보장하지 않습니다. `m7g.xlarge`는 메모리와 CPU를 함께 확보하는 범용 후보입니다. 메모리 중심의 `r7g.large`는 같은 16GiB에 더 저렴하지만 CPU가 2 vCPU이므로 DB·Kafka·관측의 CPU 경합을 비교한 뒤 선택합니다. 컨테이너 메모리 한도와 DB 설정도 호스트 예산 안에서 함께 조정해야 합니다.

### 4대 대신 2대를 선택하는 이유

API·Worker·데이터·관측을 4대로 분리하면 부하와 호스트 장애의 영향을 더 나눌 수 있습니다. 다만 이번 목표는 **총 195,000원의 2주 예산과 EC2 2대 제한 안에서 기능을 시연하고 병목 개선 전후를 반복 측정하는 것**입니다. 호스트를 추가하는 대신 앱과 데이터에 CPU·메모리 여유와 시험 시간을 우선 배분합니다. 같은 총자원을 4대로 나누면 호스트별 OS·에이전트의 기본 사용량과 분산된 메모리 여유를 감수하고, 네트워크·배포·관측·복구 관리 대상도 늘어납니다. 대수만으로 비용이 두 배가 되거나 고가용성이 생긴다고 판단하지 않습니다.

두 호스트에서도 서비스별 ECS Task의 독립 배포·자원 상한·실행 개수 조절은 유지하되 호스트의 남은 자원 안에서 수행합니다. API와 Worker의 CPU 경합, PostgreSQL·Kafka·Redis·관측의 메모리·I/O 경합은 남습니다. 앱 호스트 장애는 앱 전체에, 데이터 호스트 장애는 공유 저장소·메시징·관측에 영향을 줍니다. 2대안은 비용·운영 범위와 맞바꾼 선택이며 고가용성 구성이 아닙니다. 자원 조정 후에도 경합이 목표를 막거나 호스트 장애 격리가 필요해지면 분리 배치를 재검토합니다.

k6를 실행하는 부하 발생기는 **기존 로컬 PC**, 요청을 받는 앱은 첫 번째 EC2에 둡니다. 임시 EC2는 추가하지 않습니다. 발생기 CPU·실제 주입률·`dropped_iterations`와 인터넷 대역폭·지연을 기록하고, 주입 목표를 채우지 못한 결과를 서버 한계로 해석하지 않습니다. 다섯 명은 같은 환경을 번갈아 사용합니다.

## 예산 추정

서울 리전 Linux 온디맨드의 2026-11-09 조회 단가입니다. 1인 39,000원씩 총 **195,000원**에서 예비비 10%를 남깁니다. 환율 1,450원/달러와 VAT 10%는 계산 가정이며 실제 청구 환율·세금은 계정에서 확인합니다.

| 인스턴스 | vCPU·메모리 | 시간당 단가 | 비교 목적 |
| --- | --- | --- | --- |
| `t4g.large` | 2·8GiB | $0.0832 | 평소 운영 |
| `c7g.xlarge` | 4·8GiB | $0.1632 | 앱 시험 후보 |
| `m7g.xlarge` | 4·16GiB | $0.2006 | 데이터·관측 시험 후보 |
| `r7g.large` | 2·16GiB | $0.1292 | 데이터 메모리 비용 절감 대안. CPU 여력 확인 필요 |

- 평소 2대의 336시간 컴퓨팅·공인 IPv4 2개·gp3 합계 120GB 유지비: 약 $64.38, 약 102,700원.
- 시험 중 `c7g.xlarge` + `m7g.xlarge`의 컴퓨팅 단가는 합계 $0.3638/시간입니다. 평소 2대와의 차액은 $0.1974/시간, 약 315원/시간입니다.
- 시험에 남는 예산 약 72,800원으로 **팀 합계 약 231시간, 균등 배분하면 인당 약 46시간**입니다. 준비·워밍업·적체 배출을 포함해 시험 사양을 켠 시간이며 로컬 발생기의 AWS 비용은 없습니다.

계산은 기존 2대의 사양 변경 시간에 평소 컴퓨팅 요금을 중복 청구하지 않았습니다. gp3는 $0.0912/GB·월, 한 달은 720시간으로 계산했습니다. NAT·로드 밸런서·스냅샷·CloudWatch Logs·이미지 저장·추가 디스크 성능·과금 대상 통신은 미포함이므로 **전체 AWS 청구액이나 시험 시간의 보장값은 아닙니다.** 크레딧은 반영하지 않았으며, 실제 구성의 총비용을 확정한 뒤 시간을 배정합니다.

## 거래 수와 디스크 용량

**1,000 TPS × 15분 = 90만 건은 서로 다른 결제의 확정 건수입니다. 전체 DB 행 수나 디스크 용량이 아닙니다.** [측정 기준](../design.md#검증-지표와-완료-기준)을 만족했을 때의 계산이며 달성 실적도 아닙니다.

네 DB의 스키마와 주요 저장 경로에는 다음 대상이 있습니다. 아래는 용량 산정을 위한 확인 범위이며, 모든 분기의 행 수·바이트를 계산한 결과는 아닙니다.

| DB | 저장량을 확인할 대상 |
| --- | --- |
| [Commerce](../../infra/postgres/schema/commerce.sql) | 주문·배송지 사본·재고/쿠폰 이력·알림·Inbox/Outbox·멱등/감사 기록 |
| [Payment](../../infra/postgres/schema/payment.sql) | 결제·승인 시도·처리 작업·마일리지 예약/원장·Inbox/Outbox·환불/복구 기록 |
| [Settlement](../../infra/postgres/schema/settlement.sql) | 정산 항목·원장·지급/배치·Inbox/Outbox·대조 기록 |
| [Finance Mock](../../infra/postgres/schema/finance_mock.sql) | 승인/환불/지급 거래·멱등 기록·예약·자금 원장 |

결제 수가 같아도 카드·마일리지·간편결제 비율, 환불·재시도·알림·배치의 활성화에 따라 저장량이 달라집니다. 테이블 외에도 인덱스·큰 값 저장 공간(TOAST), PostgreSQL 변경 기록(WAL), Kafka 메시지, 컨테이너 로그·지표·시험 원장을 포함해야 합니다. **120GB는 비용 계산용 가정이며 필요 용량이나 충분한 용량으로 검증하지 않았습니다.**

시험 종류·입력 데이터·설정을 고정하고 작은 부하부터 늘리며 네 DB 각각의 `pg_database_size`와 테이블별 `pg_total_relation_size`, 데이터 파일시스템의 최대 사용량을 기록합니다. 관계 크기는 인덱스·TOAST를 포함하므로 이를 다시 더하지 않습니다. WAL 생성량과 실제 보유량, Kafka·로그·관측 데이터는 별도로 확인하고 서로 다른 부하 규모에서 증가 추이를 비교한 뒤 한 회차에 필요한 용량과 여유를 정합니다.

## 시험 데이터 초기화 제안

시험 시작마다 DB를 자동 삭제하지 않습니다. **새 독립 시험을 명시적으로 선택했을 때만**, 이전 시험의 결과와 분석에 필요한 원문을 보존한 뒤 전용 시험 환경을 같은 초기 상태로 준비하는 방식을 제안합니다. 공유 시연 환경과 복구·재시작·누적 데이터 시험은 초기화 대상에서 제외합니다.

1. 이전 주입을 멈추고 남은 처리·정합성 대조 결과를 확인합니다. 실패·미확정이 남으면 원인 분석이나 복구에 필요한 상태를 보존하고 새 시험을 시작하지 않습니다.
2. 입력 조건·코드/설정 버전·요청 원장·TPS/지연/오류·자원 사용량·대조 결과를 삭제 대상 볼륨 밖에 보존합니다.
3. 초기화할 전용 환경·볼륨과 다른 실행이 없음을 확인한 뒤, 해당 환경의 네 DB·Kafka 메시지와 소비 위치·Redis를 함께 초기화하고 같은 초기 데이터를 적재합니다. DB만 지워 이전 메시지가 다시 처리되는 상태를 만들지 않습니다.
4. Redis 실행 식별자를 다시 준비하고 스키마·인증·초기 데이터·연결을 확인한 뒤 워밍업과 주입을 시작합니다.

이 자동화는 **미구현**입니다. 현재 `payment_load.py up`은 DB를 비우지 않으며 `prepare`는 Kafka 소비 위치·모의 시나리오·지갑을 준비하는 명령입니다. 기존 전용 환경 삭제 범위와 보존 조건은 [부하 환경 종료](../../tests/load/README.md#종료), 공유·기존 데이터 보호는 [DB 정책](../migrations.md)을 따릅니다.

EBS 내부 데이터를 비워도 할당 용량과 요금은 줄지 않습니다. 할당 용량을 계속 늘리는 대신 시험 데이터를 재사용 가능한 범위로 유지하고 Kafka·컨테이너 로그·관측 데이터의 보존량을 제한합니다. EBS는 직접 축소할 수 없으므로 초기 용량은 측정 후 정합니다.

## 채택 전 확인

- **시험 범위:** 기존 도구는 Kafka 직접 주입부터 Payment·Finance Mock까지의 부분 시험입니다. 주문 API·Gateway·전체 정산을 포함하는 시험과 구분하고 입력 비율·오류 조건·완료 기준을 고정합니다.
- **성능·용량:** 단계별 처리량·적체·오류·지연, 네 DB의 증가량과 최대 디스크 사용량, 발생기의 주입 능력을 확인합니다. 현재 앱/PostgreSQL의 `mem_limit: 512m`과 JVM `ActiveProcessorCount=2`도 검토합니다. 후자는 JVM의 병렬성 계산에 영향을 주는 값이며 CPU 사용량 상한은 아닙니다.
- **AWS 적용:** ARM 이미지·ECS Task 자원과 배치·호스트 간 연결·Gateway 진입점/TLS·관리 포트 접근·EBS 영속 경로와 재기동/복구를 검증합니다. Compose의 볼륨·네트워크·종료 명령을 ECS에 그대로 적용하지 않습니다.
- **운영·비용:** 로컬 발생기와 서버 사이의 네트워크 영향, 로그 보존·전체 견적·예산 알림을 확인합니다. 시험 후 평소 사양으로 되돌리고 불필요한 IP/볼륨을 정리하는 담당과 확인 절차를 정합니다.

## 외부 근거

- [AWS 서울 리전 EC2·EBS 단가](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-northeast-2/index.csv), [공인 IPv4 요금](https://aws.amazon.com/vpc/pricing/)
- [C7g 사양과 용도](https://aws.amazon.com/ec2/instance-types/c7g/), [M7g](https://aws.amazon.com/ec2/instance-types/m7g/), [R7g](https://aws.amazon.com/ec2/instance-types/r7g/)
- [EBS 과금](https://aws.amazon.com/ebs/pricing/), [EBS 용량 변경 제한](https://docs.aws.amazon.com/ebs/latest/userguide/ebs-modify-volume.html)
- [ECS Task의 ARM·자원·네트워크 설정](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters_ec2.html), [ECS 호스트 데이터 보존](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/bind-mounts.html)
- [PostgreSQL DB·테이블 용량 함수](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-DBSIZE)
- [PostgreSQL 메모리와 OS 캐시](https://www.postgresql.org/docs/current/runtime-config-resource.html#RUNTIME-CONFIG-RESOURCE-MEMORY)
