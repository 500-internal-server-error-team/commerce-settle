# 결제 부하·장애·복구 시험

[#133](https://github.com/500-internal-server-error-team/commerce-settle/issues/133)의 결제 처리량, PG 장애, Worker 재시작 뒤 정합성을 측정합니다. 지표 정의는 [ADR 0002](../../docs/decisions/0002-performance-and-integrity-metrics.md)를 따릅니다.

## 측정 범위

Commerce의 신규 주문 접수 제한은 관측 연결([#166](https://github.com/500-internal-server-error-team/commerce-settle/pull/166)) 전에는 닫혀 있어 `POST /api/orders`가 항상 503입니다. 이 제한은 우회하지 않습니다. 그래서 Commerce가 발행하는 것과 같은 `OrderPaymentRequested` v1을 `commerce-settle.commerce.v1`에 직접 넣고, Payment 수신부터 finance-mock 승인과 결과 Outbox 발행까지 측정합니다. 주문 API·Gateway 구간과 Commerce·Settlement의 결과 반영은 포함하지 않습니다. #166 병합 뒤 같은 도구에 주문 API 경로를 더해 다시 측정합니다.

- **결제 확정 TPS:** Payment DB에서 `CONFIRMED`가 된 결제의 초당 건수
- **수신→확정 지연:** Inbox 저장 시각(DB)부터 결제 확정 시각까지. 같은 Docker VM의 시계라 호스트 시계 오차가 들어가지 않습니다.
- **미완료:** `PENDING`·`PROCESSING`·`UNKNOWN` 결제. 처리 대기 결제 요청(Inbox `WAITING`), Kafka lag, 미발행 Outbox는 따로 셉니다.
- 요청은 카드·혼합(마일리지 1,000점)·간편결제·전액 마일리지를 같은 비율로 섞습니다.

## 실행

기존 개발 볼륨을 건드리지 않도록 별도 Compose 프로젝트(`commerce-settle-load`)로 띄웁니다. `python3 scripts/init_env.py`로 `.env`를 채우고 `./gradlew assemble`로 jar를 만든 뒤 실행합니다. Redis 승인 값(`AUTH_REDIS_RUN_ID`)은 공용 `.env`를 바꾸지 않고 환경 변수로만 넘깁니다.

```bash
python3 tests/load/payment_load.py up        # 스택 기동. 수신 Listener는 꺼 둔다
python3 tests/load/payment_load.py prepare   # 빈 토픽의 수신 그룹 offset, PG 시나리오, 마일리지·간편결제 지갑
python3 tests/load/payment_load.py enable    # payment-worker의 수신·처리·승인(http)·발행을 켠다
python3 tests/load/payment_load.py ramp --stages 50:60,100:60,200:60,400:60 --mix card,mixed,easy,mileage
python3 tests/load/payment_load.py scenario loss      # latency·decline·loss·lookup-fail·restart
python3 tests/load/payment_load.py replay             # 확인 대상 작업을 운영 재처리 API로 다시 대기
python3 tests/load/payment_load.py reconcile          # 원장 ↔ Payment ↔ finance-mock 대조
```

- `ramp`는 단계별 도착률로 넣으며 일정 간격으로 상태를 기록하고, 주입 뒤 적체가 빌 때까지(기본 최대 300초) 기다린 뒤 단계별 처리량·적체·지연을 `summary.json`에 남깁니다.
- `scenario`는 PG 시나리오를 바꿔 Worker를 다시 만들고 초당 100건을 60초 넣은 뒤 대조합니다. `restart`는 시작 25초 뒤 `payment-worker`를 SIGKILL하고 다시 띄웁니다.
- finance-mock의 시나리오 관리 API([#146](https://github.com/500-internal-server-error-team/commerce-settle/issues/146))가 없어 `prepare`가 `load-pg-*` 시나리오를 finance-mock DB에 직접 넣습니다.
- `replay`는 시험 스택의 `payment-api`에만 운영자 자격(`operator:load`)을 설정합니다. 자격은 `tests/load/work/operator.credential`에 무작위로 만듭니다.
- 주입 원장·표본·요약·대조 결과는 Git에서 제외한 `tests/load/work/<실행 이름>/`에 남습니다. 정리는 `docker compose -p commerce-settle-load -f compose.yml -f tests/load/compose.load.yml down -v`입니다.

도구의 대조·재처리 회귀 검사는 `python3 -B -m unittest discover -s tests/load -p 'test_payment_load.py'`로 실행하며 CI에도 포함합니다. 메모리 SQLite의 대조 쿼리와 재처리 API 대역을 사용하므로 실제 PostgreSQL·PG의 부하·복구 시험을 대신하지 않습니다.

### 대조 항목

`reconcile`은 현재 스택에서 넣은 요청 원장을 기준으로 다음을 확인하고, 하나라도 어긋나면 실패로 끝납니다.

- 요청마다 결제가 정확히 하나이고 총액·마일리지·외부 결제액이 같으며 확정 또는 거절로 끝남
- 외부 결제액이 있는 결제마다 승인 시도가 하나이고 업무 키가 결제 회차와 같음. finance-mock 거래와 금액·상태(확정↔`SUCCEEDED`, 거절↔`DECLINED`)가 같고, 결제에 연결되지 않은 대역 거래가 없음
- 구매자 마일리지 잔액이 확정한 마일리지만큼 줄고 예약이 남지 않음. 결제마다 원장이 확정이면 RESERVE·DEBIT, 거절이면 RESERVE·RELEASE 한 건씩
- 간편결제 시험 지갑 잔액이 성공한 승인 금액만큼 줄고 예약이 남지 않음
- 결제마다 `PaymentAccepted`와 `PaymentConfirmed`(또는 `PaymentDeclined`)가 한 건씩이고 모두 발행됨
- 끝나지 않은 Inbox·결제 작업과 Inbox 본문 충돌이 0

대역 거래 금액 +1원과 마일리지 잔액 −1점을 넣은 DB에서 두 불일치를 모두 찾는 것을 확인했습니다.

## 시험 환경

Windows 11, Docker Desktop 한 대. 앱 컨테이너는 메모리 512MiB, JVM CPU 2개(`ActiveProcessorCount=2`, Serial GC), PostgreSQL 512MiB, Kafka 1GiB(파티션 3)이며 Compose 기본값 그대로입니다. Payment Worker 인스턴스는 하나입니다.

## 처리량 결과 (2026-11-03)

각 측정은 빈 스택에서 초당 50·100·200·400건을 60초씩 넣은 45,000건입니다. 초당 확정은 단계의 첫 표본을 뺀 평균입니다.

| 측정 | 바꾼 것 | 50/초 | 100/초 | 200/초 | 400/초 | 끝난 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 기준(순차 실행) | 26.6 | 27.5 | 27.1 | 27.0 | 확정 12,616, 미완료 15,292, 대기 17,200 |
| 2 | 승인 Worker 동시 실행 6슬롯 | 48.1 | 49.3 | 48.8 | 48.2 | 확정 22,313, 대기 22,651 |
| 3 | 결제 요청 처리기 동시 실행 3슬롯 | 50.6 | 99.3 | 95.8 | 54.7 | 전부 확정, 적체 185초 |
| 4 | 예약 작업 스레드 4개 | 50.8 | 99.2 | 174.3 | 174.6 | 전부 확정, 미발행 44,010 |
| 5 | 수신 3·Outbox 발행 32·DB 연결 20 | **49.5** | **101.9** | **194.2** | **205.9** | 전부 확정, 적체 38초 |

측정 5의 수신→확정 p99는 50·100·200/초에서 3.1·1.4·2.3초입니다. 400/초에서는 처리 대기가 쌓여 44초입니다. Kafka lag는 200/초까지 14건 이하, 400/초에서 최대 309건입니다.

**60초 단계별 시험에서 관측한 결제 확정 처리량: 약 200 TPS(이 환경).** 목표 1,000 TPS에는 미달하며, ADR 0002의 15분 지속 가능 TPS 판정은 아직 수행하지 않았습니다.

### 찾은 병목과 수정

1. **승인 Worker 순차 실행**(측정 1): 작업을 한 건씩 처리하고 20건마다 1초를 쉬어 도착률과 무관하게 초당 약 27건이었습니다. 동시 실행 슬롯(`app.payment-approval.concurrency`, 기본 6)과 상한 도달 시 연속 폴링을 추가했습니다(#162).
2. **결제 요청 처리기 순차 실행**(측정 2): 100건마다 1초를 쉬며 한 건씩 처리해 초당 약 49건에서 Inbox `WAITING`이 계속 늘었습니다. 동시 실행 슬롯(`app.payment-request-processor.concurrency`, 기본 3)을 추가했습니다.
3. **예약 작업 스레드 공유**(측정 3): Payment의 `@Scheduled` 네 개가 스레드 하나를 나눠 써, 쉬지 않는 처리기가 승인 폴링을 약 2분 막았습니다. 그 동안 승인 시도가 0건이었습니다. 스레드를 4개로 나눴습니다(#162).
4. **수신·발행**(측정 4): 수신 Listener가 파티션 3개를 스레드 하나로 읽어 초당 약 190건, Outbox가 동시 전송 8건이라 초당 약 55건(확정 1건당 이벤트 2건)에서 막혔습니다. Listener 동시 실행 3, Outbox 동시 발행 32, DB 연결 풀 20으로 올렸습니다.
5. **남은 한계**(측정 5): 400/초에서는 결제 요청 처리기(3슬롯)가 초당 약 210건에서 다시 막히고, 적체를 비울 때 초당 255~280건까지 나옵니다. `payment-worker`가 메모리 512MiB 중 473MiB를 썼습니다. 1,000 TPS는 슬롯 설정만으로는 어렵고 컨테이너 CPU·메모리, PostgreSQL 자원, Worker 수평 확장을 함께 늘려 다시 측정해야 합니다.

## 장애·복구 결과

측정 5와 같은 설정의 한 스택에서 차례로 실행했습니다. 각 시나리오는 초당 100건 60초(6,000건)이며, 복구 시간은 주입이 끝난 뒤 미완료·처리 대기·Kafka lag·미발행 Outbox가 모두 0이 될 때까지입니다.

| 시나리오 | PG 설정 | 결과 | 복구 시간 |
| --- | --- | --- | --- |
| latency | 승인 지연 200~800ms | 전부 확정. 처리량이 초당 약 37건으로 떨어져 미완료 최대 3,902건 | 372초 |
| decline | 확정 거절 20% | 확정 5,108·거절 892. 거절 결제의 마일리지 예약 해제·`PaymentDeclined` | 0초 |
| loss | 응답 유실 20% | 전부 확정. 재승인 없이 원거래 조회로 확정, 대역 거래 중복 0 | 6.7초 |
| lookup-fail | 응답 유실 20% + 조회 실패 50% | 5,997건 확정, 3건은 시도 10회 소진으로 `REVIEW_REQUIRED` | 자동 복구 없음 → 운영 재처리 |
| restart | 정상. 25초에 Worker SIGKILL 뒤 재기동 | 전부 확정. 재기동하는 약 20~30초 동안 확정 0건, Kafka lag 최대 3,489건 | 29.5초 |

- **lookup-fail의 3건:** 결과를 모르는 결제를 실패나 거절로 바꾸지 않았고 마일리지 예약도 유지했습니다(finance-mock에는 승인 성공). `replay`로 `POST /internal/payment-jobs/{jobId}/replays`(#181)를 호출하자 같은 업무 키로 원거래를 조회해 3건 모두 확정됐고, 운영자 감사 기록 3건이 남았습니다.
- **latency:** 승인 처리량은 대략 슬롯 수 ÷ PG 지연입니다. PG 지연이 길면 승인 슬롯을 늘려야 같은 TPS를 유지합니다.
- **최종 대조:** 시험 스택 전체 75,000건(확정 74,108, 거절 892)에서 결제 누락·중복, 금액 불일치, 이중 승인, 마일리지·간편결제 지갑 잔액 오차, 결과 이벤트 누락·중복이 모두 0입니다. 측정 5의 45,000건도 따로 대조해 통과했습니다.

## 남은 일

- #166 병합 뒤 주문 API부터 결제 확정, Commerce 결과 반영까지 같은 방식으로 측정
- 15분 지속 시험. 이번 단계는 60초씩이라 ADR 0002의 지속 가능 TPS 판정(15분, 1만 건 이상 p99)에는 모자랍니다.
- 1,000 TPS를 위한 자원 확장과 Worker 수평 확장 측정
- 환불 SAGA(#168) 병합 뒤 환불 혼합 부하와 대조 항목 추가
