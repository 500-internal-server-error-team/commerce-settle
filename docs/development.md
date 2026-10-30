# 로컬 실행과 검증

Java 21, Docker·Compose v2, Python 3, Git이 필요합니다. Gradle은 Wrapper가 내려받습니다. Apple Silicon과 Linux에서 같은 Compose 구성을 사용합니다.

16GB Mac에서 전체 관측 도구와 k6까지 실행할 때는 Docker 메모리 8GiB를 출발점으로 두고 Mac의 메모리 압박·swap과 컨테이너 OOM 여부를 함께 확인합니다. 호스트 RAM과 Docker VM 할당량은 다릅니다. 이 자원 배정이 1천 TPS를 보장하지는 않습니다.

## 처음 실행

```bash
git clone https://github.com/500-internal-server-error-team/commerce-settle.git
cd commerce-settle
git switch develop
python3 scripts/init_env.py
./gradlew --continue clean build
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
```

`java -version`이 21인지 먼저 확인합니다. 다른 버전이 기본값인 Mac에서는 설치된 Java 21 경로를 사용합니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

`init_env.py`는 필요한 비밀번호를 무작위로 생성해 `.env`에 저장하고 기존의 비어 있지 않은 값은 보존합니다. 새 비밀번호 변수가 추가돼 Compose가 실행을 거절하면 다시 실행해 빈 값만 채웁니다. 파일을 공유·커밋하지 않습니다. 포트 충돌이 나면 `.env`의 포트 값을 변경합니다. Dockerfile은 미리 빌드한 `app.jar`를 사용하므로 최초 실행과 Java 변경 후에는 Gradle 빌드가 필요합니다.

줄바꿈은 `.gitattributes`로 LF(`*.bat`만 CRLF)에 고정합니다. 이 파일이 추가되기 전에 Windows의 `core.autocrlf=true`로 받은 작업 트리에는 CRLF 파일이 남아 PostgreSQL 초기화 스크립트가 `set: pipefail: invalid option`으로 실패할 수 있습니다. 진행 중인 변경을 커밋하거나 stash한 뒤 한 번 다시 체크아웃합니다. 이 명령은 커밋하지 않은 변경을 지웁니다.

```bash
git rm -r --cached -q .
git reset --hard
```

초기화 스크립트는 PostgreSQL 이미지에 복사되고, 실패한 볼륨은 다시 초기화되지 않습니다. [초기화 명령](#종료재실행초기화)으로 볼륨을 삭제하고 이미지를 다시 빌드한 뒤 시작합니다.

```bash
docker compose build postgres
docker compose up -d --wait --wait-timeout 300
```

기존 환경의 `COMPOSE_PROJECT_NAME`도 보존하므로, 기존 컨테이너·볼륨을 계속 사용하려면 그 값을 유지합니다. 신규 환경의 기본 프로젝트명은 `commerce-settle`입니다.

커머스 앱·DB·계정·스키마는 `commerce`, DB 비밀번호 변수는 `COMMERCE_DB_PASSWORD`입니다. 명세 경로는 `/v3/api-docs/commerce`, 서비스 URL 재정의 변수는 `COMMERCE_URL`입니다. 판매자 업무 API는 `/api/sellers`를 사용합니다.

기존 Docker의 `buildx/activity/...: permission denied` 오류가 발생하면 전역 파일의 소유권을 바꾸는 대신 작업용 설정 경로를 지정할 수 있습니다. 현재 셸에서만 적용되며 사용이 끝나면 `unset BUILDX_CONFIG`로 해제합니다.

```bash
mkdir -p work/buildx
export BUILDX_CONFIG="$PWD/work/buildx"
```

## 주문 API stub

주문 생성·본인 목록·단건 조회를 학습할 때만 `APP_ORDER_STUB_ENABLED=true`로 켭니다. 기본값은 false입니다. 상품·주문·멱등 결과와 주소 사본은 실제 DB와 별개인 메모리 데이터이며 재시작하면 초기화됩니다. 실제 주문 저장·재고/마일리지 예약·결제·Inbox/Outbox·자동 상태 전이는 구현하지 않습니다. 쿠폰·이벤트 연동은 TODO이고, 생성 주문과 초기 샘플 모두 할인액은 0원·eventId는 null입니다.

이 stub의 `OrderStubCreate` 입력은 `productId`, `quantity`, `mileageAmount`, `expectedProductVersion`, `expectedTotalAmount`, `externalMethod`, `methodAccountRef`의 7개 키입니다. 두 외부 수단 값도 키는 필수이며 전액 마일리지일 때 null로 보냅니다. 아직 지원하지 않는 `buyerCouponId`, `eventId`, `expectedEventVersion`과 수동 `addressId`를 보내면 400으로 거절합니다. 정식 [OrderCreate 계약](openapi.json)은 11개 필드로 유지하며 `x-implemented: false`입니다. 켠 인스턴스의 `/v3/api-docs`에서 개발용 입력을 확인합니다.

신규 생성은 `OrderInventory.lockInventoryAndReadProduct(productId)`로 상품을 받은 뒤 기존 상품 도메인 규칙으로 판매 가능 여부를 확인하고, 반환된 가격·버전을 검증해 주문에 값을 복사합니다. 멱등 재요청은 상품을 다시 읽지 않고 최초 주문 사본을 반환합니다. `OrderStubConfiguration`은 같은 상품 샘플 목록으로 메모리 포트와 초기 주문을 조립하며, 초기 샘플 생성은 이 포트를 호출하지 않습니다. 실제 DB 구현이 지켜야 할 호출자 트랜잭션 참여·재고 행 우선 잠금 계약은 [포트](../commerce/src/main/java/com/commercesettle/commerce/application/port/OrderInventory.java)에 명시합니다. 현재 메모리 구현은 DB 트랜잭션이나 행 잠금·재고 예약을 수행하지 않습니다.

[처음 실행](#처음-실행)의 인프라가 준비된 환경에서 새 commerce jar와 이미지를 만든 뒤 로컬 전용 인스턴스를 엽니다.

```bash
./gradlew :commerce:bootJar
docker compose build commerce
docker compose run --rm --no-deps -p 127.0.0.1:18080:8080 -e APP_ORDER_STUB_ENABLED=true commerce
```

다른 터미널에서 호출합니다. 직접 전달하는 신원 헤더는 로컬 시험용입니다. 백엔드의 Gateway 호출 자격 검사는 아직 없어 운영 인증을 대신하지 못합니다.

```bash
curl http://localhost:18080/api/orders \
  -H 'X-Authenticated-Actor-Kind: BUYER' \
  -H 'X-Authenticated-Actor-Id: 00000000-0000-4000-8000-000000000001'

curl -i http://localhost:18080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-stub-create-001' \
  -H 'X-Authenticated-Actor-Kind: BUYER' \
  -H 'X-Authenticated-Actor-Id: 00000000-0000-4000-8000-000000000001' \
  -d '{"productId":"00000000-0000-4000-8000-000000000003","quantity":1,"mileageAmount":0,"expectedProductVersion":0,"expectedTotalAmount":23000,"externalMethod":"CREDIT_CARD","methodAccountRef":"stub-card"}'
```

`201` 응답의 `Location: /api/orders/{id}`를 같은 구매자로 조회하면 같은 주문이 나옵니다. 같은 멱등키·본문은 최초 결과를, 다른 본문은 `409 COMMON_IDEMPOTENCY_CONFLICT`를 반환합니다. 생성 주문은 `PAYMENT_PENDING`·`UNSHIPPED`로 유지됩니다. 서버 가격·버전과 예상 값을 비교하며 실제 잔액·재고는 검사하지 않습니다. 외부 결제액이 0원이면 수단 두 값은 모두 null이어야 합니다.

가상 주소 풀은 수령인·연락처·우편번호·주소가 모두 합성 데이터인 1,000개입니다. 주문 ID와 `APP_ORDER_STUB_SEED`로 하나를 골라 `OrderView.address`에 보관합니다. 재요청·조회에서 다시 선택하지 않으며 다른 주문은 같은 주소를 뽑을 수 있습니다. 연락처는 실제 통신번호가 아닌 `000-` 접두사, 주소는 `가상시 테스트구`를 사용합니다. 기존 28필드 HTTP 주문 응답에 주소를 추가하거나 금융 이벤트로 보내지는 않습니다. 실제 주소록·택배사·배송 추적 API는 제공하지 않습니다.

시작 시 구매자 `00000000-0000-4000-8000-000000000001`·`00000000-0000-4000-8000-000000000011`에 각각 10개 주문을 생성해 미결제·결제완료·발송·배송완료·구매확정을 보여줍니다. 그 외 구매자도 생성 요청을 보낼 수 있으며 새 주문은 본인에게만 보입니다. 상품 사본은 수납 상자(접미사 `000000000003`, 23,000원·판매자 `000000000002`), 키보드(`000000000023`, 39,000원·판매자 `000000000022`), 머그컵(`000000000033`, 10,000원·판매자 `000000000002`)입니다. 접미사 앞 UUID 구간은 모두 `00000000-0000-4000-8000-`이며 상품 버전은 0입니다.

seed 기본값은 67입니다. 같은 seed·Clock이면 초기 주문과 주소 사본이 같습니다. 배송 소요일은 [배송 정책](policies.md#배송환불-기한과-자동-구매확정)의 SHA-256 규칙으로 2·3영업일을 정하고 `weekdays-v1`을 고정합니다. 이미 만든 시각·주소·배송 이력은 조회마다 바뀌지 않습니다. 목록은 `createdAt DESC, id DESC`, page는 0부터·size는 1~100이며 타인 주문은 없는 주문과 같은 404입니다.

전용 HTTP 통합 테스트는 실제 HTTP 서버·Controller·서비스·JSON 검증·오류 응답을 연결하며 DB·Kafka·Redis를 사용하지 않습니다. 숫자·숫자 문자열의 결제수단 변환과 boolean의 문자열 변환을 거절하고, 실제 요청·응답을 정식 `docs/openapi.json`과 생성된 OpenAPI 3.1 스키마 양쪽으로 검증합니다. stub 입력은 정식 `OrderCreate`에서 미지원 4필드만 제외해 비교하며 원래 계약은 유지합니다. 생성 명세의 필드·필수 키·타입·null·허용값·범위·형식·페이지 항목 참조를 정식 계약과 대조하고, 필수 키 누락·잘못된 상태·배송일·금액과 페이지 내부 주문 변조가 거절되는지 검사합니다. 이 테스트가 Compose 전체 실행이나 금융 처리 완료를 증명하지는 않습니다.

```bash
./gradlew :commerce:test --tests '*OrderStubServiceTest' --tests '*OrderStubHttpIntegrationTest'
```

## 시간대

앱 로그·API·이벤트 출력과 업무 날짜는 [공통 시간 규약](../CONTRIBUTING.md#요청과-응답)의 KST를 따릅니다. API·이벤트 DTO는 `OffsetDateTime`으로 출력하며 내부 `Instant`·DB `timestamptz`·epoch 값은 유지합니다. 업무 API·이벤트 구현 시 이 변환 경계를 적용해야 합니다.

Compose와 Gradle `bootRun`은 JVM 시간대를 `Asia/Seoul`로 지정합니다. IDE나 jar 직접 실행에는 `-Duser.timezone=Asia/Seoul`을 VM 옵션으로 넣습니다. PostgreSQL의 `TimeZone`·`log_timezone`과 Grafana 대시보드도 명시적으로 KST를 사용합니다. 기존 DB 볼륨에서도 Compose 재생성으로 설정을 적용하며 데이터 이관은 하지 않습니다.

현재 콘솔 로그에는 `+09:00`을 포함합니다. 추후 내장 Logstash JSON을 활성화하면 JVM 시간대가 `@timestamp`에 적용됩니다. Docker·Prometheus·Zipkin 등 외부 도구의 원시 epoch·수집 시각은 각 형식을 유지하며 조회 화면에서 KST로 해석합니다.

## 접속

| 대상 | 기본 주소 | 실행 조건 |
| --- | --- | --- |
| Swagger UI | <http://localhost:8080/swagger-ui.html> | 기본 구성 |
| Commerce 명세 | <http://localhost:8080/v3/api-docs/commerce> | 기본 구성 |
| Payment 명세 | <http://localhost:8080/v3/api-docs/payment> | 기본 구성 |
| Settlement 명세 | <http://localhost:8080/v3/api-docs/settlement> | 기본 구성 |
| Zipkin | <http://localhost:9411> | 기본 구성, 메모리 저장소 |
| Prometheus | <http://localhost:9090> | `observability` |
| Grafana | <http://localhost:3000> | `observability`, 사용자 `admin`, 비밀번호 `.env`의 `GRAFANA_ADMIN_PASSWORD` |
| Kafka-UI | <http://localhost:8081> | `observability` |

```bash
docker compose --profile observability up -d --wait --wait-timeout 300
```

Grafana의 `commerce-settle / Runtime overview` 대시보드와 Prometheus 데이터 소스는 자동 등록됩니다. 수집 주기 때문에 첫 데이터가 표시되기까지 약 15초가 필요합니다. 이 구성은 로컬 전용이며 DB·Kafka·Redis·내부 앱·관리 포트는 호스트에 게시하지 않습니다. Zipkin은 기본 10% 샘플링이며 `.env`의 `TRACING_SAMPLING_PROBABILITY`로 바꿉니다. 모든 요청을 수집하면 부하·메모리 사용량이 달라집니다.

## k6 실행 확인

서비스를 기동한 뒤 기본 스크립트를 실행합니다. 기본값은 초당 10회·10초·20 VU 사전 할당이며 Commerce OpenAPI 조회만 수행합니다. VU는 동시 실행 여유이며 초당 요청 수와 다릅니다.

```bash
docker compose --profile tools run --rm --no-deps k6
```

다른 장비에서 k6를 실행할 때는 Docker Compose 대신 해당 장비의 k6로 스크립트를 실행하고 접근 가능한 테스트 서버 주소를 지정합니다.

```bash
k6 run -e BASE_URL=http://TEST_SERVER:8080 -e RATE=100 -e DURATION_SECONDS=30 tests/load/bootstrap.js
```

Compose는 루프백에만 게시하므로 다른 장비에서 바로 접근할 수 없습니다. 원격 시험은 인증·네트워크 접근을 별도로 설계한 테스트 환경에서 진행합니다.

`RATE`, `DURATION_SECONDS`, `PRE_ALLOCATED_VUS`, `MAX_VUS`는 양의 정수이며 VU 최대값은 사전 할당값 이상이어야 합니다. Compose에서는 `-e RATE=100 -e DURATION_SECONDS=30`처럼 `run` 옵션으로 덮어씁니다. checks·HTTP 실패·`dropped_iterations` 임계값을 확인합니다. 명세 조회 처리량은 결제 승인 완료 TPS가 아닙니다. 업무 시나리오·Synthetic Data·성능 목표 달성은 후속 검증입니다.

## 개발·검증

```bash
./gradlew spotlessApply
python3 scripts/test_init_env.py
./gradlew --continue build
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
```

운영·테스트 Java 코드의 포맷·Checkstyle, 단위·통합·ArchUnit 테스트와 패키징을 검사합니다. `spotlessApply`는 로컬 수정용이며 CI는 `spotlessCheck`로 위반을 차단합니다. 테스트 추가 기준은 [테스트 작성](../CONTRIBUTING.md#테스트-작성)을 따릅니다.

Python 테스트는 환경 초기화가 기존 설정을 보존하는지 확인합니다. smoke는 각 실행의 DB·Redis 연결·메트릭, Gateway 문서 라우팅·관리 경로 차단, DB 간 접근 거부, 앱별 Redis ACL(커머스의 인증 키 쓰기·스크립트 실행과 `SCRIPT`·`FUNCTION`·키 목록·DB 전환 거절, Gateway의 `EVALSHA_RO` 검증과 쓰기·`EVAL`·`SCRIPT` 거절, 나머지 앱의 인증 키 거절), Kafka·Redis 왕복과 Zipkin의 Gateway→Commerce 추적 전파를 검사합니다. 임시 Kafka 토픽·Redis 키는 삭제합니다. CI는 낮은 부하의 k6 실행도 확인합니다. 결제·SAGA의 정합성과 성능은 업무 시나리오로 검증합니다.

정적 검사만 실행할 때는 `./gradlew spotlessCheck checkstyleMain checkstyleTest`, 테스트만 실행할 때는 `./gradlew test`를 사용합니다. `build`는 이 검사들을 포함하므로 별도 실행이 필수는 아닙니다.

Payment API와 Worker는 같은 `payment` 모듈로 빌드합니다. Gradle 모듈별 명령은 `./gradlew :payment:build`처럼 실행합니다.

### Kafka 전달 기반

Commerce·Payment·Settlement는 자기 원본 토픽 `commerce-settle.<서비스>.v1`을 생성합니다. 소비자별 DLT는 `commerce-settle.<소비자>.v1.dlt`이며 소비자는 `commerce`, `commerce-notifications`, `payment`, `settlement`입니다. 모두 파티션 3개·복제 1개이고 원본은 24시간, DLT는 7일 보존합니다. DLT는 토픽만 준비하며 자동 격리·재처리는 아직 연결하지 않습니다. 기존 토픽 설정이 다르면 smoke가 실패하므로 실제 값을 대조해야 합니다.

현재 구현한 전달 기반은 Commerce의 `OrderPaymentRequested` Outbox 저장·발행과 Payment의 Inbox 영속 인계입니다. 업무 유즈케이스는 자기 트랜잭션에서 `OrderEventOutbox.appendPaymentRequested`를 호출해야 하며, 트랜잭션 밖 단독 저장은 거절됩니다. 실제 전송은 `OutboxRelay`가 저장된 topic·key·원문 bytes로 수행하며 직접 발행 경로는 두지 않습니다. 발행 경로의 선택 이유와 CDC 대안은 [Kafka 결정](decisions/0005-kafka-event-delivery-and-capacity.md#발행-경로-통일)을 따릅니다. 최초 직렬화한 bytes를 보존해 재발행하고, Kafka ACK 뒤에만 발행 위치와 완료 상태를 저장합니다. 주문 API·예약과 실제 결제 접수 로직은 아직 연결하지 않았습니다.

Payment의 `AcceptOrderPaymentRequestService`는 Inbox의 `WAITING`·`next_run_at`에 원본과 메시지 처리 대기 작업을 저장·커밋한 뒤 반환합니다. 같은 ID·동일 bytes는 원래 상태를 유지하고 다른 bytes는 `inbox_conflicts`에 격리합니다. 별도 `inbox_jobs`나 가짜 paymentId의 금융 Job을 만들지 않습니다. 결제·마일리지 예약·실제 `payment_jobs` 생성과 Inbox 완료를 처리할 후속 유즈케이스는 아직 없습니다. 수신 ACK는 이 대기 작업의 저장 완료만 뜻합니다.

`app.outbox.enabled`와 `app.kafka.order-payment-requested.enabled`는 기본 `false`입니다. 빈 개발 DB에 서비스별 초기 SQL을 적용하고 아래 신규 그룹 초기화를 마친 뒤 각각 활성화합니다. 수신 구현 Bean이 없으면 활성화한 Listener는 시작에 실패합니다. 테스트 대역을 운영 구현으로 등록하지 않습니다. 기존 개발 컨테이너에 초기 SQL을 재실행하거나 볼륨을 임의로 초기화하지 않습니다.

기술용 Outbox·Inbox 저장은 같은 DataSource의 JdbcTemplate로 bytea 원문·조건부 갱신·SKIP LOCKED를 다룹니다. 트랜잭션은 응용 서비스에서 시작하며 운영 앱의 JpaTransactionManager에 참여합니다. 별도 운영 트랜잭션 매니저나 공유 DB를 추가하지 않습니다. Commerce Outbox DB 시험은 HikariCP·JpaTransactionManager로 JDBC의 트랜잭션 참여와 롤백을 확인합니다. Payment Inbox 시험은 DataSourceTransactionManager를 사용하므로 Payment의 운영 JpaTransactionManager 참여까지 검증한 것은 아닙니다.

Outbox의 소진 전환과 실행 선점은 각각 요청한 배치 크기 안에서 잠긴 행을 건너뜁니다. 잠긴 선행 이벤트가 있어도 같은 주문의 후행 이벤트는 추월하지 않습니다. Outbox 저장·선점·결과 갱신은 해당 로컬 트랜잭션에 잠금 대기 1초·SQL 실행 5초 상한을 적용하며 호출자가 정한 더 짧은 상한은 유지합니다. 상한은 트랜잭션 종료 때 복원됩니다. 잠금 시간 초과는 로컬 저장을 롤백하며 금융 실패나 예약 해제를 뜻하지 않습니다. 결과 갱신은 먼저 행 잠금을 얻고 다음 SQL에서 임대·버전을 검사하여 대기 중 만료된 임대도 거절합니다. 발행 결과 저장이 실패하면 원본의 RUNNING 임대를 만료 뒤 회수합니다.

Commerce Outbox는 커밋 후 `OutboxAppended` 신호로 Relay를 깨우고 결과 반영 후 슬롯이 반환될 때 다음 작업을 이어 보냅니다. 롤백은 깨움을 만들지 않습니다. 시작 때 한 번 조회하고, 신호 누락·재시도·만료 임대는 기본 1초 복구 조회로 처리합니다. 미완료 전송 최대 8건·한 번의 조회 최대 100건으로 시작하며 빈 슬롯만 선점합니다. 임대 기본값 240초는 Kafka 기본 metadata/buffer 대기 최대 60초와 전달 제한 120초보다 길게 잡은 시작값입니다. 생산자 설정을 바꾸면 임대도 그 두 제한의 합보다 길어야 하며 잘못된 설정은 시작 때 거절합니다. 재시도는 1초부터 최대 60초 간격·10회 상한이며 만료 임대 회수도 시도 횟수에 포함합니다. 소진 시 원본을 REVIEW_REQUIRED로 보존하고 선행 미발행 건을 후행이 추월하지 않게 합니다. 전송과 ACK 뒤 DB 반영은 전용 bounded executor에서 수행하고 DB 반영이 끝날 때까지 슬롯을 유지합니다. 전송 실행기의 기본 thread·queue 상한은 각각 8개이고, 깨움 조정 실행기는 thread·queue 각각 1개입니다. 커밋 스레드와 Kafka 콜백은 작업 제출만 하며, 제출이 거절돼도 그 스레드에서 대신 실행하지 않습니다. 조정 실행기 제출 거절은 복구 조회에서 재시도하며 종료 때 실행기는 최대 30초 대기합니다. 종료 중 제출이 거절되면 추가 선점을 멈추고 미반영 원본은 임대 만료 후 회수합니다. 운영 재처리 API는 아직 없습니다.

Commerce Outbox와 Payment의 결제·Inbox 테이블은 각각 [서비스별 초기 SQL](migrations.md)에서 함께 생성합니다. Outbox·Inbox 저장의 잠금 대기는 최대 1초, SQL 한 문장은 최대 5초로 제한하고 호출자의 더 짧은 상한은 유지합니다. 시간 초과 시 트랜잭션을 롤백하고 수신 offset을 ACK하지 않습니다.

Kafka template·listener의 observation을 활성화해 실제 발행 시도의 traceparent를 소비자 처리로 전파합니다. Outbox는 재시작·재전달 가능한 비동기 경계이므로 발행 시도마다 새 trace를 시작하고 불변 eventId로 연결합니다. 원래 HTTP trace를 DB에 보존하거나 금융 Job까지 연결하는 구현은 포함하지 않습니다. Kafka 통합 시험은 원본 헤더와 소비자 span의 traceId·parentId, 재전달 뒤에도 같은 원본 parent를 유지하는지 확인합니다.

Consumer는 `auto.offset.reset=none`이므로 새 그룹에 offset이 없거나 보존 범위를 벗어나면 자동으로 처음/끝으로 이동하지 않습니다. **첫 생산자를 열기 전에**, 새 구독 토픽의 모든 파티션이 비어 있고 기존 그룹 offset도 없음을 확인한 경우에만 초기화합니다. 현재 Payment 수신 경계의 예시는 다음과 같습니다.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic commerce-settle.commerce.v1 --time -2
docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic commerce-settle.commerce.v1 --time -1
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group payment.v1
# 위 조회에서 모든 파티션의 시작·끝이 0이고 기존 그룹 offset이 없을 때만 실행
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --reset-offsets --group payment.v1 --topic commerce-settle.commerce.v1 --to-earliest --execute
```

초기화 후 영속 인계 구현과 수신 경계를 활성화하고 생산자를 엽니다. 이미 메시지가 있거나 기존 offset이 있는 환경에는 위 초기화 명령을 적용하지 않습니다. 보존 이탈은 DB 원본과 대조해 복구하며 임의 offset 변경으로 정상 소비처럼 처리하지 않습니다. 다른 그룹·구독 토픽을 추가할 때도 같은 준비가 필요합니다.

수신은 메시지 종류·버전·key·필수 필드·타입을 검증하고 영속 인계가 성공한 뒤에만 ack합니다. 현재 지원하지 않는 이벤트도 자동으로 버리지 않습니다. 처리 예외에는 해당 Listener 컨테이너를 비정상 중단하며, 자동 재시도 소진 후 건너뛰는 기본 동작을 사용하지 않습니다. 원인과 미확정 저장 결과를 확인한 뒤 소비자를 재기동하면 마지막 커밋 위치에서 재전달됩니다. 격리 DB·DLT 실행 경로가 연결되기 전까지는 실패를 중단 상태로 유지합니다.

`./gradlew :commerce:test :payment:test`는 Docker에서 전용 `apache/kafka:4.2.2` 컨테이너를 만들어 Outbox 원문 발행·위치 저장·ACK 후 저장 실패의 재발행과 실제 수신, 초기 offset 부재, 인계 실패 후 offset 보존과 소비자 재기동의 재전달을 검증합니다. Docker가 없으면 이 통합 시험은 실패하며 생략하지 않습니다. 시험 컨테이너와 데이터는 종료 때 제거하고 개발 Kafka·DB·볼륨을 사용하지 않습니다. 기본 `build`·CI에도 포함되며, smoke는 기동된 실제 7개 토픽의 설정을 추가로 검사합니다. Payment Kafka 수신 시험의 인계 구현은 메모리 대역입니다. Commerce Outbox의 실제 DB·Kafka 전달 시험도 주문·외부 결제 전체의 금융 정합성·업무 멱등성·처리량 검증을 대신하지 않습니다.

같은 기본 테스트 경로의 PostgreSQL `18.3` 전용 컨테이너 시험은 같은 스키마 정의로 DB를 생성하고 Outbox와 호출자 저장의 롤백·동시 중복·원문 보존·선행 순서·임대 회수·늦은 응답 차단, Inbox 대기 작업의 커밋 후 ACK·롤백·동시 재전달·본문 충돌 격리를 다룹니다. Kafka 경계 시험과 DB 저장 시험은 각각의 보장을 확인하며, 실제 주문부터 외부 결제까지의 통합·금융 정합성·처리량 검증을 대신하지 않습니다.

### Gateway 라우팅과 오류

Gateway의 서비스 주소는 기존 `COMMERCE_URL`·`PAYMENT_URL`·`SETTLEMENT_URL`로 재정의합니다. 업무 경로를 별도로 나열하지 않고 빌드 때 `docs/openapi.json`을 `contracts/openapi.json` 리소스로 포함합니다. 명세의 `/api/` 작업을 추가·변경하면 Gateway도 다시 빌드해야 합니다. `/internal`·`/mock`·관리 경로는 대상에서 제외합니다. 같은 명세의 `x-actor`로 보호 작업의 세션을 검증하며, 백엔드의 호출 자격 확인 전까지는 로컬 개발 전용입니다.

`./gradlew :gateway:build`의 HTTP 통합 시험은 임시 로컬 HTTP 서버 세 개로 외부 API의 소유 서비스·메서드·경로, 내부 경로 차단, 본문·쿼리·멱등 키 전달, 명세·Swagger UI, 오류 계약과 연결 단절 시 재전송하지 않는 동작을 확인합니다. 세션 판정을 대역으로 바꿔 인증 헤더 제거·신원 전달과 401·403·503 거절도 확인합니다. 판정 스크립트는 `SessionVerifierIntegrationTest`가 Testcontainers Redis와 Gateway ACL 사용자로 검증합니다. 업무 컨트롤러·실제 백엔드의 업무 성공을 검증하는 시험은 아닙니다.

Gateway 자체 오류는 `application/problem+json`과 실제 추적의 `traceId`로 응답하며 쿼리·원래 예외 메시지·내부 주소를 본문에 넣지 않습니다. 미등록 경로·메서드는 `404 COMMON_RESOURCE_NOT_FOUND`, 상류 연결 실패·시간 초과는 `503 COMMON_TEMPORARILY_UNAVAILABLE`, 예상 밖 오류는 `500 COMMON_INTERNAL_ERROR`입니다. 전송 계층이 요청을 자동 재전송하지 않으므로 503·응답 유실 뒤에는 원업무 키로 결과를 확인합니다. 백엔드가 보낸 상태·오류 본문·추적 ID는 그대로 전달합니다.

컨테이너 상태·로그는 아래처럼 확인합니다.

```bash
docker compose ps -a
docker compose logs --tail=100 payment-api payment-worker
docker compose exec -T payment-api curl --fail http://localhost:9091/actuator/health
```

## Redis 세션 검증과 복구

아래는 [Redis 세션 정책](policies.md#인증공통-api-기준)을 구현할 때의 검증·운영 기준입니다. 현재는 앱별 Redis ACL, Gateway의 세션 검증, 커머스의 로그인 세션 발급·로그아웃·로그인/복구 요청 제한, 비밀번호 재설정의 가드 차단·확정이 있습니다. `init_env.py`가 인증 세대 `AUTH_SESSION_EPOCH`, 제한 키용 `AUTH_RATE_LIMIT_HMAC_KEY`, 복구 토큰 전달 암호문용 `AUTH_RESET_DELIVERY_KEY`를 생성합니다. 세대를 바꾸면 기존 세션이 모두 무효가 되고, HMAC 키를 바꾸면 진행 중인 제한 시간창이 초기화되며, 전달 키를 바꾸면 아직 전달하지 않은 복구 토큰을 열 수 없습니다. 미확정 인증 변경의 재조정, 실행 식별자 설정과 차단·재개 기능은 아직 없습니다. smoke는 판매자 가입·로그인·Gateway 경유 조회·로그아웃 뒤 401, 계정 존재 여부와 관계없이 같은 복구 요청 응답, 쓸 수 없는 복구 토큰의 401과, Redis에 직접 넣은 세션의 역할 403·변경 중 가드 503을 확인합니다. 폐기·복구 흐름의 검증 완료로 간주하지 않습니다.

- 고정 30분 직전/정각/직후와 반복 조회를 시험해 expiresAt·TTL이 연장되지 않는지 확인합니다. Redis 쓰기 거절·시간 초과에는 토큰을 반환하지 않고 보호 요청은 503이어야 합니다.
- 로그아웃·계정 정지·비밀번호 재설정과 동시 로그인을 경합시킵니다. Redis 가드 선차단 뒤 DB 롤백·커밋 결과 유실·Redis 후속 반영 실패를 주입하고, 차단 유지·이전 버전 거절·DB 확정 결과에 따른 재조정을 확인합니다.
- 확정 정지/삭제·발급 중 버전 변경은 401, 변경 중/누락된 가드·복구 차단은 503인지 검사합니다. 폐기 전 인증을 마친 요청과 폐기 뒤 검증하는 요청을 구분하고, 접수한 금융 작업이 세션 만료로 유실되지 않는지 확인합니다.
- 비밀번호 DB 커밋 뒤 Redis 반영 실패·응답 유실을 재현해 미확정 안내, 복구 후 새 비밀번호 로그인, 소비한 토큰의 재사용 거절과 비밀번호 중복 변경 방지를 검사합니다. 계정 상태 API는 현재 없으므로 시험용 응용 유즈케이스로 폐기 절차를 호출합니다.
- 위조된 신원 헤더·Gateway 우회·다른 서비스/운영자 자격·타 계정 주문을 거절하고, Gateway의 Redis 자격으로 인증 키를 생성·수정·삭제할 수 없는지 확인합니다. Gateway 자격의 `EVAL`·`EVALSHA`·`SCRIPT LOAD`가 거절되는지, `SCRIPT FLUSH` 뒤 `EVAL_RO` 재적재로 같은 판정을 내리는지도 검사합니다.
- [로그인·복구 요청 제한](policies.md#로그인복구-요청-제한)은 구현 PR에서 확정한 설정으로 상한 직전/정확한 상한/초과·시간창 만료·다중 인스턴스 동시 요청을 검증합니다. 존재/미존재 계정과 구매자/판매자·로그인/복구의 분리, 성공 뒤에도 유지되는 카운터, 429의 Retry-After와 기존 복구 토큰 보존을 확인합니다. 제한 저장소 오류·쓰기 거절에는 503이며 비밀번호 검사·토큰 발급/폐기·전달이 실행되지 않아야 합니다. 필수 제한 설정·HMAC 키 누락과 잘못된 값으로 인증 진입점이 열리지 않는지도 검사합니다.
- PUBLIC 경로의 메서드를 바꾼 요청, 보호 작업의 누락된 신원 헤더, 로그아웃의 누락된 세션 해시를 거절하는지 확인합니다. 공개 상품 조회 경로를 근거로 판매자 상품 변경까지 허용하지 않습니다.

Redis 인증 저장소 장애·재시작·재연결 뒤에는 자동으로 보호 요청을 다시 허용하지 않습니다. 복구 절차는 다음과 같습니다.

1. Gateway·커머스의 사용자 인증 게이트를 닫고 모든 인스턴스의 신규 보호 요청·세션 발급·비밀번호 재설정·인증 관련 계정 변경을 503으로 차단합니다. 기존 금융 작업과 서비스·운영자 자격은 별도로 처리합니다. 이미 시작한 이전 세대의 인증 변경은 DB 트랜잭션 종료·결과 확인까지 기다리거나 DB 인증 버전의 조건부 증가로 이전 버전 쓰기를 차단합니다. 종료 또는 차단을 확인하기 전에는 새 세대 가드를 구성하거나 인증을 재개하지 않습니다.
2. 계정 변경의 미확정 결과를 PostgreSQL에서 확인하고 새 인증 세대 UUID와 현재 Redis 실행 식별자를 Gateway·커머스 전체에 같은 값으로 적용합니다. 기존 세대로 동작하는 인스턴스를 남기지 않습니다.
3. 새 세대의 가드는 DB 확정 상태·인증 버전으로 구성합니다. 이전 세대 세션을 AOF·DB에서 새 세대로 복사하지 않으며, 사용자는 재로그인합니다.
4. 이전 세대 토큰 거절·새 로그인·고정 만료·정지 계정 차단과 ACL을 확인한 뒤 인증 게이트를 엽니다. 이전 키 정리는 인증 영역의 해당 세대만 대상으로 하며 다른 캐시·업무 데이터를 지우지 않습니다.

AOF에 로그아웃 전 세션을 남긴 상태의 재시작과 Redis 데이터 복원도 시험합니다. 이전 세대 토큰이 새 인스턴스에서 다시 유효해지지 않아야 합니다. 장애 전 비밀번호 변경의 DB 커밋을 복구 시점까지 지연시키고, 이전 쓰기를 정리하기 전 가드 구성·재개가 차단되는지와 복구 후 이전 비밀번호·세션이 거절되는지도 확인합니다. API 프로세스만 재시작하고 Redis 실행 식별자·인증 세대가 그대로인 경우는 기존 세션의 원래 만료 시각을 유지합니다.

## 종료·재실행·초기화

```bash
# 컨테이너만 종료하고 DB·Kafka·Redis·관측 볼륨을 보존
docker compose --profile observability --profile tools down

# 같은 데이터로 재실행
docker compose up -d --wait --wait-timeout 300
python3 scripts/smoke.py

# 이 Compose 프로젝트의 로컬 데이터까지 삭제할 때만 실행
docker compose --profile observability --profile tools down -v --remove-orphans
```

DB 볼륨이 남아 있으면 초기화 스크립트가 다시 실행되지 않습니다. 따라서 `.env`의 DB 비밀번호만 바꾸면 기존 DB와 연결할 수 없습니다. 필요한 경우 기존 DB에서 비밀번호를 변경하거나, 보존할 데이터가 없는 로컬 환경에서 위 초기화 명령으로 볼륨을 삭제하고 다시 시작합니다.

Redis 비밀번호를 바꿀 때도 앱과 Redis를 함께 재생성해야 합니다. Redis 데이터는 AOF 볼륨에 보존됩니다. Zipkin 메모리 추적은 재시작하면 사라집니다.

현재 개발 환경의 스키마 적용은 [DB 정책](migrations.md)을 따릅니다. 초기 SQL 수정·이미지 재빌드만으로 기존 DB는 바뀌지 않으며, 새 빈 개발 DB에서 검증합니다. 관측 프로필을 켠 적이 있으면 종료 시에도 `--profile observability`를 지정합니다.

버전 호환성은 [Spring Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Spring Cloud 호환표](https://cloud.spring.io/), Kafka 구성은 [공식 Docker 안내](https://kafka.apache.org/42/getting-started/docker/)를 참고합니다. 외부 배포 시 지원 기간·보안 업데이트·인증·장애 내성을 검토합니다.
