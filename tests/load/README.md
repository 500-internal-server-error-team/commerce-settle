# 주문·결제 부하·장애·복구 시험

[#133](https://github.com/500-internal-server-error-team/commerce-settle/issues/133)의 결제 처리량, PG 장애, Worker 재시작 뒤 정합성을 측정합니다. 지표 정의는 [검증 지표와 완료 기준](../../docs/design.md#검증-지표와-완료-기준)를 따릅니다.

## 실험 준비와 분석

주문 API·Payment 직접 주입·SQL 비교 등 시험 경로에 맞춰 적용합니다. [실험 보고 양식](../../.github/ISSUE_TEMPLATE/experiment-report.md)에 계획과 실행별 근거를 연결하며, 공통 지표 정의와 업무별 완료 조건은 위 검증 기준을 따릅니다.

### 실행 전

1. 가설·변경 변수·주 지표·허용 회귀·오류율, 반복·분석 방법과 중단 조건을 먼저 정합니다. 경로·완료점·구매자/상품 집중도와 초기 데이터, 코드·이미지·자원·병렬도·발생기·관측 설정을 고정합니다. 예열·주입·측정·배출을 나누고 서로 다른 조건은 별도 실험으로 기록합니다.
2. 판정에 필요한 증거의 출처·수집 방법·주기·보존 위치를 정하고, 짧은 시범 입력으로 실제 수집을 확인합니다. 지표 신선도, 요청/업무 ID·추적·SQL의 연결, 시각 동기와 측정창, 원본 조회 가능 여부를 확인합니다. `UP`이나 대시보드 존재만으로 관측 준비를 통과시키지 않습니다.
3. 필요한 증거가 빠지면 수집을 보완한 뒤 본 시험을 시작합니다. 탐색적 실행을 먼저 할 때는 미확보 항목·영향·보완 계획을 기록하고 해당 원인·개선 판정을 보류합니다. 수집 자체의 CPU·메모리·DB 부하, timeout·샘플링·보관 한도를 확인하고 전후에 같은 조건을 유지합니다. 관측 실패 시 추가 주입 중단·배출·원키 대조를 수행할 방법도 확인합니다.

### 관측 수단과 한계

| 수단 | 현재 확인하는 범위 | 별도로 확인할 증거 |
| --- | --- | --- |
| [호스트 자원 수집](resource_sample.py) | Docker CPU·메모리, `vmstat`·`iostat` 표본 | SQL·잠금 보유자·접수 차단 사유는 수집하지 않음 |
| [금융 지표·추적](../../docs/development.md#금융-처리-시간과-비동기-추적) | 처리 단계·SQL 시간·연결 풀·대기열과 샘플 trace | 집계 지표와 샘플 trace만으로 모든 요청의 원인을 설명할 수 없음. 필요한 trace·로그를 보관 한도 전에 확보 |
| [일반 주문 쿼리 비교](#일반-주문-쿼리-비교) | JDBC 실행 수·SQL 시간·연결 획득·잠금 대기 표본 | 네트워크 왕복·전체 서비스 성능과 구분. 변경 경로의 신규·재요청·충돌과 분산·집중 입력을 함께 검토 |
| [업무 완료 지표 수집](#업무-완료전구간-지연의-전후-비교) | 이미 실행한 부하의 완료량·지연과 실제 DB 측정창 | 부하 주입·실행 조건 검증·금융 대조를 대신하지 않음 |

DB 잠금 원인을 판정하는 시험은 대기/보유 PID·쿼리 식별자 또는 민감값을 제거한 SQL, 트랜잭션 경과시간·잠금 대상의 연결을 별도로 수집합니다. 대기 건수만으로 특정 SQL을 병목으로 확정하지 않습니다. 접수 gate의 동작을 분석할 때는 전환 시각·결정 사유와 판정에 사용한 값·원본 시각·조회 오류를 연결합니다. 현재 상태 지표만으로 과거 차단 사유를 알 수 없으므로 필요한 수집을 따로 준비합니다. 바인딩 값·토큰·개인정보는 저장하지 않습니다.

### 사후 분석과 종료

1. 실제 주입·HTTP 상태별 성공/오류·업무 완료·미완료와 적체·배출 시간을 대조합니다. HTTP·DB 지표의 실제 측정창을 확인해 비교 가능한 구간을 고릅니다. SQL 실행 수 감소와 전체 처리 개선을 구분하고 SQL·연결 획득·잠금·외부 호출·비동기 대기 중 달라진 구간을 확인합니다. 전후 조건·측정창·표본 수가 다르면 그 차이와 판정 한계를 먼저 밝힙니다. 이전 계측이 없던 구간과 새 지표로 개선율을 만들지 않습니다.
2. 타임아웃·응답 미수신은 미접수의 증거가 아닙니다. 원래 키·실행 원장으로 실제 거래를 확인하고 새 키로 재전송하거나 초기화해 덮지 않습니다. 추가 주입을 멈춰도 접수된 거래를 보존하며, 배출 뒤 시험 범위의 주입 원장·원천별 금액·상태를 대조합니다. 미확정·예약·격리·적체가 남으면 담당자와 복구·재대조 조건을 기록합니다.
3. 관측 사실·원인 가설·판정·다음 행동을 구분합니다. 도구 종료, 목표 주입 달성, 성능·정합성 통과와 분석 마감을 따로 기록합니다. 원인을 확정하지 못해도 실패 회차와 부족한 증거를 보존하며, 추가 확인의 대상·방법·완료 조건을 남깁니다. 결론은 검토한 경로·입력·측정 범위에 한정합니다.
4. 실행별 입력·명령·버전/SHA·원장·지표 조회식/시간 구간·필요한 trace/로그·대조 결과를 기존 결과 경로에 보존하고 보고에서 연결합니다. 검토자가 접근할 수 있는 비밀정보 제거 근거를 제공하고, 접근 제한·보존 한도는 명시합니다. 시험용 상품·세션·프로세스·컨테이너와 공유 EC2는 해당 실행 절차대로 정리하거나 유지 담당·종료 조건을 남깁니다. 실패 기록과 필수 복구 근거는 정리로 삭제하지 않습니다.

## Mac에서 공개 HTTPS 주문 부하 시험

[order_load.py](order_load.py)는 공개 API로 주문을 준비하고 [orders.js](orders.js)를 Mac의 Docker k6에서 실행합니다. 요청은 **공개 HTTPS → Caddy → Gateway → 주문 API**를 거칩니다. Node.js나 서버의 k6 배포는 필요하지 않습니다. Python 3·실행 중인 Docker Desktop과 접근 가능한 배포 주소를 준비한 뒤 저장소 루트에서 실행합니다.

```bash
python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN
```

기본값은 **한 구매자·한 상품에 200건/초·60초(12,000건)**입니다. 먼저 낮은 부하로 실행 경로를 확인하려면 `--tps 2 --seconds 3`을 붙입니다. 기존 로컬 `order.json`이나 `.env`는 읽거나 덮어쓰지 않습니다.

`--tps`로 초당 주문 요청 수를 **1~1000**에서 선택합니다. 기존 `--rate`도 같은 옵션입니다. 실행 시간은 `--seconds`로 1~60초를 지정하며 기본값은 60초입니다.

```bash
python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN --tps 200
python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN --tps 400
python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN --tps 1000
```

기본 사전 VU는 `max(400, TPS×2)`, 최대 VU는 `max(1200, TPS×6)`이며 `--pre-allocated-vus`·`--max-vus`로 1~10000 안에서 조정합니다. 실제 선택값은 요약에 남습니다. 지정 TPS는 요청 도착률이며 서버의 결제 완료 TPS나 달성 보장이 아닙니다.

실행기는 생성한 주문 본문을 컨테이너의 `ORDER_FILE=/work/order.json`으로 전달합니다. `orders.js`를 직접 실행할 때도 `ORDER_FILE`에 준비된 주문 JSON을 지정할 수 있으며, 로그인 본문·잘못된 JSON은 요청 전에 거절합니다. 직접 k6 실행은 Python 실행기의 사전 PAID 확인·원장 저장·배출 대조·상품/세션 정리를 수행하지 않으므로, 전후 비교에는 위 Python 명령을 사용합니다.

### 서버와 실행 준비

- 팀과 시험 시간을 맞추고 앱·데이터 EC2와 기존 ECS 서비스를 기동합니다. 연결된 탄력적 IP를 유지한 같은 인스턴스의 재기동에서는 DNS를 바꾸지 않습니다. Redis 재시작 뒤 로그인 차단은 [인증 복구](../../docs/development.md#redis-세션-검증과-복구)로 해결하고, 기존 DB·토픽·offset을 초기화하지 않습니다.
- 관측 대상의 `UP`, `commerce_order_admission_open=1`, 금융 Worker·Outbox·Consumer 활성화를 확인합니다. 접수 gate는 정상 조건을 60초 연속 충족한 뒤 열립니다. 실제 이미지·CPU/메모리·DB 풀·Worker 동시성·샘플링과 시험 시각은 [전후 비교 조건](../../docs/development.md#병목-개선-전후-비교)대로 기록합니다.
- 개발 fixture를 켠 환경에서는 구매자 A·판매자 A를 기존 Swagger 로그인 경로로 인증합니다. fixture를 쓰지 않는 환경은 유효한 `BUYER_TOKEN`·`SELLER_TOKEN`을 환경 변수로 전달합니다. 두 계정은 시험 계정이어야 하며 토큰·비밀번호를 파일·명령 인자에 쓰지 않습니다. 이 도구가 발급한 세션만 끝에 로그아웃하고 전달받은 기존 세션은 유지합니다.

도구는 구매자의 기존 배송지를 선택하며 없을 때만 가상 주소를 등록합니다. 판매자 계정으로 새 `k6-<실행 ID>` 일반 상품을 만들고 재고를 **예정 주문 수 + 사전 주문 1개 + 여유분(최소 100개·10%)**만큼 준비합니다. 기존 fixture 상품이나 다른 상품의 재고는 바꾸지 않습니다. 실제 견적에서 상품 버전·금액을 받아 주문 본문을 만들고, 사전 주문 한 건의 `PAID` 반영을 확인한 뒤에만 k6를 시작합니다. 명시적인 접수 gate 거절은 30초 간격으로 두 번 더 확인하고 다른 변경 요청은 자동 재시도하지 않습니다.

로그인·준비·사전 주문까지만 검증하려면 `--prepare-only`를 사용합니다. 이때 새 상품과 재고는 유지하며 200건/초 주입은 하지 않습니다. 이후 일반 실행은 별도 상품·실행 ID를 새로 만듭니다. 준비가 성공해도 부하 시험을 통과했다는 의미는 아닙니다.

### 결과와 종료

결과는 Git에서 제외한 `tests/load/work/orders-<실행 ID>/`에 남습니다.

| 파일 | 내용 |
| --- | --- |
| `plan.json`, `order.json` | 대상·실행 ID·시험 상품·준비 재고·사전 주문과 실제 견적을 반영한 주문 본문. 토큰 없음 |
| `requests.jsonl` | 전송 전 `started`·응답 후 `completed` 행을 반복 번호로 연결. 요청 시각·HTTP 상태·주문 ID·오류 코드·지연. 멱등키는 `<runId>-load-<iteration>`으로 복원 |
| `k6-summary.json` | 실제 요청 수, p95/p99·오류율·`dropped_iterations`와 임계값 결과 |
| `order-states.json` | 사전 주문을 뺀 상품별 주문 상태·누락된 접수 주문·201 원장이 없는 주문과 결제 시각별 건수 |
| `summary.json` | 입력·VU 조건·측정 시작/끝·결과·준비/수집/정리 실패 |

`summary.json`의 `passed=true`는 **예정 요청 전부 시작·201·서로 다른 주문 ID·미시작 반복 0·조회된 주문 전부 PAID**가 함께 확인됐다는 뜻입니다. 배출 대기 기준은 기본 300초이며 `--drain-seconds`로 바꿉니다. 진행 중 조회 한 건의 timeout(10초)만큼 더 걸릴 수 있습니다. 목표 주입을 놓치거나 관측이 실패하면 실패로 끝내고 원장과 거래를 보존합니다. `completed`가 없는 `started`와 요청 timeout은 접수되지 않았다는 증거가 아니므로, 미확정 거래를 다음 시험으로 덮거나 DB 초기화로 없애지 않습니다.

Grafana의 **Financial processing**, Zipkin, Kafka UI에서 같은 측정 구간의 단계·SQL·PG 호출·대기열·lag를 확인합니다. `paymentConfirmedAtCounts`는 Commerce가 돌려준 결제 확정시각의 집계이며 Payment DB의 직접 측정과 구분합니다. 단계 실행 횟수·HTTP 201을 결제 확정 TPS로 해석하지 않습니다. **Settlement·대역 원장·금액 대조와 15분 지속 성능 판정은 이 도구의 성공 판정에 포함하지 않습니다.** [정산 대조](../../docs/development.md#정산-대조-실행과-재개)와 시험 원장의 주문 ID를 별도로 대조합니다.

일반 실행이 끝나면 자체 k6 컨테이너·발급 세션을 정리하고 새 시험 상품을 판매 중지합니다. 주문·재고 이력·시험 결과는 삭제하지 않습니다. 중단·정리 실패는 요약에 남으며 다음 시험 전에 미확정 거래를 확인합니다. 로컬 도구가 EC2를 종료하지는 않습니다. 공유 환경의 적체 배출·대조와 팀 사용 여부를 확인한 뒤 담당자가 EC2를 중지합니다.

준비·실패·결과 판정 회귀 검사는 `python3 -B -m unittest discover -s tests/load -p 'test_order_load.py'`로 실행합니다. API 계약 대역 검사는 실제 배포 환경의 준비·부하 실행을 대신하지 않습니다.

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
- 결제 요청 처리 슬롯·승인 슬롯·DB 연결 풀은 `LOAD_PAYMENT_REQUEST_CONCURRENCY`(기본 3)·`LOAD_PAYMENT_APPROVAL_CONCURRENCY`(기본 6)·`LOAD_PAYMENT_DB_POOL_SIZE`(기본 20)로 바꿉니다. `enable`·`scenario`가 payment-worker를 다시 만들 때도 같은 값을 지정해야 합니다. `ramp --kill-after`의 재기동은 죽이기 전 Worker의 실제 값을 그대로 넘기므로 다시 지정하지 않아도 되며, 그 값을 `summary.json`의 `fault.workerSettingsBeforeKill`에 남깁니다. `ramp`는 실행 중인 Worker의 실제 값을 `summary.json`의 `workerSettings`에 남기고, Worker를 읽을 수 없으면 `null`로 남깁니다. 이 기록은 `ramp`만 하며 `scenario` 결과에는 남지 않습니다.
- `prepare`는 시험 스택의 finance-mock DB에 `load-pg-*` 시나리오를 직접 넣습니다.
- 새 마일리지·간편결제 시험 지갑의 초기 잔액은 100억 원이며, `prepare`를 다시 실행해도 기존 잔액은 보존합니다. `reconcile`은 함께 생성한 간편결제 지갑의 저장된 초기 잔액을 기준으로 두 지갑을 대조합니다.
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

`reconcile`은 현재 스택에서 넣은 요청 원장을 기준으로 다음을 확인하고, 하나라도 어긋나면 실패로 끝납니다. `--runs`로 일부 실행만 대조하면 같은 스택의 다른 실행에서 생긴 결제는 그 실행을 대조할 때 확인하고, 어느 실행의 원장에도 없는 결제만 문제로 셉니다. 원장은 스택의 모든 실행을 함께 읽으므로 두 실행에 같은 주문 ID가 있으면 대조를 거절합니다. 마일리지·간편결제 지갑은 현재 `LOAD_NAMESPACE`의 것만 확인하며, 같은 번호를 쓴 다른 실행의 차감도 잔액 계산에 포함합니다. 따라서 반복을 마친 뒤에는 실행 번호마다 그 번호로 `--runs` 없이 한 번씩 대조해, 빼 두었던 다른 실행의 결제와 각 번호의 지갑을 모두 확인합니다.

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

## 선착순 이벤트 집중 부하

[선착순 집중 부하](../../docs/design.md#선착순-이벤트-집중-부하)의 A단계로, Gateway·Commerce의 주문 접수와 이벤트 예약 경합만 측정합니다. [event_load.py](event_load.py)가 새 Compose 프로젝트를 만들고 끝나면 소유 자원만 지웁니다. 신규 접수 gate는 실제 관측을 그대로 쓰며, Payment·Settlement 소비를 꺼 두므로 주문은 `PAYMENT_PENDING`에서 예약을 잡은 채 남습니다. 결제 확정·환불 복원·적체 배출을 포함한 B단계는 이 시험이 대신하지 않습니다.

```bash
./gradlew assemble
python3 -B tests/load/event_load.py --buyers 6000 --peak-rate 600 --tail-rate 100 --tail-seconds 25   # 축소 단계
python3 -B tests/load/event_load.py                                                                    # 60,000명, 첫 5초 6,000/s
python3 -B tests/load/event_load.py --warmup-seconds 30 --warmup-rate 50                               # 이벤트 공개 전 예열
```

- 구매자·판매자·상품은 SQL, 세션은 Redis에 직접 넣고, 주소는 주소 ID로 암호화되므로 [event_flash.js](event_flash.js)의 `seed`가 실제 API로 등록합니다.
- `--warmup-seconds`를 주면 이벤트 공개 전에 `--warmup-rate`(기본 50)/s로 일반 상품을 주문해 같은 주문 경로의 JIT·연결 풀을 데웁니다. 예열 구매자는 초당 주문 수만큼 따로 만들어 집중 구간 구매자와 겹치지 않으며, 예열 결과는 `summary.json`의 `warmup`에, 예열 주문은 일반 상품 재고 보존 검사에 함께 들어갑니다. 기본값 0은 예열하지 않습니다.
- 이벤트는 이미 시작한 기간으로 공개해 바로 `OPEN`으로 만듭니다. 구매자마다 주문을 한 번 보내며 10명 중 1명은 일반 상품을 주문합니다.
- k6는 결과를 `created`·`sold_out`(`INVENTORY_INSUFFICIENT`)·`admission_closed`(`EVENT_ADMISSION_CLOSED`)·`capacity`(접수 gate 503)·`conflict`·`client_error`·`server_error`·`timeout`으로 나눕니다. 의도한 매진 거절은 결함으로 세지 않습니다.
- 시험 중 1초마다 Commerce DB의 잠금 대기·활성 세션 수와 이벤트의 예약+판매 수를 표본 추출해, 수량이 처음 다 찬 시각(`soldOutAtSeconds`)을 남깁니다.
- 응답 시간은 이벤트·일반 상품과 결과별로 나눠 기록합니다. 생성은 매진 전, 이벤트 마감 거절은 대부분 매진 뒤 구간이라 두 구간을 따로 비교할 수 있습니다.
- 끝나면 초과 판매·구매자 중복 참여 0, 참여 수=이벤트 예약 수=이벤트 주문 수, 두 상품의 재고 합 보존, 게이지 조회를 확인하고 하나라도 어긋나면 실패로 끝납니다. 결과는 `tests/load/work/event-<시각>/`에 남습니다.

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

## 업무 완료·전구간 지연의 전후 비교

Grafana `commerce-settle / 업무 완료·전구간 성능 비교`는 완료 TPS, 주문 결제 접수→정산 최초 반영 지연, HTTP 서버 p95/p99를 같은 60/300/900초 창으로 비교합니다. 기존 금융 처리 대시보드의 비동기·SQL·배치 구간과 함께 봅니다. 승인 확정 TPS는 이후 취소돼도 남는 `confirmed_at` 이력이며, 환불은 외부 환불·마일리지 복원·원장 상쇄·주문 복구 확인까지 끝나야 완료입니다. 정산 최초 반영은 판매대금 지급 완료와 다릅니다.

기준 구간 offset은 시각을 겹쳐 보는 기능입니다. 개선의 근거는 같은 초기 데이터·요청 혼합·도착률·장애 시나리오·발생기 위치·CPU/메모리·runtime·warm-up 조건으로 반복 실행해 남깁니다. 코드 revision과 조정한 동시성 등의 `variant`는 결과에 따로 기록합니다. 원래 Payment 직접 주입 도구는 주문 API와 Settlement 반영을 실행하지 않으므로 그 결과를 전체 주문 왕복 성능으로 바꾸어 부르지 않습니다.

`performance.py capture`는 이미 실행한 부하의 끝 시각에 Prometheus의 같은 창을 읽는 도구입니다. 부하를 주입하거나 데이터를 초기화하지 않습니다. DB는 주기 수집하므로 각 업무의 실제 `dbWindowStart/End`와 관측 지연(최대 45초)을 결과에 남기고, HTTP 창은 지정한 조회 종료 시각을 기준으로 합니다. 일정 부하가 이 전체 구간과 warm-up 이후에도 유지되도록 실행하며, 경계가 다른 급격한 ramp 구간을 같은 창으로 해석하지 않습니다. 전체 주문 왕복 부하는 Gateway 주문 API를 거쳐 실제 정산 반영까지 진행하고, warm-up을 제외한 창 끝에서 수집합니다. 격리된 시험의 초기 데이터와 실제 사용 이미지·자원·발생기 결과는 실행자가 확인합니다. 클라이언트 타임아웃·연결 실패·`dropped_iterations`는 발생기 원문도 함께 보존합니다.

측정마다 저장소 밖 또는 기존 ignored `tests/load/work/<실행>/`에 metadata JSON을 만듭니다. 다음은 필드 형식이며 자리표시는 실제 관측값으로 채웁니다.

```json
{
  "revision": "<40자리 실제 소스 SHA>",
  "imageDigests": {
    "gateway": "sha256:<실제 digest>", "commerce": "sha256:<실제 digest>",
    "payment": "sha256:<실제 digest>", "settlement": "sha256:<실제 digest>",
    "finance-mock": "sha256:<실제 digest>"
  },
  "variant": {"approvalConcurrency": 6},
  "conditions": {
    "workload": "order-approval-refund",
    "datasetSha256": "<초기 fixture SHA256>",
    "arrivalRate": 100,
    "mix": ["card"],
    "scenario": "normal",
    "generator": "<발생기 위치와 버전>",
    "resources": {"appCpu": 2, "appMemoryMiB": 4096},
    "runtime": {"jdk": "<실제 JDK>", "postgres": "<실제 버전>"},
    "warmupSeconds": 60
  },
  "operations": ["payment.confirmed", "refund.completed", "order.settlement.applied", "payment.settlement.applied"],
  "http": {"application": "gateway", "method": "POST", "uri": "<실제 metric의 URI template>"}
}
```

```bash
python3 -B tests/load/performance.py capture --prometheus http://127.0.0.1:9090   --metadata tests/load/work/before/metadata.json --window 900   --at <측정 종료 epoch초> --output tests/load/work/before/performance.json
python3 -B tests/load/performance.py compare   tests/load/work/before/performance.json tests/load/work/after/performance.json
python3 -B -m unittest discover -s tests/load -p test_performance.py
```

인증된 HTTPS Prometheus에는 `PERFORMANCE_AUTH_USER`·`PERFORMANCE_AUTH_PASSWORD` 환경 변수를 사용합니다. 자격은 결과에 기록하지 않으며 redirect를 따르지 않습니다. 결과 파일은 덮어쓰지 않습니다. 수집 실패·45초 초과 표본·미래 표본·중복 source는 수집을 실패시킵니다. 완료 수는 있지만 시작 시각이 없거나 역전된 지연은 별도 건수로 남깁니다. 비교는 조건이 다르면 거부하며, 누락 지연·무표본·1만 건 미만 p99는 `unavailable`와 종료 코드 2로 남깁니다. TPS 증가나 지연 감소를 관측하지 않은 상태에서 개선됐다고 판정하지 않습니다. HTTP는 서버 분포이고 업무 지연 bucket은 DB의 rolling-window Gauge이므로 `rate()`·`increase()`를 다시 적용하지 않습니다.

## 병렬도 단일 변수 시험 절차

[ADR 0015](../../docs/decisions/0015-parallelism-decision-and-load-scope.md)의 판정 순서를 따릅니다. 값과 반복 횟수는 시험 전에 고정하고, 결과를 본 뒤 바꾸지 않습니다.

- **실행 번호**: 실험마다 `LOAD_NAMESPACE`(소문자·숫자 1~12자)를 지정합니다. 상품 ID, 구매자 풀(200명), EASY_PAY 지갑 계정명이 번호별로 나뉩니다. 비우면 기존 ID를 씁니다. `prepare`·`ramp`·`reconcile`은 같은 번호로 실행합니다.
- **고정 값**: payment Outbox `PAYMENT_OUTBOX_MAX_IN_FLIGHT` 기본 32, 승인 `LOAD_PAYMENT_APPROVAL_CONCURRENCY` 6, 결제 요청 `LOAD_PAYMENT_REQUEST_CONCURRENCY` 3, Kafka 리스너 3(파티션 3이 상한).
- **변수 값**: Outbox 16·64, 승인 12·24, 결제 요청 6. 한 번에 한 변수만 바꿉니다. 병목 판정으로 대상이 아니라고 확인된 구간은 시험하지 않습니다.
- **부하**: 이벤트 시나리오 300건/초 × 30초(`--stages 300:30`)를 유지합니다.
- **도구**: 기준선과 변수 실험 모두 `payment_load.py`를 씁니다. 공개 경로 확인은 `order_load.py`로 기준선에 한 번 더 실행합니다.
- **반복**: 기준선 3회, 변수 값마다 3회입니다. 쌍이 모자라 ADR 0005 판정이 불가하면 판정을 보류하고 그대로 기록합니다.
- **자원 기록**: 두 EC2에서 시험과 같은 시간 동안 `python3 -B tests/load/resource_sample.py --out-dir tests/load/work/resources-<실행 ID> --interval 5 --seconds <시간>`을 실행합니다. 스크립트는 [#310](https://github.com/500-internal-server-error-team/commerce-settle/issues/310)에서 추가합니다.
- **판정**: ADR 0015 §6 기준입니다. 거절은 성공과 분리해 집계합니다. 주입률이 목표에 못 미친 회차(`dropped_iterations`, 발생기 자원으로 확인)는 서버 판정에서 뺍니다.

```bash
LOAD_NAMESPACE=r1 python3 -B tests/load/payment_load.py ramp --run r1-baseline-1   --stages 300:30 --mix card,mixed,easy,mileage --drain 300
LOAD_NAMESPACE=r1 python3 -B tests/load/payment_load.py reconcile --runs r1-baseline-1
```
