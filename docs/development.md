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

`init_env.py`는 필요한 비밀번호를 무작위로 생성해 `.env`에 저장하고 기존의 비어 있지 않은 값은 보존합니다. 새 비밀번호 변수가 추가돼 Compose가 실행을 거절하면 다시 실행해 빈 값만 채웁니다. 파일을 공유·커밋하지 않습니다. 주소록 암호문 키 `ADDRESS_ENCRYPTION_KEY`를 바꾸면 저장된 주소를 열 수 없습니다. `<대상>_GATEWAY_CREDENTIAL`은 Gateway가 대상 서비스에 보내는 [내부 호출 자격](decisions/0010-internal-service-credentials.md)입니다. 교체할 때는 이전 값을 대상의 `_PREVIOUS`에 옮기고 새 값을 넣은 뒤 대상 서비스와 Gateway를 재기동하며, 전환이 끝나면 `_PREVIOUS`를 비우고 대상을 다시 재기동합니다. 포트 충돌이 나면 `.env`의 포트 값을 변경합니다. Dockerfile은 미리 빌드한 `app.jar`를 사용하므로 최초 실행과 Java 변경 후에는 Gradle 빌드가 필요합니다.

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

## 개발용 DB fixture

`APP_DEV_FIXTURES_ENABLED=true`는 각 서비스 DB에 없는 실험 데이터를 추가합니다. 기본값은 false입니다. `APP_DEV_FIXTURE_SCENARIOS_ENABLED=true`도 지정하면 Payment 접수·정산 일배치·모의 지급을 실제 서비스 경로로 실행합니다. 기존 Worker/Relay 활성화 설정은 바꾸지 않습니다. 정산 일배치 스케줄러와 지급 Worker는 fixture 조회 상태를 바꾸지 않도록 모두 끕니다. 둘 중 하나라도 켜져 있으면 fixture 기동을 거절합니다. 개발 전용 DB에서 사용합니다.

```bash
python3 scripts/init_env.py
./gradlew bootJar
APP_DEV_FIXTURES_ENABLED=true APP_DEV_FIXTURE_SCENARIOS_ENABLED=true \
SETTLEMENT_DAILY_BATCH_ENABLED=false SETTLEMENT_PAYOUT_WORKER_ENABLED=false \
APP_ORDER_STUB_ENABLED=false \
docker compose up -d --build --wait --wait-timeout 300
```

계속 사용할 옵션은 `.env`에 저장합니다. 비밀번호는 `init_env.py`가 생성한 `DEV_FIXTURE_PASSWORD`이며 최초 계정에만 적용합니다. 기존 Argon2id·주소 AES-GCM 암호화를 사용합니다. 비밀번호나 주소 암호화 키를 공유·커밋하지 않습니다.

### Commerce 조회 데이터

계정·상품·주소의 UUID 앞 구간은 `00000000-0000-4000-8000-`이며 아래 숫자를 12자리로 채운 접미사를 씁니다.

| 계정 | 이메일 | 접미사·상태 |
| --- | --- | --- |
| 구매자 A/B | `fixture-buyer-a@example.test` / `fixture-buyer-b@example.test` | 1 / 11, ACTIVE |
| 주소 없는 구매자 | `fixture-buyer-41@example.test` | 41, ACTIVE·마일리지 0 |
| 복수·삭제 주소 구매자 | `fixture-buyer-51@example.test` | 51, ACTIVE |
| 기본 주소 없는 구매자 | `fixture-buyer-61@example.test` | 61, ACTIVE·마일리지 500 |
| 정지·삭제 구매자 | `fixture-buyer-71@example.test` / `fixture-buyer-81@example.test` | 71 SUSPENDED / 81 DELETED |
| 판매자 A/B | `fixture-seller-a@example.test` / `fixture-seller-b@example.test` | 2 / 22, ACTIVE |
| 정지·삭제 판매자 | `fixture-seller-72@example.test` / `fixture-seller-82@example.test` | 72 SUSPENDED / 82 DELETED |
| 역할별 동일 이메일 | `fixture-shared@example.test` | 구매자 91 / 판매자 92, ACTIVE |

구매자 8명·판매자 5명, 상품·재고 각각 28행, 주소 29행(논리삭제 1행)을 만듭니다. 수납 상자(상품 3, 23,000원)·키보드(23, 39,000원)·머그컵(33, 10,000원)은 재고 100개입니다. 상품 400~420은 판매자 A의 페이지 실험용 판매 상품이며 각각 10,000원입니다. 상품 430은 품절, 431은 DRAFT, 432는 STOPPED, 433은 DELETED입니다. 공개 상품은 25개이며 판매자 A 목록도 기본 페이지 크기 20을 넘습니다.

구매자 A는 기본 주소 101과 일반 주소 120~141(총 23개), B는 기본 주소 111, 구매자 51은 주소 151~153(153 삭제), 구매자 61은 기본 지정 없이 161·162를 가집니다. 연락처 `000-`, 주소 `가상시 테스트구`는 합성 값입니다. 상품 400~404에는 실제 재고 예약 서비스로 RESERVED·CONSUMED·RELEASED·환불 복원·반품 복원을 각각 적용합니다. 초기 재고 이력 27행과 예약 처리 이력 11행, 예약 5행을 만들며 실제 주문·환불 실행과는 별개입니다.

기동 후 [Swagger UI](http://localhost:8080/swagger-ui.html)에서 `/api/buyer-sessions` 또는 `/api/seller-sessions`로 로그인하고 응답의 `token`을 Authorize에 입력합니다. 공개 `/api/products?page=1`, 본인 `/api/sellers/me/products?page=1`, `/api/buyers/me/addresses?page=1`과 상품 단건·재고를 바로 조회합니다. 정지·삭제 계정 로그인은 거절되고 타인의 상품·주소 변경은 허용하지 않습니다. 포트를 바꿨으면 `.env`의 `GATEWAY_PORT`를 사용합니다.

### Payment·Settlement·Finance Mock

금융 시나리오 seed는 `20261030`, 스냅샷 기준 시각은 `2026-09-01T09:00:00+09:00`입니다. 합성 금융 ID는 UTF-8 `dev-fixture-v1:<이름>`의 Java `UUID.nameUUIDFromBytes`로 정합니다. DB가 생성하는 업무 결과 ID는 임의 UUID이며 재실행 시 업무 키로 기존 결과를 찾습니다. 인증·Worker의 실제 Clock은 고정하지 않습니다.

| 영역 | 기본 적재 | 시나리오 옵션을 켠 처리 결과 |
| --- | --- | --- |
| Payment | 구매자 1·11·41·61 지갑(100,000·0·0·500점)과 양수 지갑 SEED 2행. 결제 6개 상태 스냅샷과 정산 대응 CONFIRMED 스냅샷 8개 | 외부 39,000원·혼합 23,000원(M 1,000)·전액 마일리지 10,000원·0점 지갑 잔액 부족 요청. APPLIED 3·REVIEW_REQUIRED 1, 결제 3·승인 작업 2·예약 2·RESERVE 2·DEBIT 1. 구매자 A 잔액 90,000·예약 1,000점 |
| Settlement | 항목 8개: 판매자 A의 마감 직전 2·정각 1·직후 1·환불 예약 1·환불 완료 1, 판매자 B의 직전 1·구매확정 대기 1 | `2026-09-01` 실제 일배치를 fixture 항목으로 제한해 배치·지급·작업 각 2행, 지급 예약 3행 생성. 판매자 A 39,100원·B 33,150원. 정각·직후는 다음 배치 대상으로 남음 |
| Finance Mock | 기존 기본 지급 설정과 별개인 `fixture-payout-funding`(1,000,000원), `fixture-normal-v1`·`fixture-reject-v1`·`fixture-response-loss-v1`·`fixture-lookup-failure-v1` | 시나리오별 19,550원 실제 모의 지급. 성공 3·거절 1, 자금 예약 4·현금 차감 3·멱등 기록 4. 잔액 941,350원·예약액 0원 |

Payment는 API 역할에서만 기동 시나리오를 실행합니다. Kafka의 별도 `commerce-settle.fixture.payment.v1`의 실제 발행 결과를 Inbox 출처로 저장하고, 지정 이벤트만 기존 접수 처리기로 처리합니다. 일반 WAITING 작업·정식 Commerce 토픽·Kafka consumer offset을 변경하지 않습니다. 이벤트 이름은 `request-event-0`~`3`, 주문은 `request-order-0`~`3`, 회차는 `request-cycle-0`~`3`입니다. 각각 외부·혼합·전액 마일리지·잔액 부족입니다. 승인 Worker·결과 Outbox·PG 실행은 구현되지 않아 PENDING 승인 작업을 임의 완료하지 않습니다.

정산 스냅샷의 주문·결제 이름은 `order-settlement-0`~`7`·`payment-settlement-0`~`7`이며 Payment와 ID·당사자·상품·금액을 맞춥니다. 실제 PG 승인이나 서비스 간 이벤트 전달 결과는 아닙니다. `order-settlement-*`는 Commerce DB 주문과 별개인 합성 금융 참조입니다. Settlement의 기존 기본 지급 재원·시나리오(`payout-funding-default`·`payout-normal-v1`)를 사용하며, 일배치가 실제 Batch 메타데이터를 기록합니다. fixture 항목 ID도 Job 식별 파라미터에 포함해 일반 일배치 실행과 구분합니다. 동일 파라미터의 완료된 실행은 다시 만들지 않으며, 다른 인스턴스가 이미 실행 중이면 그 실행에 맡깁니다. 동일 판매자·날짜의 일반 정산 항목이 함께 선점되면 그 판매자 트랜잭션을 롤백하고 실행을 실패시켜 기존 데이터를 보존합니다.

모의 지급 업무 키 이름은 `mock-payout-0`~`3`입니다. 모의 지급은 내부 `/mock/payouts` API로도 실행·조회할 수 있으며 Gateway에 공개하지 않습니다. 응답 유실 설정에서 새 업무 키의 최초 POST는 504, 저장 결과 GET은 200입니다. 기동 시나리오가 이미 생성한 고정 키의 POST는 재요청이므로 202이며, 조회 장애 설정의 GET은 503입니다. 같은 업무 키 재요청은 기존 거래를 반환해 추가 차감하지 않습니다. 이 기록은 별도 모의 지급 예제입니다. #88의 지급 Worker로 이미 생성한 정산 작업을 처리하려면 fixture 옵션을 끄고 Worker를 명시적으로 켭니다. 기존 비활성 상태의 조회 fixture와 금융 처리 실행을 구분합니다.

```bash
APP_DEV_FIXTURES_ENABLED=false SETTLEMENT_DAILY_BATCH_ENABLED=false \
SETTLEMENT_PAYOUT_WORKER_ENABLED=true docker compose up -d --wait settlement
```

Payment·Settlement에는 업무 조회 HTTP API가 없어 서비스별 DB로 확인합니다. 서비스 간 DB를 조인하거나 loader가 타 서비스 DB를 읽지 않습니다.

```bash
docker compose exec postgres psql -U postgres -d commerce -c \
  "SELECT buyer_id,id,status,total_amount,mileage_amount FROM commerce.orders ORDER BY buyer_id,id;"
docker compose exec postgres psql -U postgres -d payment -c \
  "SELECT status,count(*) FROM payment.payments GROUP BY status; SELECT kind,sum(amount) FROM payment.mileage_entries GROUP BY kind;"
docker compose exec postgres psql -U postgres -d settlement -c \
  "SELECT seller_id,amount,status FROM settlement.payouts; SELECT status,count(*) FROM settlement.settlement_items GROUP BY status;"
docker compose exec postgres psql -U postgres -d finance_mock -c \
  "SELECT account_ref,opening_balance,balance,reserved_amount FROM finance_mock.mock_accounts; SELECT status,count(*) FROM finance_mock.mock_transactions GROUP BY status;"
```

서비스별 초기 데이터·설정은 한 트랜잭션에서 적재하고 기존 수정·논리삭제·잔액·원장을 보존합니다. 고정 계정 ID/이메일·상품 소유자·금융 스냅샷 주문 ID 충돌은 적재를 롤백합니다. 업무 시나리오는 초기 적재 커밋 후 개별 업무 트랜잭션으로 실행합니다. 중간 실패 때 이미 커밋한 금융 효과를 되돌리지 않고 고정 이벤트·업무 키로 남은 처리를 이어갑니다. 옵션을 꺼도 기존 데이터는 남습니다. 전체 DB를 아우르는 원자적 초기화나 기존 실험 상태의 리셋은 제공하지 않습니다.

#94에서 추가된 DB 저장 모델에도 PAYMENT_PENDING/UNSHIPPED 주문과 독립 암호화 배송지 사본을 각 4행 넣습니다. 이름은 `request-order-0`~`3`, 회차는 `request-cycle-0`~`3`, 주소는 `request-address-0`~`3`이며 Payment 요청과 ID·구매자·판매자·상품·금액·생성 후 10분 대기 기한을 맞춥니다. 배송 seed는 20261030, 달력은 `fixture-calendar-v1`/공휴일 없는 Asia/Seoul 사본입니다. 이 fixture는 저장·조회 모델용 사본이며 재고 예약·Outbox를 새로 실행하지 않습니다. 기본 주문 API에서 본인 자료를 조회할 수 있습니다. 이 저장 모델 fixture에는 재고 예약이 없어 주문 결과의 전체 흐름 시험에는 위 샘플 대신 `POST /api/orders`로 새 주문을 생성합니다.

주문은 `APP_ORDER_STUB_ENABLED=true`일 때 구매자 A/B별 메모리 샘플 10개를 별도로 조회합니다. DB 주문·금융 데이터와 연결되지 않으며 DB 상품·주소 변경도 반영하지 않습니다. 배송/구매확정, 환불/SAGA, 마일리지 해제/복원, 쿠폰·이벤트·알림, PG·간편결제·PG 입금 대역은 선행 업무 구현 후 fixture를 추가합니다. 부하 시험용 대량 데이터 생성도 이 기능 fixture와 별개입니다.

## 주문 영속 저장 기반

`Order.pendingRegular`는 쿠폰·이벤트 없는 일반 주문의 상품·금액·결제 구성과 최초 회차, 생성 후 10분 대기 기한을 고정합니다. 마일리지 잔액 확인이나 외부 승인·예약은 수행하지 않습니다. 배송 소요일·공휴일 달력은 [배송 정책](policies.md#배송환불-기한과-자동-구매확정)을 사본으로 보존합니다.

`OrderRepository.save`는 호출자의 트랜잭션 안에서 주문과 `OrderAddress`를 함께 저장하며 트랜잭션 밖 호출은 거절합니다. 호출자는 주소록의 암호문을 복사하지 않고, 기존 `AddressCipher`로 새 주문 주소 ID에 묶어 다시 암호화한 사본을 넘겨야 합니다. 목록·단건·주소 조회는 구매자 ID로 제한합니다. 일반 주문 응용 서비스가 상품·활성 구매자·주소의 현재 유효성을 확인하고 재고 예약·Outbox·영구 멱등 기록까지 같은 트랜잭션에 연결합니다. 쿠폰·이벤트는 소유 어댑터로 연결하며, 결제 결과·배송·취소/반품은 [주문 업무 흐름](#주문-업무-흐름)을 따릅니다.

`OrderTest`와 `OrderRepositoryIntegrationTest`는 금액·결제 구성, 배송 정책·주소 사본, 실제 PostgreSQL 재조회·구매자별 목록·원자 저장·유일성·잠금 대기 상한을 검사합니다. 다른 DB 시험과 같이 빈 전용 컨테이너에 commerce 초기 SQL을 적용하고 종료 때 제거합니다. 기존 개발 DB에는 초기 SQL을 재실행하지 않으며 [DB 적용 정책](migrations.md#현재-개발-환경의-적용-방식)을 따릅니다.

## 일반 주문 API

`APP_ORDER_STUB_ENABLED`의 기본값 false에서는 실제 DB의 `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{orderId}`를 제공합니다. 개발 fixture를 켜고 구매자 A로 로그인하면 초기 DB 주문과 새 주문을 조회할 수 있습니다. 같은 인스턴스에서 메모리 stub과 DB API를 함께 켜지는 않습니다.

요청은 정식 `OrderCreate`의 11개 키가 모두 필수입니다. 기본 설정은 쿠폰·이벤트 없는 일반 주문을 지원합니다. 쿠폰·이벤트 조합을 입력하면 소유 어댑터 연결 전에는 503이며, 수량·마일리지 병용·이벤트 버전 조합 오류는 400입니다. 본인 `addressId`를 명시하고 조회한 상품 버전·총액을 보냅니다. 카드·혼합·전액 마일리지 구성을 결제 요청에 보존하지만, 마일리지 확인·예약은 Payment의 책임이며 이 API가 잔액 확보나 결제 승인을 보장하지 않습니다.

```bash
curl -i http://localhost:8080/api/orders \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: regular-order-create-001' \
  -d '{"productId":"00000000-0000-4000-8000-000000000003","quantity":1,"eventId":null,"buyerCouponId":null,"mileageAmount":0,"addressId":"00000000-0000-4000-8000-000000000101","expectedProductVersion":0,"expectedEventVersion":null,"expectedTotalAmount":23000,"externalMethod":"CREDIT_CARD","methodAccountRef":"fixture-card"}'
```

`BUYER_TOKEN`에는 로그인 응답의 token을 넣습니다. `201`과 `Location`은 `PAYMENT_PENDING` 주문 접수이며 결제 완료가 아닙니다. 주문·배송지 사본·재고 예약·멱등 기록·`OrderPaymentRequested` Outbox는 전부 커밋되거나 롤백됩니다. 재고 행을 먼저 잠근 뒤 상품의 판매 상태·가격·버전을 확인합니다. 주문 키는 만료시키지 않고 같은 키·본문의 재요청은 현재 본인 주문을 반환합니다. 상품·주소가 바뀌어도 다시 예약하지 않으며 다른 본문은 409입니다. 주소록 변경·삭제와 별개로 주문 배송지 사본을 보존합니다.

목록은 `createdAt DESC, id DESC`, 기본 20개·최대 100개입니다. 데이터 범위를 벗어난 유효한 페이지는 빈 `items`와 본인 주문의 `totalElements`를 반환합니다. 타인·없는 주문은 404이며 정지·삭제 구매자는 접근할 수 없습니다. DB 재기동 뒤에도 주문과 키를 보존합니다. 배송 사본은 기존 `weekdays-v1`(Asia/Seoul, 공휴일 없는 달력), seed 기본 67과 `order-regular-v1` 정책 버전을 사용합니다. seed는 `app.orders.simulation-seed`로 지정할 수 있습니다.

Outbox relay는 기존 `app.outbox.enabled=false` 기본값을 유지합니다. 격리한 실험 인스턴스에서 켜면 `commerce-settle.commerce.v1`에 주문 ID를 key로 발행합니다. Payment의 `PaymentAccepted` 발행은 #75에서 구현됐습니다. Payment의 재시도 상한(#76)과 Commerce의 신규 접수 제한 gate는 별도 작업입니다. 결제 확정·거절·취소 반영은 아래 주문 업무 흐름을 따릅니다. 접수 ID 연결은 [결제 접수 결과 수신](#결제-접수-결과-수신)에서 확인합니다. 결과 처리기를 켜지 않으면 주문은 대기 상태를 유지하며, 10분이 지나도 승인 차단 결과 없이 예약을 해제하지 않습니다. 새 주문의 재고 예약이 누적되는 실험은 전용 DB에서 실행합니다.

`OrderIntegrationTest`는 실제 HTTP·PostgreSQL에서 11필드 입력·본인 조회·금액/이벤트 계약·영구 멱등·동시 요청·마지막 재고 경쟁·DB 저장 실패의 전체 롤백을 검증합니다.

## 결제 접수 결과 수신

Commerce의 `PaymentAccepted` Listener와 처리기는 기본 비활성입니다. 격리한 시험 인스턴스에서 `APP_KAFKA_PAYMENT_ACCEPTED_ENABLED=true`, `APP_PAYMENT_ACCEPTED_PROCESSOR_ENABLED=true`로 각각 활성화합니다. Payment는 기존 주문 요청 Listener·Worker 처리기·Outbox Relay, Commerce는 주문 Outbox Relay를 함께 켜야 전체 왕복이 진행됩니다. 기본 `auto-offset-reset=none`은 유지하며 새 소비 그룹의 시작 offset은 시험 전에 명시적으로 준비합니다. 새 DB에는 갱신한 Commerce 초기 SQL을 적용하고, 기존 DB·볼륨에 전체 SQL을 재실행하거나 데이터를 초기화하지 않습니다.

`commerce.v1`은 `commerce-settle.payment.v1`의 원문·최초 위치와 WAITING 행을 커밋한 뒤 ACK합니다. 같은 ID·같은 bytes는 원래 결과를 유지하고 다른 bytes는 `inbox_conflicts`에 보존합니다. 주문 결과도 같은 Listener가 인계하며, 같은 생산자 토픽의 정산·알림 전용 이벤트는 해당 소비 그룹에 맡깁니다. 잘못된 스키마·알 수 없는 이벤트는 무ACK로 Consumer를 중단하므로 입력 원인을 확인한 뒤 재개합니다. DLT 발행이나 금융 보상으로 자동 전환하지 않습니다.

처리기는 1초마다 최대 100건을 한 건씩 별도 트랜잭션으로 처리합니다. 도래한 잠긴 Inbox는 건너뛰고, 주문의 회차·버전·기존 ID를 확인해 `paymentId`와 Inbox APPLIED를 함께 저장합니다. `PAYMENT_PENDING`·금액·주소 사본·재고 예약은 유지합니다. envelope의 Payment aggregateVersion을 주문 버전으로 비교하지 않습니다.

선행 주문 미도착·일시 반영 실패는 1초부터 최대 60초 간격으로 지수 재시도하며 10회 소진 시 사유와 함께 REVIEW_REQUIRED로 남깁니다. 반영 트랜잭션이 롤백되면 별도 트랜잭션에서 재시도 기록을 남기며, 이미 다른 처리기가 완료한 행은 변경하지 않습니다. 이전 회차·버전·상태·다른 ID의 충돌은 즉시 확인 대상으로 보존합니다. 운영 HTTP 재처리 API는 후속 작업입니다. 확정·거절·취소와 환불 결과는 별도 주문 결과 처리기가 같은 Inbox에서 처리합니다.

`PaymentAcceptedIntegrationTest`는 전용 PostgreSQL·Kafka에서 HTTP 주문 조회, 원자 반영·롤백·중복·충돌·잠금 대기 상한·건너뛰기·지수 재시도·소진·Consumer 재시작을 검증합니다. 이 시험은 빈 전용 토픽에서 `earliest`를 명시적으로 사용하며 운영 기본값을 바꾸지 않습니다.

## 주문 업무 흐름

주문 API는 실제 PostgreSQL을 사용합니다. Swagger UI에서 구매자·판매자로 각각 로그인해 발급받은 토큰으로 호출하거나 Postman/curl을 사용합니다. 공개 요청은 Gateway로 보내고 내부 컨텍스트·운영 종결은 Gateway에 노출하지 않습니다. 판매자 목록에는 주소가 없으며, 본인 이행 대상 주문의 단건 조회에서만 주문 당시 주소 사본을 제공합니다. `deliveryAddress.isDefault=false`는 주소록 항목이 아닌 주문 사본임을 뜻합니다.

| 순서 | 호출·동작 | 완료 의미 |
| --- | --- | --- |
| 구매자 견적·주문 | `POST /api/order-quotes` → 위 일반 주문 생성 → `GET /api/orders/{orderId}` | 견적은 예약하지 않음. 생성 201은 결제 대기 |
| 결제 결과 | Payment v1 결과 인계 → 주문 결과 처리기 → 구매자/판매자 주문 조회 | 확정 결과만 PAID·판매 수량 반영. 미확정·취소 거절과 취소 대기 중 거절은 승인 차단 확인까지 예약 유지 |
| 판매자 발송 | `POST /api/sellers/me/orders/{orderId}/shipments`에 `{expectedVersion, shipmentReference}` | 최초 서버 발송·모의 도착 시각 고정 |
| 배송·구매확정 | 내부 매분 작업 | 주말·보관한 공휴일 제외. 도착 예정 시각+168시간 초과·진행 중 환불 없음일 때 확정 |
| 구매자 취소/반품 | `POST /api/orders/{orderId}/refund-requests`에 `{kind, reason, expectedVersion}` | 미발송 CANCEL 자동 승인. 발송 후 RETURN은 판매자 심사 대기 |
| 판매자 심사·입고 | `decision` → 실제 도착·재판매 가능 검수 뒤 `receipt` | 입고는 명시적 판매자 확정이며 금융 결과와 무관하게 재고 한 번 복원 |

변경 호출마다 8~128자 `Idempotency-Key`를 보내고, `expectedVersion`은 대상 주문 또는 반품 요청의 최신 조회 값입니다. 같은 키·본문은 현재 결과를 반환하며 다른 본문은 409입니다. 예를 들어 발송 후 구매자가 반품을 요청하고 판매자가 승인·입고하려면 다음 본문을 각 경로에 보냅니다. 응답의 요청 `id`와 최신 `version`을 다음 호출에 사용합니다.

```text
BUYER: POST /api/orders/{orderId}/refund-requests
{"kind":"RETURN","reason":"단순 변심","expectedVersion":2}
SELLER: POST /api/sellers/me/refund-requests/{requestId}/decision
{"decision":"APPROVE","reason":"반품 승인","expectedVersion":0}
SELLER: POST /api/sellers/me/refund-requests/{requestId}/receipt
{"expectedVersion":1}
```

입고 전에 `GET /api/refund-requests/{requestId}` 또는 판매자 요청 목록에서 최신 버전을 확인합니다. 금융 접수 이벤트도 요청 버전을 바꿀 수 있습니다. `receipt`는 승인된 RETURN 상품의 실제 도착·재판매 가능 검수를 마친 판매자의 확정입니다. 도착·검수를 자동 생성하지 않고, 금융 환불 성공만으로 입고하지 않습니다. 미입고 기한·자동 반품 운송·재판매 불가 판정 API는 현재 계약에 없습니다. 승인 전 RETURN만 구매자가 `withdrawal`로 철회할 수 있습니다.

주문 스케줄러는 `APP_ORDER_WORKFLOW_ENABLED=true`, 결과 처리기는 `APP_ORDER_RESULT_PROCESSOR_ENABLED=true`로 시험 인스턴스에서 활성화합니다. 결과 수신은 기존 `APP_KAFKA_PAYMENT_ACCEPTED_ENABLED=true`를 공유하며, 접수 ID 처리기는 기존 플래그를 별도로 유지합니다. 기본값은 모두 false입니다. 이 설정은 인스턴스 환경에 전달해야 하며 `.env` 추가만으로 기존 Compose 컨테이너에 전달되지 않습니다. 빈 전용 DB에는 갱신한 초기 SQL을 적용하고 기존 볼륨을 초기화하지 않습니다.

Payment 내부 조회와 운영 종결은 ADR 0010의 `service:payment`·`operator:<식별자>` 자격을 인스턴스의 `app.internal-auth.senders`에 설정해야 합니다. 사용자 토큰이나 Gateway 자격으로 호출할 수 없습니다. 운영 종결은 외부 확정 거절·마일리지 미복원·정산 예약 해제·고객 확인을 모두 검증한 `RefundClosureEvidence`가 필요합니다. 기본 미연결 구현은 503이며 금융 실패만으로 주문을 PAID로 되돌리거나 자동 구매확정을 진행하지 않습니다.

쿠폰·이벤트의 실제 예약·확정·해제·복원은 `OrderBenefits` 어댑터로 교체합니다. 주문 트랜잭션에 참여하고 이벤트 가격/버전·기간/참여 제한과 쿠폰 소유/수단/기한/예산을 다시 확인해야 합니다. `OrderWorkflowIntegrationTest`의 DB 기록 대역은 해당 호출·금액 사본·로컬 롤백만 검증하며 실제 쿠폰 예산·이벤트 재고 구현의 완료가 아닙니다. Payment/Settlement의 실제 회차 재접수·마일리지 재예약·승인 차단·원환불/예약 해제 조회·환불 복구 확인 왕복은 각 소유 서비스와 통합 검증해야 합니다. 금융 성공을 임의로 반환하는 기본 어댑터는 없습니다. 거래 알림과 신규 접수 용량 gate는 별도 미구현 경계입니다.

```bash
./gradlew :commerce:test --tests '*OrderWorkflow*' --tests '*OrderIntegrationTest' --tests '*PaymentAcceptedIntegrationTest'
```

이 명령은 전용 PostgreSQL·Kafka와 명시적 계약 결과 대역으로 구매자/판매자 HTTP 흐름, 중복·역순·기한 경합·잠금 상한·롤백을 확인합니다. 실제 PG·마일리지·정산 금융 정합성 완료를 뜻하지 않습니다.

## 주문 API stub

주문 생성·본인 목록·단건 조회를 학습할 때만 `APP_ORDER_STUB_ENABLED=true`로 켭니다. 기본값은 false입니다. 상품·주문·멱등 결과와 주소 사본은 실제 DB와 별개인 메모리 데이터이며 재시작하면 초기화됩니다. 실제 주문 저장·재고/마일리지 예약·결제·Inbox/Outbox·자동 상태 전이는 구현하지 않습니다. 쿠폰·이벤트 연동은 TODO이고, 생성 주문과 초기 샘플 모두 할인액은 0원·eventId는 null입니다.

이 stub의 `OrderStubCreate` 입력은 `productId`, `quantity`, `mileageAmount`, `expectedProductVersion`, `expectedTotalAmount`, `externalMethod`, `methodAccountRef`의 7개 키입니다. 두 외부 수단 값도 키는 필수이며 전액 마일리지일 때 null로 보냅니다. 아직 지원하지 않는 `buyerCouponId`, `eventId`, `expectedEventVersion`과 수동 `addressId`를 보내면 400으로 거절합니다. 정식 [OrderCreate 계약](openapi.json)은 기본 DB API에서 11개 필드를 받습니다. 켠 인스턴스의 `/v3/api-docs`에서 개발용 입력을 확인합니다.

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

가상 주소 풀은 수령인·연락처·우편번호·주소가 모두 합성 데이터인 1,000개입니다. 주문 ID와 `APP_ORDER_STUB_SEED`로 하나를 골라 `OrderView.address`에 보관합니다. 재요청·조회에서 다시 선택하지 않으며 다른 주문은 같은 주소를 뽑을 수 있습니다. 연락처는 실제 통신번호가 아닌 `000-` 접두사, 주소는 `가상시 테스트구`를 사용합니다. 기존 28필드 HTTP 주문 응답에 주소를 추가하거나 금융 이벤트로 보내지는 않습니다. 이 stub은 합성 주소를 사용하며, 일반 주문은 [구매자 주소록](api.md#기능-목록)의 본인 주소를 사용합니다. 택배사·배송 추적 API는 제공하지 않습니다.

시작 시 구매자 `00000000-0000-4000-8000-000000000001`·`00000000-0000-4000-8000-000000000011`에 각각 10개 주문을 생성해 미결제·결제완료·발송·배송완료·구매확정을 보여줍니다. 그 외 구매자도 생성 요청을 보낼 수 있으며 새 주문은 본인에게만 보입니다. 상품 사본은 수납 상자(접미사 `000000000003`, 23,000원·판매자 `000000000002`), 키보드(`000000000023`, 39,000원·판매자 `000000000022`), 머그컵(`000000000033`, 10,000원·판매자 `000000000002`)입니다. 접미사 앞 UUID 구간은 모두 `00000000-0000-4000-8000-`이며 상품 버전은 0입니다.

seed 기본값은 67입니다. 같은 seed·Clock이면 초기 주문과 주소 사본이 같습니다. 배송 소요일은 [배송 정책](policies.md#배송환불-기한과-자동-구매확정)의 SHA-256 규칙으로 2·3영업일을 정하고 `weekdays-v1`을 고정합니다. 이미 만든 시각·주소·배송 이력은 조회마다 바뀌지 않습니다. 목록은 `createdAt DESC, id DESC`, page는 0부터·size는 1~100이며 타인 주문은 없는 주문과 같은 404입니다.

메모리 재고 포트는 `OrderStubConfiguration` 내부에서 만들어 stub 서비스에만 전달하며 Spring Bean으로 등록하지 않습니다. 실제 `OrderInventory` 구현이 같은 이름이나 다른 이름의 Bean으로 등록돼도 stub은 그 구현을 사용하지 않습니다. 결제수단은 `CreateOrderCommand.ExternalMethod`를 HTTP DTO와 응용 서비스가 함께 사용하고, 입력 조합 오류는 해당 `externalMethod` 또는 `methodAccountRef`로 반환합니다.

주문 stub을 꺼도 판매자 상품 API의 공통 `AuthenticatedActorResolver`, HTTP Jackson의 enum·문자열·정수 변환 제한과 생성 OpenAPI의 nullable enum 보정은 기존 API에 적용됩니다. 판매자 신원·역할 오류의 401·403 계약과 이벤트용 공용 `ObjectMapper`는 유지합니다.

전용 HTTP 통합 테스트는 실제 HTTP 서버·Controller·서비스·JSON 검증·오류 응답을 연결하며 DB·Kafka·Redis를 사용하지 않습니다. 숫자·숫자 문자열의 결제수단 변환과 boolean의 문자열 변환을 거절하고, 실제 요청·응답을 정식 `docs/openapi.json`과 생성된 OpenAPI 3.1 스키마 양쪽으로 검증합니다. stub 입력은 정식 `OrderCreate`에서 미지원 4필드만 제외해 비교하며 원래 계약은 유지합니다. 생성 명세의 필드·필수 키·타입·null·허용값·범위·형식·페이지 항목 참조를 정식 계약과 대조하고, 필수 키 누락·잘못된 상태·배송일·금액과 페이지 내부 주문 변조가 거절되는지 검사합니다. 주문 3개 operation의 성공·공통 오류 코드, 미디어 타입·본문 참조, 생성 `201`·필수 `Location`, POST `503`의 조건부 `Retry-After`와 멱등·페이지·경로 인자를 정식 계약과 대조합니다. 모든 실제 HTTP 응답도 해당 operation의 코드·본문·헤더에 연결해 검증하며, 재요청의 `Location`, 오류의 실제 상태·요청 경로·추적 ID·필드 오류를 확인합니다. 테스트에서는 실제 추적을 켜고 Zipkin 내보내기를 끕니다. 정식 명세에 나열하지 않은 프레임워크·서버 오류는 생성 명세의 `default` 공통 오류로 설명합니다. 429 요청 제한과 503 수용량 제어는 구현 예정이며 응답 명세 검사가 실제 제어 동작을 증명하지는 않습니다. 이 테스트가 Compose 전체 실행이나 금융 처리 완료를 증명하지는 않습니다.

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
| Alertmanager | <http://localhost:9093> | `observability`, 기본 외부 발송 없음 |
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

Python 테스트는 환경 초기화가 기존 설정을 보존하는지 확인합니다. smoke는 각 실행의 DB·Redis 연결·메트릭, Gateway 문서 라우팅·관리 경로 차단, Gateway 자격이 없는 커머스 직접 호출의 `401`, DB 간 접근 거부, 앱별 Redis ACL(커머스의 인증 키 쓰기·스크립트 실행과 `SCRIPT`·`FUNCTION`·키 목록·DB 전환 거절, Gateway의 `EVALSHA_RO` 검증과 쓰기·`EVAL`·`SCRIPT` 거절, 나머지 앱의 인증 키 거절), Kafka·Redis 왕복과 Zipkin의 Gateway→Commerce 추적 전파를 검사합니다. 임시 Kafka 토픽·Redis 키는 삭제합니다. CI는 낮은 부하의 k6 실행도 확인합니다. 결제·SAGA의 정합성과 성능은 업무 시나리오로 검증합니다.

정적 검사만 실행할 때는 `./gradlew spotlessCheck checkstyleMain checkstyleTest`, 테스트만 실행할 때는 `./gradlew test`를 사용합니다. `build`는 이 검사들을 포함하므로 별도 실행이 필수는 아닙니다.

Payment API와 Worker는 같은 `payment` 모듈로 빌드합니다. Gradle 모듈별 명령은 `./gradlew :payment:build`처럼 실행합니다.

### Kafka 전달 기반

Commerce·Payment·Settlement는 자기 원본 토픽 `commerce-settle.<서비스>.v1`을 생성합니다. 소비자별 DLT는 `commerce-settle.<소비자>.v1.dlt`이며 소비자는 `commerce`, `commerce-notifications`, `payment`, `settlement`입니다. 모두 파티션 3개·복제 1개이고 원본은 24시간, DLT는 7일 보존합니다. Payment·Settlement의 명시적 원문 격리·DLT 발행 경로는 아래에 설명하며 다른 소비자는 토픽만 준비한 상태입니다. 기존 토픽 설정이 다르면 smoke가 실패하므로 실제 값을 대조해야 합니다.

현재 구현한 전달 기반은 Commerce의 `OrderPaymentRequested` Outbox 저장·발행과 Payment의 Inbox 영속 인계입니다. 업무 유즈케이스는 자기 트랜잭션에서 `OrderEventOutbox.appendPaymentRequested`를 호출해야 하며, 트랜잭션 밖 단독 저장은 거절됩니다. 실제 전송은 `OutboxRelay`가 저장된 topic·key·원문 bytes로 수행하며 직접 발행 경로는 두지 않습니다. 발행 경로의 선택 이유와 CDC 대안은 [Kafka 결정](decisions/0005-kafka-event-delivery-and-capacity.md#발행-경로-통일)을 따릅니다. 최초 직렬화한 bytes를 보존해 재발행하고, Kafka ACK 뒤에만 발행 위치와 완료 상태를 저장합니다. 주문 API·예약과 실제 결제 접수 로직은 아직 연결하지 않았습니다.

Payment의 `AcceptOrderPaymentRequestService`는 Inbox의 `WAITING`·`next_run_at`에 원본과 메시지 처리 대기 작업을 저장·커밋한 뒤 반환합니다. 같은 ID·동일 bytes는 원래 상태를 유지하고 다른 bytes는 `inbox_conflicts`에 격리합니다. 별도 `inbox_jobs`나 가짜 paymentId의 금융 Job을 만들지 않습니다. 대기 행은 Worker의 `ApplyOrderPaymentRequestService`가 처리하며, 실패의 재시도·확인 대상 전환은 [데이터 모델](data-model.md#inbox)을 따릅니다. 수신 ACK는 이 대기 작업의 저장 완료만 뜻합니다.

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

수신은 메시지 종류·버전·key·필수 필드·타입을 검증하고 영속 인계가 성공한 뒤에만 ack합니다. 기본 설정은 처리 예외에 해당 Listener를 중단하며 실패한 offset을 건너뛰지 않습니다. 원인과 저장 결과를 확인한 뒤 재기동하면 마지막 커밋 위치에서 재전달됩니다.

Payment 원문 격리는 새 빈 DB의 초기 SQL을 검증한 뒤 `app.kafka.quarantine.enabled=true`로 켭니다. 기본값은 false입니다. 해석 불가·미지원 입력의 원문과 DLT 작업을 함께 커밋한 뒤 ACK하며, DB 저장 실패는 ACK 없이 Listener를 중단합니다. 같은 eventId의 본문 충돌은 기존 `inbox_conflicts`에서 확인합니다.

`QuarantineRelay`는 한 번에 하나만 임대하고 전용 실행기에서 Kafka 응답을 기다립니다. 공통 예약 스레드와 DB 트랜잭션을 점유하지 않으며 중복 신호는 합칩니다. 기본 주기는 1초, 주기당 최대 32개, 임대 240초, 결과 대기 125초입니다. `max.block.ms`와 결과 대기의 합은 임대보다 짧아야 합니다. 발행 실패는 1초부터 최대 60초 간격으로 10회까지 재시도하고, 소진하면 원문과 작업을 REVIEW_REQUIRED로 보존합니다.

원인 수정 뒤 인증된 운영 호출자는 `QuarantineService.requestReplay(id,expectedVersion,operator,reasonCode)`로 원래 토픽·키·본문을 재발행합니다. 예상 버전과 활성 재처리 부재를 검사하고 요청자·사유를 기록합니다. REVIEW_REQUIRED 작업은 `resumeDelivery(deliveryId,expectedVersion,operator,reasonCode)`로 상태·버전을 확인하고 이전 시도 수와 재개 이력을 보존하며 재개합니다. DLT 가용성과 독립적으로 요청할 수 있고, 발행 완료가 업무 성공을 뜻하지는 않습니다. 공개 관리 UI·HTTP API는 제공하지 않습니다.

Settlement 원문 격리도 같은 테이블 구조와 `app.kafka.quarantine.enabled`(기본 false)를 사용합니다. `PaymentConfirmed`·`PurchaseConfirmed`의 해석 실패만 격리하고, 반영 중 DB 오류와 격리 저장 실패는 ACK 없이 Listener를 중단합니다. Settlement `QuarantineRelay`는 전용 실행기 없이 공통 예약 스레드(4개)에서 Kafka 응답을 기다리며 주기·묶음·임대·결과 대기·재시도 규칙은 Payment와 같습니다. 대상 토픽은 `commerce-settle.settlement.v1.dlt`입니다. 운영 재처리·재개와 격리 지표는 아직 없습니다.

### DLT 관측과 조치

격리 기능을 켠 Payment는 15초마다 자기 DB를 전용 실행기에서 관측하고 `/actuator/prometheus`로 마지막 성공 표본을 노출합니다. SQL 제한 시간은 2초이며 scrape와 공통 예약 스레드에서 DB를 조회하지 않습니다. 원문·키·업무 ID는 지표 label에 넣지 않습니다.

Grafana의 `commerce-settle / Payment quarantine`은 최근 5분 신규 격리, DLT/REPLAY 상태별 작업·도래 후 최장 대기, 수집 성공과 표본 나이를 보여줍니다. 미래 재시도와 유효 임대는 지연에서 제외합니다. 같은 논리 작업은 인스턴스 간 합산하지 않으며 수집 실패·45초 이상 지난 표본은 상태 집계에서 제외합니다. 수집 없음을 0건으로 해석하지 않습니다.

Prometheus는 신규 격리·재시도 소진을 CRITICAL, 도래 후 5분/15분 지연을 WARNING/CRITICAL로 Alertmanager에 전달합니다. DB 관측 실패·오래된 표본과 Payment 관측 대상 중단도 경보합니다. 신규 격리는 DB 기록 시각 기준 최근 5분 창이며 창 종료로 알람이 해제돼도 업무 복구 완료를 뜻하지 않습니다. 상세 기준은 [운영 알람](policies.md#운영-알람)을 따릅니다.

1. 신규 격리는 원본 위치·오류 코드를 확인하고 생산자/스키마 원인을 조사합니다. 원문을 Slack·일반 로그에 붙이지 않습니다.
2. 발행 지연·REVIEW_REQUIRED는 Kafka 가용성, 발행 작업 상태·시도 수·임대를 확인합니다. DLT가 비어 있어도 DB 발행 대기를 확인합니다.
3. 원인 수정 뒤 위의 `requestReplay` 또는 `resumeDelivery`로 재처리하며 발행 완료와 실제 Inbox·업무 결과를 따로 대조합니다. 인증된 운영 호출 수단과 Inbox 업무 실패의 수동 재개는 별도 구현 대상입니다.

기본 수신처는 Alertmanager 화면입니다. 팀 Incoming Webhook을 연결할 때만 비밀 URL을 저장소 밖 파일에 두고 다음 overlay를 사용합니다. 해당 Webhook의 채널이 수신처이며 실제 채널로 시험 메시지를 자동 발송하지 않습니다.

```bash
SLACK_WEBHOOK_URL_FILE=/path/to/slack-webhook-url \
docker compose -f compose.yml -f compose.alertmanager-slack.yml --profile observability up -d --wait --wait-timeout 300
```

`python3 scripts/test_observability.py`는 규칙 경계·중복/오래된 표본과 실제 Prometheus→Alertmanager→전용 시험 수신기의 발생·복구 전달, WARNING 억제, 대시보드 등록을 검사합니다. 시험 알림 주기만 짧게 하고 실제 Slack은 사용하지 않습니다. 전용 Compose 프로젝트·임시 설정·컨테이너·볼륨은 종료 때 제거하며 기본 CI에도 포함합니다. 이 검사는 Kafka lag·금융 Inbox/Outbox/Job·전체 저장소 관측이나 신규 접수 gate를 검증하지 않습니다.

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

아래는 [Redis 세션 정책](policies.md#인증공통-api-기준)을 구현할 때의 검증·운영 기준입니다. 현재는 앱별 Redis ACL, Gateway의 세션 검증, 커머스의 로그인 세션 발급·로그아웃·로그인/복구 요청 제한, 비밀번호 재설정의 가드 차단·확정이 있습니다. `init_env.py`가 인증 세대 `AUTH_SESSION_EPOCH`, 제한 키용 `AUTH_RATE_LIMIT_HMAC_KEY`, 복구 토큰 전달 암호문용 `AUTH_RESET_DELIVERY_KEY`를 생성합니다. 세대를 바꾸면 기존 세션이 모두 무효가 되고, HMAC 키를 바꾸면 진행 중인 제한 시간창이 초기화되며, 전달 키를 바꾸면 아직 전달하지 않은 복구 토큰을 열 수 없습니다. 미확정 인증 변경의 재조정, 실행 식별자 설정과 차단·재개 기능은 아직 없습니다. smoke는 판매자 가입·로그인·Gateway 경유 조회·로그아웃 뒤 401, 계정 존재 여부와 관계없이 같은 복구 요청 응답, 쓸 수 없는 복구 토큰의 401과, Redis에 직접 넣은 세션의 역할 403·변경 중 가드 503, DB에 직접 넣은 구매자 계정과 같은 방식의 세션으로 주소 등록·재요청·목록·삭제를 확인합니다. 폐기·복구 흐름의 검증 완료로 간주하지 않습니다.

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
