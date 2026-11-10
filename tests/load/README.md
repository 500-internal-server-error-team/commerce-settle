# 주문·결제 부하·장애·복구 시험

[#133](https://github.com/500-internal-server-error-team/commerce-settle/issues/133)의 결제 처리량, PG 장애, Worker 재시작 뒤 정합성을 측정합니다. 지표 정의는 [검증 지표와 완료 기준](../../docs/design.md#검증-지표와-완료-기준)를 따릅니다.

## 측정 범위

Commerce가 발행하는 것과 같은 `OrderPaymentRequested` v1을 `commerce-settle.commerce.v1`에 직접 넣고, Payment 수신부터 finance-mock 승인과 결과 Outbox 발행까지 측정합니다. 주문 API·Gateway 구간과 Commerce·Settlement의 결과 반영은 포함하지 않습니다.

- **결제 확정 TPS:** Payment DB에서 `CONFIRMED`가 된 결제의 초당 건수
- **수신→확정 지연:** Inbox 저장 시각(DB)부터 결제 확정 시각까지. 같은 Docker VM의 시계라 호스트 시계 오차가 들어가지 않습니다.
- **미완료:** `PENDING`·`PROCESSING`·`UNKNOWN` 결제. 처리 대기 결제 요청(Inbox `WAITING`), Kafka lag, 미발행 Outbox는 따로 셉니다.
- 아래 `ramp --mix` 예제는 카드·혼합(마일리지 1,000점)·간편결제·전액 마일리지를 같은 비율로 섞습니다. `--mix`를 생략하면 카드만 주입합니다.

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
- 상태 관측이 실패하면 추가 주입을 중지하고 주입 스레드·요청 원장·producer 종료를 확인한 뒤 오류로 끝납니다. 이때 성공 요약은 만들지 않으며, 이미 접수한 메시지는 취소하지 않습니다. 남은 요청 원장과 실제 Kafka·거래 상태를 대조하세요.
- `scenario`는 PG 시나리오를 바꿔 Worker를 다시 만들고 초당 100건을 60초 넣은 뒤 대조합니다. `restart`는 시작 25초 뒤 `payment-worker`를 SIGKILL하고 다시 띄웁니다.
- `prepare`는 시험 스택의 finance-mock DB에 `load-pg-*` 시나리오를 직접 넣습니다.
- `replay`는 시험 스택의 `payment-api`에만 운영자 자격(`operator:load`)을 설정합니다. 자격은 `tests/load/work/operator.credential`에 무작위로 만듭니다.
- 주입 원장·표본·요약·대조 결과는 Git에서 제외한 `tests/load/work/<실행 이름>/`에 남습니다. `LOAD_WORK_DIR`를 지정하면 그 경로를 사용합니다.

도구의 대조·재처리 회귀 검사는 `python3 -B -m unittest discover -s tests/load -p 'test_payment_load.py'`로 실행하며 CI에도 포함합니다. 메모리 SQLite의 대조 쿼리와 재처리 API 대역을 사용하므로 실제 PostgreSQL·PG의 부하·복구 시험을 대신하지 않습니다.

부하/복구 후 Settlement의 영속 대조와 원천별 HTTP 검사는 [정산 대조 실행](../../docs/development.md#정산-대조-실행과-재개)의 `scripts/reconcile.py`로 연결합니다. 기존 `payment_load.py reconcile`은 시험 주입 원장과 실제 처리 결과의 누락·중복 검사를 계속 맡습니다. 새 읽기 계약의 검사가 주입 원장 검사를 대체하지 않습니다.

### 종료

주입 도구와 UI 제어 서버를 먼저 종료합니다. 기동 때 사용한 `LOAD_PROJECT`와 절대경로 `LOAD_WORK_DIR`를 지정한 상태에서 저장소 루트에서 실행합니다. 기본 CLI 환경은 각각 `commerce-settle-load`와 `<저장소>/tests/load/work`이며, UI 환경은 UI 준비 때 선택한 값을 그대로 사용합니다. 아래 명령은 **선택한 프로젝트와 볼륨만 삭제**하며 원장·요약 파일은 보존합니다. 변수가 없으면 Docker를 실행하지 않습니다.

```bash
: "${LOAD_PROJECT:?기동에 사용한 프로젝트를 지정하세요}" \
  "${LOAD_WORK_DIR:?기동에 사용한 결과 경로를 지정하세요}" &&
AUTH_REDIS_RUN_ID="${AUTH_REDIS_RUN_ID:-0000000000000000000000000000000000000000}" \
LOAD_OPERATOR_CREDENTIAL="$(cat "$LOAD_WORK_DIR/operator.credential")" \
  docker compose -p "$LOAD_PROJECT" -f compose.yml -f tests/load/compose.load.yml down -v
```

기존 운영자 자격을 읽고 Redis 승인 값은 Compose 렌더링에만 공급합니다. 공용 `.env`나 다른 프로젝트의 설정은 바꾸지 않습니다.

### 대조 항목

`reconcile`은 현재 스택에서 넣은 요청 원장을 기준으로 다음을 확인하고, 하나라도 어긋나면 실패로 끝납니다.

- 요청마다 결제가 정확히 하나이고 총액·마일리지·외부 결제액이 같으며 확정 또는 거절로 끝남
- 외부 결제액이 있는 결제마다 승인 시도가 하나이고 업무 키가 결제 회차와 같음. finance-mock 거래와 금액·상태(확정↔`SUCCEEDED`, 거절↔`DECLINED`)가 같고, 결제에 연결되지 않은 대역 거래가 없음
- 구매자 마일리지 잔액이 확정한 마일리지만큼 줄고 예약이 남지 않음. 결제마다 원장이 확정이면 RESERVE·DEBIT, 거절이면 RESERVE·RELEASE 한 건씩
- 간편결제 시험 지갑 잔액이 성공한 승인 금액만큼 줄고 예약이 남지 않음
- 결제마다 `PaymentAccepted`와 `PaymentConfirmed`(또는 `PaymentDeclined`)가 한 건씩이고 모두 발행됨
- 끝나지 않은 Inbox·결제 작업과 Inbox 본문 충돌이 0

## 장애 재현과 복구

`scenario`의 입력은 다음과 같습니다. 시나리오 정의와 주입·대조 구현은 [payment_load.py](payment_load.py), 시험 스택 설정은 [overlay](compose.load.yml), 처리기 실행 설정은 [개발 환경](../../docs/development.md#kafka-전달-기반)을 따릅니다.

| 시나리오 | 입력 |
| --- | --- |
| `latency` | 승인 지연 200~800ms |
| `decline` | 확정 거절 20% |
| `loss` | 응답 유실 20% |
| `lookup-fail` | 응답 유실 20% + 조회 실패 50% |
| `restart` | 정상 PG, 시작 25초 뒤 Worker SIGKILL·재기동 |

복구 시간은 주입이 끝난 뒤 미완료·처리 대기·Kafka lag·미발행 Outbox가 모두 0이 될 때까지입니다. 조회 시도를 소진한 결제 작업은 `REVIEW_REQUIRED`로 남기며, 결제는 미확정 상태와 예약을 유지합니다. `replay`는 `POST /internal/payment-jobs/{jobId}/replays`로 같은 업무 키의 원거래 조회를 재개합니다. 재처리 뒤에도 위 대조를 다시 실행합니다.

## 실측 기록

2026-11-03의 [시험 환경·단계별 수치·병목 분석·장애 복구 관측 원문](https://github.com/500-internal-server-error-team/commerce-settle/blob/7a3affbd34cde810951d21e35aefcb4aa557dafc/tests/load/README.md#시험-환경)을 보존합니다. 당시 60초 단계 시험의 관측이며, 15분 지속 가능 TPS 판정을 대신하지 않습니다.

## 일반 주문 쿼리 비교

일반 주문의 SQL 비용은 별도 PostgreSQL Testcontainer와 Commerce HTTP 서버로 비교합니다. Java 21에서 실행하며 기존 Compose 환경이나 볼륨을 사용하지 않습니다.

```bash
ORDER_QUERY_BENCHMARK=true ./gradlew --no-daemon :commerce:test --rerun-tasks \
  --tests '*OrderIntegrationTest.measuresOrderQueryCost'
```

구매자·주소·상품 ID와 요청 본문·멱등키를 고정합니다. 구매자 20명/상품 20개 분산과 구매자 20명/상품 1개 집중 순서로 각각 100건 워밍업 후 400건을 동시 클라이언트 4개가 보냅니다. 쿠폰·이벤트·마일리지는 없고, 상품은 10,000원·수량 1개입니다. 모든 응답의 201과 최종 가용·예약 수량을 검사합니다. 기준선과 후보를 같은 JDK·PostgreSQL 이미지·JVM/DB 자원·부하 발생기·주변 스택 조건에서 반복 실행하고, revision과 이미지 ID를 함께 기록합니다.

결과는 `commerce/build/test-results/test/TEST-…OrderIntegrationTest.xml`의 `ORDER_QUERY_BENCHMARK` JSON입니다. `jdbcExecutions`는 JDBC 직접 설정문과 JPA SQL의 실행 호출 수이며 `commit`·`rollback` 같은 연결 제어와 드라이버 내부 SQL은 제외합니다. 네트워크 왕복·패킷 수가 아닙니다. `jdbcMs`는 클라이언트에서 잰 SQL 실행 호출 시간의 합으로 네트워크·서버 대기를 포함하고, PostgreSQL `execution_ms`는 `pg_stat_statements`의 서버 실행 시간 합입니다. 서버 집계에는 계측용 설정·연결 확인문이 포함될 수 있습니다.

`httpMs`·`acquireMs`는 각각 HTTP 왕복과 연결 획득 시간의 p50/p95/p99(ms)입니다. `lockWaitBackendSamples`는 10ms 간격 조회마다 관측한 잠금 대기 backend 수의 합이며 대기 시간이나 고유 거래 수가 아닙니다. 표본 사이의 짧은 대기는 놓칠 수 있습니다. 동시 클라이언트가 응답 후 다음 요청을 보내는 시험이므로 목표 도착률 200회/초 검증이나 Gateway·금융 후속 처리 성능으로 해석하지 않습니다.

SQL 중복 방지 회귀는 환경 변수 없이 기본 테스트의 `locksInventoryOncePerNewOrder`가 검사합니다. 성능 비교 기록과 채택 근거는 [#246](https://github.com/500-internal-server-error-team/commerce-settle/issues/246)에 남깁니다.
