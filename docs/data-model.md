# 데이터 모델

서비스별 PostgreSQL과 [Redis 인증 데이터](#redis-인증-데이터)의 설계입니다. PostgreSQL의 물리 FK는 만들지 않고 아래 관계를 애플리케이션에서 검증합니다. 공통 시간 컬럼은 [시간 정보와 감사 기록](architecture.md#시간-정보와-감사-기록)에 따라 채웁니다. 생성/수정 시각과 행위 감사 로그는 별개입니다. 식별자는 서버 생성 UUID, 금액·누적 수량은 bigint, PostgreSQL 시각은 `timestamptz`이며 조회 시간대는 KST입니다. 내부 저장은 같은 절대시각을 유지합니다. `?`는 nullable, 나머지 컬럼은 NOT NULL입니다. 숫자 버전은 0부터 시작하고 조건부 갱신마다 증가합니다.

이 문서는 테이블·컬럼·제약·인덱스의 목표 스키마를 정의합니다. 실제 DDL과 환경별 생성·변경 절차는 [DB 정책](migrations.md)을 따르며, 명세의 존재를 테이블 구현 완료로 간주하지 않습니다. 현재 커머스의 `products`·`product_inventory`·`inventory_movements`·`inventory_reservations`·`idempotency_records`·`buyer_accounts`·`seller_accounts`·`password_reset_tokens`·`audit_logs`는 [commerce 초기 SQL](../infra/postgres/schema/commerce.sql)에, 정산의 `settlement_items`·`settlement_batches`·`payouts`·`payout_jobs`·`settlement_reservations`와 Spring Batch 메타데이터는 [settlement 초기 SQL](../infra/postgres/schema/settlement.sql)에, 금융 Mock의 `mock_accounts`·`mock_scenarios`·`mock_transactions`·`mock_funds_reservations`·`mock_cash_entries`·`idempotency_records`는 [finance_mock 초기 SQL](../infra/postgres/schema/finance_mock.sql)에 반영했습니다. 금융 Mock은 지급 대역 동작만 구현했으며 PG·간편결제 승인·환불·입금 컬럼은 아직 쓰지 않습니다.

일반 주문의 `orders`·`order_addresses`도 commerce 초기 SQL과 도메인·저장소에 반영했습니다. 최초 주문 금액·결제 구성·배송 정책과 암호화 주소 사본을 저장하는 기반이며, 정식 주문 API·재고/쿠폰 예약·Outbox와 결제 결과 처리는 아직 연결하지 않았습니다. 저장·조회 범위와 검증은 [주문 영속 저장 기반](development.md#주문-영속-저장-기반)을 따릅니다.

아래 컬럼·제약·인덱스가 기준이고 ERD는 주요 필드와 관계를 요약합니다. 각 뷰는 같은 DB 안의 관계를 보여주며 외부 서비스 ID는 값으로만 저장합니다. 주소·연락처는 별도 암호화 사본이고 금융 이벤트에 포함하지 않습니다.

소유 표기의 `commerce`는 커머스 앱과 그 앱이 소유한 DB·계정·스키마 이름입니다. 판매자 계정·컬럼의 `seller`는 업무 식별자입니다.

## 계정·상품

![계정·상품 ERD](diagrams/erd-accounts.svg)

### buyer_accounts

소유: `commerce`. 구매자 계정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `email_normalized` | `varchar(254)` | 업무 값 |
| `password_hash` | `varchar(255)` | 업무 값 |
| `display_name` | `varchar(80)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `auth_version` | `bigint` | 인증 버전; 기본 0 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** UNIQUE(email_normalized), 삭제 뒤에도 유지. 이메일은 앞뒤 공백을 지우고 소문자로 맞춰 저장하며 로그인·요청 제한 키도 같은 값을 쓴다. status=ACTIVE/SUSPENDED/DELETED. auth_version은 인증에 영향 주는 계정 상태·비밀번호 변경과 가드 복구 때 증가하며 일반 엔티티 version과 구분한다.

**인덱스:** (status,id).

### password_reset_tokens

소유: `commerce`. PostgreSQL의 일회용 비밀번호 복구 토큰. 로그인 세션은 Redis에 저장한다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `actor_kind` | `varchar(16)` | 업무 값 |
| `actor_id` | `uuid` | 업무 값 |
| `purpose` | `varchar(16)` | 업무 값 |
| `token_hash` | `char(64)` | 업무 값 |
| `expires_at` | `timestamptz` | 업무 값 |
| `consumed_at` | `timestamptz?` | 업무 값 |
| `revoked_at` | `timestamptz?` | 업무 값 |
| `delivery_ciphertext` | `bytea?` | 복구전달용암호문 |
| `delivery_status` | `varchar(16)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(token_hash). actor_kind=BUYER/SELLER, purpose=PASSWORD_RESET. 목적·종류·10분 만료·소비/폐기를 DB에서 검사한다. 발급은 계정 잠금 아래 이전 복구 토큰을 폐기한다. 비밀번호 교체·토큰 소비·다른 복구 토큰 폐기·auth_version 증가는 같은 DB 트랜잭션이며 Redis 세션 폐기는 [정책의 차단·재조정](policies.md#폐기와-저장소-복구)을 따른다. 토큰 원문은 저장하지 않고, 토큰 ID에 묶은 AES-GCM 전달용 암호문을 delivery_status=PENDING으로 둔다. 실제 전달과 이후 상태는 알림 전달 구현 때 정한다.

**인덱스:** (actor_kind,actor_id,purpose); (expires_at).

### seller_accounts

소유: `commerce`. 판매자 계정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `email_normalized` | `varchar(254)` | 업무 값 |
| `password_hash` | `varchar(255)` | 업무 값 |
| `display_name` | `varchar(80)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `auth_version` | `bigint` | 인증 버전; 기본 0 |
| `payout_account_ref` | `varchar(100)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** UNIQUE(email_normalized), 구매자 계정과 별도·같은 정규화. status=ACTIVE/SUSPENDED/DELETED. 지급 계정은 시험용 참조. auth_version은 인증에 영향 주는 계정 상태·비밀번호 변경과 가드 복구 때 증가하며 일반 엔티티 version과 구분한다.

**인덱스:** (status,id).

### buyer_addresses

소유: `commerce`. 구매자 주소록.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `buyer_id` | `uuid` | →buyer_accounts.id |
| `recipient_ciphertext` | `bytea` | 업무 값 |
| `phone_ciphertext` | `bytea` | 업무 값 |
| `address_ciphertext` | `bytea` | 업무 값 |
| `is_default` | `boolean` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** 부분 UNIQUE(buyer_id) WHERE is_default AND deleted_at IS NULL. CHECK(삭제한 주소는 기본 아님). 세 암호문은 AES-GCM이며 주소 ID·칸 이름에 묶습니다. 주소록 쓰기는 구매자 계정 행을 잠가 직렬화하며 없거나 삭제된 계정은 인증 실패입니다. 타인 주소 접근 금지.

**인덱스:** (buyer_id,deleted_at,created_at,id).

### product_inventory

소유: `commerce`. 재고.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `product_id` | `uuid` | →products.id |
| `available_qty` | `bigint` | 업무 값 |
| `reserved_qty` | `bigint` | 업무 값 |
| `event_allocated_qty` | `bigint` | 업무 값 |
| `sold_qty` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(product_id). 모든 수량>=0. 이벤트 공개·일반 주문·상품 변경·재고 조정은 이 행을 products보다 먼저 잠금([트랜잭션과 관계](#트랜잭션과-관계)). `sold_qty`는 환불·반품 복원을 뺀 순판매량이며 누적 판매량은 inventory_movements에서 계산. 수량별 증감은 [재고 예약과 복원](policies.md#재고-예약과-복원)을 따름.

**인덱스:** UNIQUE(product_id).

### products

소유: `commerce`. 상품.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `seller_id` | `uuid` | →seller_accounts.id |
| `name` | `varchar(120)` | 업무 값 |
| `unit_price` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** CHECK(1<=unit_price AND unit_price<=10000000). status=DRAFT/ON_SALE/STOPPED/DELETED.

**인덱스:** (seller_id,status,created_at,id); (status,created_at,id).

### inventory_movements

소유: `commerce`. 재고 변경 이력.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `product_id` | `uuid` | →products.id |
| `operation` | `varchar(32)` | 업무 값 |
| `business_ref` | `uuid` | 업무 값 |
| `available_delta` | `bigint` | 업무 값 |
| `reserved_delta` | `bigint` | 업무 값 |
| `allocated_delta` | `bigint` | 업무 값 |
| `sold_delta` | `bigint` | 업무 값 |
| `reason` | `varchar(200)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(product_id,operation,business_ref). 증감과 현재 재고를 같은 트랜잭션으로 기록. 재전달·재처리는 이 유일성으로 한 번만 반영.

| operation | business_ref |
| --- | --- |
| `SELLER_ADJUSTMENT` | 판매자 조정 요청의 멱등 기록 ID |
| `ORDER_RESERVE`, `ORDER_CONSUME`, `ORDER_RELEASE` | 예약의 결제 회차 `cycle_id`. 재시도마다 새 값 |
| `REFUND_RESTORE` | 미발송 주문 복구의 원환불 `refundId` |
| `RETURN_RESTORE` | 반품 입고한 `order_refund_requests.id` |
| `EVENT_ALLOCATE`, `EVENT_RETURN` | `sale_events.id` |

선착순 주문의 확정과 이벤트 종료 뒤 해제는 재고 행이 바뀌므로 `ORDER_CONSUME`·`ORDER_RELEASE`로 기록합니다. 선착순 예약과 이벤트 진행 중 해제는 이벤트의 `R`만 바뀌므로 이 이력을 남기지 않고 event_participations가 참여 기록을 맡습니다.

**인덱스:** (product_id,created_at,id).

## Redis 인증 데이터

소유: 커머스 인증 영역. Gateway는 읽기 전용으로 검증하며 [세션 정책](policies.md#인증공통-api-기준)을 따른다. 아래 구조는 PostgreSQL 테이블이 아니며 시간은 UTC epoch 밀리초 정수, 인증 버전은 0 이상의 정수다. 토큰 원문은 저장하지 않는다. 검증과 조건부 변경은 [Lua 스크립트](decisions/0003-redis-sessions.md#원자-검증에-lua-스크립트를-쓰는-이유)로 원자 실행한다.

### 로그인 세션

키는 `commerce-settle:auth:session:<epoch>:<tokenHash>`다. epoch는 배포한 인증 세대 UUID의 소문자 표기, tokenHash는 256비트 이상 임의 토큰(UTF-8)의 SHA-256 소문자 16진수다. 로그아웃의 `X-Authenticated-Session-Hash`도 같은 값이다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `actorKind` | `string` | BUYER/SELLER |
| `actorId` | `uuid` | 해당 종류의 PostgreSQL 계정 ID |
| `purpose` | `string` | SESSION |
| `authVersion` | `integer` | 발급 당시 계정의 auth_version |
| `issuedAt` | `integer` | 서버 발급 시각(epoch ms) |
| `expiresAt` | `integer` | issuedAt+30분(epoch ms); 변경 불가 |

**제약:** 발급 때 `PEXPIREAT`으로 키 만료를 expiresAt에 맞추고 조회로 연장하지 않는다. Gateway의 읽기 전용 스크립트가 현재 세대·목적·만료와 아래 ACTIVE 가드의 인증 버전을 원자적으로 검증하며, 만료는 Redis `TIME`과 expiresAt을 비교해 한 번 더 확인한다. 가드 키는 스크립트가 세션의 actorKind·actorId로 만들며 단일 노드를 전제로 한다. 세션 또는 가드 누락 시 허용하지 않으며 PostgreSQL에서 세션을 재생성하지 않는다.

### 계정별 인증 가드

키는 `commerce-settle:auth:subject:<epoch>:<actorKind>:<actorId>`다. 개별 세션 목록을 스캔하지 않고 해당 계정 종류·ID의 전체 세션을 차단한다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `authVersion` | `integer` | PostgreSQL에서 확정한 auth_version |
| `state` | `string` | ACTIVE/BLOCKED; 정지·삭제·변경 중이면 BLOCKED |
| `pendingChangeId` | `uuid?` | 진행 중인 인증 변경의 동일성·재조정 기준; 확정 반영 뒤 null |

**제약:** 가드는 현재 세대에서 TTL로 만료시키지 않는다. 최초 로그인에서 DB 계정 상태·인증 버전을 확인해 없는 가드를 조건부 생성할 수 있으나, 기존 BLOCKED 가드를 로그인으로 열지 않는다. 폐기는 가드의 상태·인증 버전·pendingChangeId를 조건부 변경해 직렬화한다. 세션 발급·가드 선차단·확정 반영은 커머스의 스크립트가 조건을 모두 확인한 뒤 마지막에 쓴다. DB 확정 상태 반영은 같은 변경만 허용하고 이전 버전의 지연 반영을 거절한다. 확정 정지/삭제 가드는 BLOCKED·pendingChangeId=null이며 401, 변경 중 가드·유효 세션의 가드 누락·Redis 검증 오류·복구 중 요청은 503이다. 재접속 때 이전 세션 세대를 계속 사용하지 않는다.

## 주문·선착순 이벤트

![주문·선착순 이벤트 ERD](diagrams/erd-orders.svg)

### sale_events

소유: `commerce`. 선착순 이벤트.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `seller_id` | `uuid` | →seller_accounts.id |
| `product_id` | `uuid` | →products.id |
| `name` | `varchar(80)` | 업무 값 |
| `event_price` | `bigint` | 업무 값 |
| `quota` | `bigint` | 업무 값 |
| `sold_count` | `bigint` | 업무 값 |
| `reserved_count` | `bigint` | 업무 값 |
| `starts_at` | `timestamptz` | 업무 값 |
| `ends_at` | `timestamptz` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `published_at` | `timestamptz?` | 업무 값 |
| `closed_at` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** name 1..80자, quota>0, event_price 1..10000000, 0<=S+R<=Q, starts_at<ends_at. status=DRAFT/SCHEDULED/OPEN/SOLD_OUT/CLOSED. 공개 후 사본 불변, 상품별 공개 기간 중복 검사는 product_inventory 잠금 아래 수행.

**인덱스:** (product_id,starts_at,ends_at); (status,starts_at,ends_at); (seller_id,created_at,id).

### event_participations

소유: `commerce`. 이벤트 참여.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `event_id` | `uuid` | →sale_events.id |
| `buyer_id` | `uuid` | 업무 값 |
| `order_id` | `uuid` | →orders.id |
| `status` | `varchar(24)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_id). 부분 UNIQUE(event_id,buyer_id) WHERE status IN (RESERVED,CONFIRMED,REFUNDED). 실패/안전 취소=RELEASED만 재참여 가능.

**인덱스:** (event_id,status); (buyer_id,created_at,id).

### orders

소유: `commerce`. 주문.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `buyer_id` | `uuid` | →buyer_accounts.id |
| `seller_id` | `uuid` | →seller_accounts.id |
| `product_id` | `uuid` | →products.id |
| `event_id` | `uuid?` | →sale_events.id |
| `buyer_coupon_id` | `uuid?` | →buyer_coupons.id |
| `payment_id` | `uuid?` | 외부Payment참조 |
| `product_name` | `varchar(120)` | 업무 값 |
| `unit_price` | `bigint` | 업무 값 |
| `quantity` | `integer` | 업무 값 |
| `gross_amount` | `bigint` | 업무 값 |
| `coupon_amount` | `bigint` | S+P; 표시용 총할인액 |
| `seller_coupon_amount` | `bigint` | S; 판매자 부담 할인 |
| `platform_coupon_amount` | `bigint` | P; 플랫폼 부담 지원 |
| `settlement_base_amount` | `bigint` | B=G−S; 수수료·정산 기준 |
| `total_amount` | `bigint` | 업무 값 |
| `mileage_amount` | `bigint` | 업무 값 |
| `external_amount` | `bigint` | 업무 값 |
| `payment_cycle_id` | `uuid` | 업무 값 |
| `external_method` | `varchar(24)?` | 업무 값 |
| `provider_id` | `varchar(16)?` | 업무 값 |
| `method_account_ref` | `varchar(100)?` | 시험용 |
| `status` | `varchar(32)` | 업무 값 |
| `fulfillment_status` | `varchar(24)` | UNSHIPPED/SHIPPED/DELIVERED/RETURN_RECEIVED |
| `simulation_seed` | `bigint` | 주문별 배송 소요일을 정한 시험 seed |
| `delivery_business_days` | `integer` | 생성 때 고정한 2 또는 3 |
| `delivery_calendar_version` | `varchar(32)` | 불변 시험 달력 버전 |
| `delivery_calendar` | `jsonb` | 시간대·공휴일 목록 사본 |
| `shipped_at` | `timestamptz?` | 최초 서버 발송 시각 |
| `delivery_due_at` | `timestamptz?` | 발송 당시 계산한 모의 도착 시각 |
| `delivered_at` | `timestamptz?` | 배송완료 반영 시 delivery_due_at을 복사 |
| `payment_confirmed_at` | `timestamptz?` | 업무 값 |
| `refund_deadline_at` | `timestamptz?` | 업무 값 |
| `purchase_confirmed_at` | `timestamptz?` | 업무 값 |
| `payment_wait_until` | `timestamptz` | 업무 값 |
| `policy_version` | `varchar(32)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** G=unit_price*quantity, D=S+P, B=G-S, T=B-P=E+M; 1<=T<=10000000, S/P>=0이고 최대 하나만 양수, 0<=D<G, 0<=M<=T, E>=0. buyer_coupon_id가 있으면 M=0. 쿠폰의 결제수단·소유권·예산 예약을 함께 검사. 일반 quantity 1..10; event_id가 있으면1. Payment 연결 ID는 알려진 경우 UNIQUE. 상태 전이는 아래 표.

배송 소요일·seed·달력 사본은 생성 후 불변입니다. 발송/도착 예정 시각은 함께 설정하고 발송 재요청으로 바꾸지 않습니다. 배송완료 시 delivered_at=delivery_due_at, refund_deadline_at=delivered_at+168시간을 함께 저장합니다. 배송 전 deadline은 NULL이며 진행 중 취소·반품·환불은 자동 구매확정을 막습니다. 늦은 배송 작업과 환불 접수도 같은 주문 잠금으로 기한을 검사합니다.

**인덱스:** (buyer_id,created_at,id); (seller_id,created_at,id); (fulfillment_status,delivery_due_at,id); (status,refund_deadline_at,id); (status,payment_wait_until,id); UNIQUE(payment_id) WHERE payment_id IS NOT NULL.

### order_refund_requests

소유: `commerce`. 취소·반품 요청.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `order_id` | `uuid` | →orders.id |
| `buyer_id` | `uuid` | 업무 값 |
| `seller_id` | `uuid` | 업무 값 |
| `kind` | `varchar(16)` | 업무 값 |
| `status` | `varchar(32)` | 업무 값 |
| `accepted_at` | `timestamptz` | 업무 값 |
| `reason` | `varchar(200)` | 업무 값 |
| `refund_id` | `uuid?` | 외부Payment참조 |
| `decision_at` | `timestamptz?` | 업무 값 |
| `received_at` | `timestamptz?` | 업무 값 |
| `closed_reason` | `varchar(200)?` | 업무 값 |
| `closure_evidence_ref` | `varchar(200)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** 부분 UNIQUE(order_id) WHERE status IN (PENDING_SELLER,APPROVED,REFUNDING,COMPLETED,FAILED_REVIEW). status=PENDING_SELLER/APPROVED/REFUNDING/COMPLETED/REJECTED/WITHDRAWN/FAILED_REVIEW/CLOSED. 재고 재입고는 received_at과 원복 유일성으로 보호.

**인덱스:** (order_id,created_at,id); (seller_id,status,created_at,id); UNIQUE(refund_id) WHERE refund_id IS NOT NULL.

### inventory_reservations

소유: `commerce`. 주문 재고 예약.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `order_id` | `uuid` | →orders.id |
| `product_id` | `uuid` | →products.id |
| `event_id` | `uuid?` | →sale_events.id |
| `quantity` | `integer` | 업무 값 |
| `cycle_id` | `uuid` | 현재 예약을 확보한 결제 회차; orders.payment_cycle_id와 일치 |
| `status` | `varchar(24)` | 업무 값 |
| `expires_at` | `timestamptz` | 주문의 payment_wait_until; 취소 요청 대상 조회에만 사용 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_id). quantity>0. status=RESERVED/CONSUMED/RELEASED/RESTORED. 기한만으로 RELEASED 전이 금지.

| 전이 | 근거 | 조건 |
| --- | --- | --- |
| →RESERVED | 주문 생성 | 가용 재고 또는 이벤트 남은 수량·참여 한도 충족 |
| RESERVED→CONSUMED | 결제 확정 | 결과의 결제 회차가 `cycle_id`와 같음 |
| RESERVED→RELEASED | 확정 거절·승인 차단된 취소 | 결과의 결제 회차가 `cycle_id`와 같음 |
| RELEASED→RESERVED | 확정 거절 뒤 재시도 | 새 회차로 수량을 다시 확보하고 `cycle_id` 교체 |
| CONSUMED→RESTORED | 미발송 주문의 환불 복구 또는 발송 주문의 반품 입고 | 주문당 한 번 |

재시도도 같은 행을 갱신하므로 주문당 예약 행은 하나입니다. 회차가 다른 늦은 결과와 이미 반영된 결과는 상태를 바꾸지 않습니다. 금지된 전이는 수량·이력을 바꾸지 않고 거절합니다. 수량 증감과 복원 시점은 [재고 예약과 복원](policies.md#재고-예약과-복원)을 따릅니다.

**인덱스:** (status,expires_at,id); (product_id,status).

### order_addresses

소유: `commerce`. 주문 주소 사본.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `order_id` | `uuid` | →orders.id |
| `recipient_ciphertext` | `bytea` | 업무 값 |
| `phone_ciphertext` | `bytea` | 업무 값 |
| `address_ciphertext` | `bytea` | 업무 값 |
| `masked_at` | `timestamptz?` | 업무 값 |
| `hold_reason` | `varchar(200)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_id). 주문 생성 후 사본 불변, 정한 마스킹/보존 보류만 허용.

**인덱스:** UNIQUE(order_id).

## 쿠폰

![쿠폰 ERD](diagrams/erd-coupons.svg)

### coupon_templates

소유: `commerce`. 발급 당시 혜택 조건과 할인 비용 집계. WELCOME는 가입 시, GRADE는 월 배치가 서버 규칙으로 자동 생성합니다. 판매자 API는 SELLER 템플릿만 생성합니다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `kind` | `varchar(16)` | WELCOME/GRADE/SELLER |
| `funder` | `varchar(16)` | PLATFORM/SELLER; 종류에서 서버가 결정 |
| `seller_id` | `uuid?` | SELLER에만 필요 |
| `product_id` | `uuid?` | SELLER의 지정 상품; null이면 판매자 전체 |
| `grade` | `varchar(16)?` | GRADE 선정자에게 적용할 BRONZE/SILVER/GOLD 혜택 |
| `target_month` | `date?` | GRADE 혜택 월의 첫날; WELCOME/SELLER는 null |
| `name` | `varchar(80)` | 표시명 |
| `discount_amount` | `bigint` | 정액 할인 |
| `min_order_amount` | `bigint` | 적용 전 최소 상품대금 |
| `min_seller_net_amount` | `bigint` | SELLER가 승인한 최소 정산액; 플랫폼 캠페인은 0 |
| `allowed_methods` | `varchar(24)[]` | 승인된 CREDIT_CARD/DEBIT_CARD/EASY_PAY |
| `pg_cost_ceiling_bps` | `integer` | PG 비용 상한; 기본 400bp |
| `other_cost_allowance` | `bigint` | 주문당 기타 비용 충당액; 기본 1,000원 |
| `issuance_limit` | `integer?` | SELLER가 정한 발급 수 한도; 플랫폼 종류는 null |
| `issued_count` | `integer` | 누적 발급 수 |
| `total_budget` | `bigint?` | SELLER가 정한 명목 할인 예산; 플랫폼 종류는 null |
| `committed_budget` | `bigint` | 발급했으나 아직 사용하지 않은 약속액 |
| `consumed_budget` | `bigint` | 사용 누계에서 확정 환불 상쇄를 뺀 금액 |
| `valid_from` | `timestamptz` | 신규 발급 시작 |
| `valid_until` | `timestamptz?` | SELLER의 종료 시각, GRADE의 월말 경계; WELCOME는 null |
| `status` | `varchar(16)` | ACTIVE/CLOSED |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | 낙관적 버전 |

**제약:** 할인액>0, 할인액<min_order_amount<=10000000. issued_count·committed_budget·consumed_budget는 비음수입니다. SELLER는 양수인 issuance_limit·total_budget와 valid_until이 필수이며 issued_count<=issuance_limit, committed_budget+consumed_budget<=total_budget를 보호합니다. WELCOME/GRADE는 두 상한이 null이고 비용 집계만 수행합니다. WELCOME/GRADE는 funder=PLATFORM이고 seller_id/product_id는 null; SELLER는 funder=SELLER이고 seller_id 필수입니다. grade와 target_month는 GRADE에만 필요하고 나머지 종류는 null입니다. WELCOME의 발급 기간은 종료 없이 유지하고 쿠폰 기한은 가입+14일로 정합니다. GRADE의 기간은 혜택 월입니다. 상품 소유·사용 수단·손익 하한·최소 정산액은 서버가 검증합니다. 발급 뒤 혜택 조건은 변경하지 않습니다.

**인덱스:** (seller_id,status,created_at,id); (kind,target_month,status). 활성 WELCOME 캠페인은 전체 한 개, GRADE는 (grade,target_month)에 부분 UNIQUE. SELLER의 월별 UNIQUE는 두지 않습니다.

### buyer_coupons

소유: `commerce`. 발급 당시 조건과 예산 약속을 보존한 쿠폰.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `template_id` | `uuid` | →coupon_templates.id |
| `buyer_id` | `uuid` | →buyer_accounts.id |
| `kind` | `varchar(16)` | WELCOME/GRADE/SELLER |
| `funder` | `varchar(16)` | PLATFORM/SELLER |
| `seller_id` | `uuid?` | SELLER에만 값 |
| `product_id` | `uuid?` | SELLER 지정 상품 |
| `target_month` | `date?` | GRADE의 발급 업무 키; WELCOME/SELLER는 null |
| `discount_amount` | `bigint` | 원할인 사본 |
| `min_order_amount` | `bigint` | 최소 상품대금 사본 |
| `allowed_methods` | `varchar(24)[]` | 사용 수단 사본 |
| `valid_from` | `timestamptz` | 사용 시작 |
| `expires_at` | `timestamptz` | WELCOME=가입+14일, GRADE=다음 달 1일 00:00 KST, SELLER=템플릿 종료 |
| `status` | `varchar(16)` | ISSUED/RESERVED/USED/EXPIRED |
| `reserved_order_id` | `uuid?` | →orders.id |
| `used_order_id` | `uuid?` | →orders.id |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | 낙관적 버전 |

**제약:** UNIQUE(template_id,buyer_id). WELCOME는 buyer_id, GRADE는 (buyer_id,target_month)에 종류별 부분 UNIQUE. SELLER는 캠페인별 유일성만 적용하고 다른 캠페인의 쿠폰도 발급할 수 있습니다. 발급 수 증가·할인 약속액 반영·쿠폰 저장은 같은 트랜잭션입니다. 판매자 상한은 SELLER 발급에만 검사합니다. 원할인·부담자·기간·수단은 불변이며 상태와 시각을 함께 검사합니다. 재가입·캠페인 교체로 발급 이력을 지우지 않습니다.

**인덱스:** (buyer_id,status,expires_at,id); UNIQUE(reserved_order_id) WHERE NOT NULL; UNIQUE(used_order_id) WHERE NOT NULL.

### coupon_history

소유: `commerce`. 발급·예약·사용·복구·만료와 예산 이동 이력.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `buyer_coupon_id` | `uuid` | →buyer_coupons.id |
| `order_id` | `uuid?` | 발급·미사용 만료는 null |
| `operation` | `varchar(16)` | ISSUE/RESERVE/USE/RELEASE/RESTORE/EXPIRE |
| `business_ref` | `uuid` | 발급 대상·결제 회차·원결제·만료의 업무 키 |
| `from_status` | `varchar(16)?` | ISSUE는 null |
| `to_status` | `varchar(16)` | 전이 결과 |
| `committed_delta` | `bigint` | 약속액 증감 |
| `consumed_delta` | `bigint` | 사용액 증감 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(buyer_coupon_id,operation,business_ref). RESERVE/RELEASE는 payment_cycle_id별, USE/RESTORE는 원결제별 한 번. 쿠폰 상태·할인 비용 집계·이력을 같은 트랜잭션으로 반영하고 미확정 결과로 약속액을 해제하지 않습니다.

**인덱스:** (buyer_coupon_id,created_at,id).

### coupon_issue_runs

소유: `commerce`. 플랫폼 월간 우수고객의 대상 고정과 재시작 가능한 발급 회차. 판매자 자유 발급은 포함하지 않습니다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; Job의 runId |
| `target_month` | `date` | 혜택 월 첫날; 취소되지 않은 회차만 월별 UNIQUE |
| `cutoff_at` | `timestamptz` | 원래 월 마감; 대체 회차도 보존 |
| `policy_version` | `varchar(32)` | 최초 회차의 서버 정책 버전; 대체·재시작에서 보존 |
| `replaces_run_id` | `uuid?` | 이전 CANCELLED 회차의 논리 참조; 최초 회차는 null |
| `target_count` | `integer?` | 동점자 포함 선정 인원; 확정 전 null |
| `cutoff_spend` | `bigint?` | 선정 대상의 최저 E 합계; 확정 전·대상 없음은 null |
| `status` | `varchar(24)` | PREPARING/ISSUING/COMPLETED/EXPIRED/CANCELLED |
| `cancelled_at` | `timestamptz?` | 서버가 기록한 교정 시각 |
| `cancelled_by` | `varchar(100)?` | 검증한 운영자 자격의 식별자; 이메일 금지 |
| `cancellation_reason_code` | `varchar(80)?` | AGGREGATION_ERROR; 운영자 임의 대상·금액 지정 불가 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | 낙관적 버전 |

**제약:** target_month·cutoff_at·policy_version·status는 NOT NULL. CHECK(replaces_run_id <> id). UNIQUE(target_month) WHERE status <> 'CANCELLED'; UNIQUE(replaces_run_id) WHERE replaces_run_id IS NOT NULL. target_month는 월 첫날이고 target_count>=0, cutoff_spend>0이다. 세 취소 필드는 CANCELLED일 때 모두 필수이며 다른 상태에서는 모두 null이다. 대체 회차는 같은 월·마감·정책 버전의 CANCELLED 부모 한 개를 참조하며, 부모·자식 연결과 취소를 같은 트랜잭션에 저장한다. 참조·상태는 앱에서 확인하며 물리 FK는 두지 않는다. EXPIRED 또는 발급 이력이 있는 회차는 취소하지 않는다. 집계 오류의 발급0건 COMPLETED는 원래 기한 안에서만 같은 교정 조건을 적용한다.

Scheduler는 매월 1일 00:10 KST부터 생성·실행하고 미생성·미완료 회차를 10분마다 확인한다. 첫 Step은 PREPARING에서 같은 마감 원자료의 집계·대상 저장을 재개하고, RANK 동점자 전원·등급·템플릿·할인 합계·중복을 검증한 뒤 대상 수·경계 금액과 ISSUING을 확정한다. 대상이 없으면 target_count=0/cutoff_spend=null로 완료한다. 확정 뒤에는 대상을 덮어쓰지 않는다. 업무 상태가 ISSUING으로 커밋됐지만 첫 Step 완료 이력이 남지 않았다면 저장한 확정 결과를 확인해 재사용하고 재집계하지 않는다. 발급 Step은 고정한 PENDING만 처리한다. 예정 할인 총액은 대상별 할인액의 합이며 사전 예산 승인과 비교하지 않는다.

Job 식별자는 targetMonth+runId다. 정상 재시작은 같은 값을 사용하고 CANCELLED는 실행하지 않는다. 원자료나 저장한 정책 버전을 재구성할 수 없으면 현재 값으로 대체하지 않고 발급을 멈춘다. 원래 만료가 지나면 미발급 대상을 EXPIRED로 종결하며 새 회차로 연장하지 않는다.

교정·Job 실행 등록·대상 저장·발급 chunk는 회차 행을 먼저 잠그고 상태를 다시 확인한다. 교정은 원회차 PREPARING/ISSUING/COMPLETED, 활성 실행 없음·실행자 부재 확인, 발급 0건, 원래 발급 기한 전이라는 조건을 모두 확인한다. ISSUED 또는 buyer_coupon_id가 있는 대상·해당 대상의 ISSUE 이력이 있으면 거절하고, 이력의 정합성을 확인할 수 없으면 판정을 미룬다. 취소·대체 회차·감사·멱등 결과는 원자 저장한다. 취소가 먼저 커밋되면 이전 실행의 지연 저장을 거절하고 첫 발급이 먼저 커밋되면 취소를 거절한다. 원회차 대상과 이력은 보존한다. 호출·오류는 [운영 계약](api.md#월간-혜택-운영-조회와-오류-교정)을 따른다.

**인덱스:** (status,target_month,id); 조건부 월 유일성과 replaces_run_id 유일성은 위 제약으로 관리한다. Job 메타데이터와 업무 테이블의 스키마·제약은 첫 구현에서 함께 검증한다.

### coupon_issue_targets

소유: `commerce`. 같은 회차의 플랫폼 월간 우수고객과 처리 결과.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 발급 업무 키 |
| `run_id` | `uuid` | →coupon_issue_runs.id |
| `template_id` | `uuid` | →coupon_templates.id |
| `buyer_id` | `uuid` | →buyer_accounts.id |
| `external_spend` | `bigint` | 고정한 대상 기간의 E 합계 |
| `rank_no` | `integer` | E 합계 내림차순 RANK; 1~100, 동점은 같은 순위 |
| `buyer_coupon_id` | `uuid?` | 처리된 원쿠폰 |
| `status` | `varchar(24)` | PENDING/ISSUED/EXPIRED |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | 낙관적 버전 |

**제약:** UNIQUE(run_id,buyer_id), UNIQUE(buyer_coupon_id) WHERE buyer_coupon_id IS NOT NULL. template_id는 해당 월·등급의 GRADE 캠페인이며 external_spend>0, 1<=rank_no<=100입니다. 동점자 때문에 행 수가 100을 넘을 수 있습니다. 월별 마감의 불변 확정·취소 이력으로 대상과 합계를 고정합니다. 만료 뒤 재개는 새 쿠폰을 만들지 않고 EXPIRED로 종결합니다. 같은 대상 재시도는 원쿠폰을 재사용하며 PENDING 대상만 이어서 처리합니다. 쿠폰·발급 이력·대상 ISSUED 전이는 같은 DB 트랜잭션으로 반영합니다. 회차 행을 먼저 잠그고 ISSUING·원래 발급 기한을 확인하며 CANCELLED 부모의 대상은 처리하지 않습니다.

## 결제·마일리지

![결제·마일리지 ERD](diagrams/erd-payment.svg)

### mileage_wallets

소유: `payment`. 우리 마일리지 잔액.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `buyer_id` | `uuid` | 업무 값 |
| `balance` | `bigint` | 업무 값 |
| `reserved_amount` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(buyer_id). CHECK(0<=reserved_amount AND reserved_amount<=balance). 갱신은 행 잠금/조건부 갱신.

**인덱스:** UNIQUE(buyer_id).

### mileage_reservations

소유: `payment`. 마일리지 예약.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `wallet_id` | `uuid` | →mileage_wallets.id |
| `payment_id` | `uuid` | →payments.id |
| `cycle_id` | `uuid` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(payment_id), amount>0. status=RESERVED/CONSUMED/RELEASED. 재시도마다 새cycle_id, RESERVE/RELEASE business_ref는cycle_id; DEBIT/RESTORE business_ref는payment_id. 이력은mileage_entries로보존.

**인덱스:** (wallet_id,status); (status,updated_at,id).

### payments

소유: `payment`. 결제.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `order_id` | `uuid` | 외부커머스참조 |
| `buyer_id` | `uuid` | 업무 값 |
| `seller_id` | `uuid` | 업무 값 |
| `order_version` | `bigint` | 업무 값 |
| `product_id` | `uuid` | 업무 값 |
| `current_cycle_id` | `uuid` | 업무 값 |
| `gross_amount` | `bigint` | 업무 값 |
| `coupon_amount` | `bigint` | S+P; 표시용 총할인액 |
| `seller_coupon_amount` | `bigint` | S; 판매자 부담 할인 |
| `platform_coupon_amount` | `bigint` | P; 플랫폼 부담 지원 |
| `settlement_base_amount` | `bigint` | B=G−S; 수수료·정산 기준 |
| `total_amount` | `bigint` | 업무 값 |
| `mileage_amount` | `bigint` | 업무 값 |
| `external_amount` | `bigint` | 업무 값 |
| `fee_amount` | `bigint` | 업무 값 |
| `seller_net_amount` | `bigint` | 업무 값 |
| `status` | `varchar(32)` | 업무 값 |
| `confirmed_at` | `timestamptz?` | 업무 값 |
| `policy_version` | `varchar(32)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_id). T=E+M=B-P, fee=floor(B*15/100), seller_net=B-fee; D=S+P, B=G-S, 모두 비음수,T>=1. 쿠폰 사용 시 M=0. 같은 주문 재시도는 같은 결제의 새 attempt.

**인덱스:** (buyer_id,created_at,id); (status,updated_at,id).

### mileage_entries

소유: `payment`. 마일리지 원장.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `wallet_id` | `uuid` | →mileage_wallets.id |
| `payment_id` | `uuid?` | →payments.id |
| `refund_id` | `uuid?` | →refunds.id |
| `kind` | `varchar(16)` | 업무 값 |
| `business_ref` | `uuid` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `balance_after` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(wallet_id,kind,business_ref). kind=SEED/RESERVE/RELEASE/DEBIT/RESTORE. amount>0. DEBIT/RESTORE는 원결제별 각각 한 번(부분 UNIQUE).

**인덱스:** (wallet_id,created_at,id); UNIQUE(payment_id,kind) WHERE kind IN (DEBIT,RESTORE).

### refund_sagas

소유: `payment`. 환불 복구 진행.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `refund_id` | `uuid` | →refunds.id |
| `step` | `varchar(40)` | 업무 값 |
| `reservation_ref` | `uuid?` | 업무 값 |
| `ledger_reversed_at` | `timestamptz?` | 업무 값 |
| `order_restored_at` | `timestamptz?` | 업무 값 |
| `last_error_code` | `varchar(80)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(refund_id). 금융 성공/마일리지 복원/원장 상쇄/주문 복구를 별도 추적. 완료 ack 전 전체 완료 금지.

**인덱스:** (step,updated_at,id).

### refunds

소유: `payment`. 금융 환불.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `payment_id` | `uuid` | →payments.id |
| `order_refund_request_id` | `uuid` | 외부커머스참조 |
| `external_amount` | `bigint` | 업무 값 |
| `mileage_amount` | `bigint` | 업무 값 |
| `status` | `varchar(32)` | 업무 값 |
| `business_key` | `uuid` | 업무 값 |
| `external_status` | `varchar(24)` | 업무 값 |
| `external_ref` | `uuid?` | 업무 값 |
| `mileage_restored_at` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_refund_request_id), UNIQUE(business_key). 부분 UNIQUE(payment_id) WHERE status IN (RESERVING,EXTERNAL_PENDING,RESTORING,INTERNAL_PENDING,COMPLETED,UNKNOWN,COMPENSATING). 원결제 E/M을 복사해 불변.

**인덱스:** (payment_id,created_at,id); (status,updated_at,id).

### payment_jobs

소유: `payment`. 결제·환불 작업.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `payment_id` | `uuid` | →payments.id |
| `refund_id` | `uuid?` | →refunds.id |
| `operation` | `varchar(32)` | 업무 값 |
| `business_key` | `uuid` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `attempts` | `integer` | 업무 값 |
| `next_run_at` | `timestamptz` | 업무 값 |
| `lease_owner` | `varchar(100)?` | 업무 값 |
| `lease_token` | `uuid?` | 금융 Job 임대마다 새 UUID; 오래된 결과 반영 차단 |
| `lease_until` | `timestamptz?` | 업무 값 |
| `last_error_code` | `varchar(80)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(operation,business_key). 작업 저장 뒤 메시지 ack. status=PENDING/RUNNING/RETRY/REVIEW_REQUIRED/DONE.

임대 소유자·token·만료는 함께 설정/해제하며 선점·회수 때 버전을 증가합니다. 결과 반영은 유효한 token·실행자·Job/금융 상태·회차·예상 버전을 같은 트랜잭션에서 검사합니다. 만료 RUNNING도 회수하며 원대역 조회부터 재개합니다. [금융 Worker 정책](policies.md#금융-worker-임대와-결과-반영)을 따릅니다.

**인덱스:** (status,next_run_at,lease_until,id).

### payment_attempts

소유: `payment`. 외부 승인 시도.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `payment_id` | `uuid` | →payments.id |
| `attempt_no` | `integer` | 업무 값 |
| `cycle_id` | `uuid` | 업무 값 |
| `provider_id` | `varchar(16)` | 업무 값 |
| `method` | `varchar(24)` | 업무 값 |
| `account_ref` | `varchar(100)` | 업무 값 |
| `funding_account_ref` | `varchar(100)` | 승인 시도 생성 때 서버 설정에서 고정한 PAYOUT 재원 계정 |
| `business_key` | `uuid` | 업무 값 |
| `external_ref` | `uuid?` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `scenario_version` | `varchar(32)` | 외부 승인 시도 생성 때 고정; 원키 재요청·원승인 환불에 유지 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(payment_id,attempt_no), UNIQUE(provider_id,business_key). 진행 중/성공 시도는 결제별1개(부분 UNIQUE). E>0인 경우만 생성. 상태=PENDING/PROCESSING/UNKNOWN/APPROVED/DECLINED/CANCELLED.

provider·method·account_ref·funding_account_ref·amount·scenario_version은 외부 호출 전 원본문의 사본으로 고정합니다. 호출 전 중단 뒤에도 이 값으로 승인 요청을 재구성하며 현재 서버 설정으로 재원·시나리오를 덮어쓰지 않습니다.

**인덱스:** (status,updated_at,id); UNIQUE(payment_id) WHERE status IN (PENDING,PROCESSING,UNKNOWN,APPROVED).

## 정산·지급

![정산·지급 ERD](diagrams/erd-settlement.svg)

### settlement_batches

소유: `settlement`. 정산 배치.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `seller_id` | `uuid` | 업무 값 |
| `batch_date` | `date` | Asia/Seoul 마감 날짜 |
| `cutoff_at` | `timestamptz` | 업무 값 |
| `policy_version` | `varchar(32)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `net_amount` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(seller_id,batch_date,policy_version); cutoff_at은 해당 batch_date의 10:00 Asia/Seoul. net_amount>=0, status=CREATED/RESERVED/PROCESSING/COMPLETED/REVIEW_REQUIRED.

**인덱스:** (status,created_at,id).

### payouts

소유: `settlement`. 지급.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `batch_id` | `uuid` | →settlement_batches.id |
| `seller_id` | `uuid` | 업무 값 |
| `recipient_account_ref` | `varchar(100)` | 업무 값 |
| `recipient_account_version` | `bigint` | 업무 값 |
| `funding_account_ref` | `varchar(100)` | 업무 값 |
| `scenario_version` | `varchar(32)` | 지급 생성 때 고정한 금융 실행 시나리오 버전 |
| `succeeded_at` | `timestamptz?` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `business_key` | `uuid` | 업무 값 |
| `external_ref` | `uuid?` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `hold_reason` | `varchar(32)?` | UNKNOWN/CONFIRMED_REJECTION |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(batch_id), UNIQUE(business_key), amount>0. 접수/외부 성공 구분. 수취/출금 계정 사본 불변. SUCCEEDED이면 succeeded_at 필수; 원대역의 최초 성공 시각을 복사해 재조회에도 유지.

**인덱스:** (seller_id,created_at,id); (status,updated_at,id).

### settlement_items

소유: `settlement`. 거래별 정산 항목.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `payment_id` | `uuid` | 업무 값 |
| `order_id` | `uuid` | 업무 값 |
| `product_id` | `uuid` | 업무 값 |
| `seller_id` | `uuid` | 업무 값 |
| `eligible_received_at` | `timestamptz?` | 업무 값 |
| `ready_at` | `timestamptz?` | 구매확정과 선행 원장이 모두 준비된 시각 |
| `total_amount` | `bigint` | T; 구매자 부담액 |
| `platform_coupon_amount` | `bigint` | P; 플랫폼 지원 원금 |
| `settlement_base_amount` | `bigint` | B=T+P |
| `external_amount` | `bigint` | 업무 값 |
| `mileage_amount` | `bigint` | 업무 값 |
| `fee_amount` | `bigint` | 업무 값 |
| `net_amount` | `bigint` | 업무 값 |
| `status` | `varchar(32)` | 업무 값 |
| `purchase_confirmed_at` | `timestamptz?` | 업무 값 |
| `order_version` | `bigint` | 업무 값 |
| `payout_id` | `uuid?` | →payouts.id |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** ready_at=max(purchase_confirmed_at,eligible_received_at). UNIQUE(payment_id). T=E+M, B=T+P=fee+net. fee=floor(B*15/100). 플랫폼 지원분을 포함한 선지급 재원은 항상 충분하게 준비. 상태 조건과 예약을 같은 트랜잭션으로 보호.

**인덱스:** (seller_id,status,ready_at,id); (payout_id); UNIQUE(payment_id). 배치는 ready_at<cutoff_at인 미선점 항목을 조회하며 payout_id가 있으면 다른 배치에 배정하지 않습니다.

### settlement_entries

소유: `settlement`. 정산 원장.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `item_id` | `uuid` | →settlement_items.id |
| `payment_id` | `uuid` | 업무 값 |
| `side` | `varchar(16)` | 업무 값 |
| `leg` | `varchar(24)` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `business_ref` | `uuid` | 업무 값 |
| `source_event_id` | `uuid` | 원장을 만든 원천 금융 이벤트 ID; 같은 이벤트의 각 leg가 공유 |
| `source_occurred_at` | `timestamptz` | 원천 Envelope의 occurredAt; 늦은 반영·재전달에도 불변 |
| `reversal_of` | `uuid?` | →settlement_entries.id |
| `policy_version` | `varchar(32)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(payment_id,leg,business_ref), UNIQUE(reversal_of) WHERE NOT NULL. SOURCE:EXTERNAL/MILEAGE/PLATFORM_COUPON, ALLOCATION:SELLER/FEE. 각 측 합계=B=E+M+P; 환불은 원항목 반대 부호. 판매자 잔액은 SELLER leg만 집계.

**인덱스:** (item_id,created_at,id); (payment_id,side); (source_occurred_at,payment_id,id); (source_event_id).

대조는 생성 시각이 아닌 source_occurred_at으로 범위를 자릅니다. 최초 원장은 `PaymentConfirmed`, 환불 상쇄는 `RefundFinancialCompleted`의 ID·시각을 보존하고 기존 원금액·상쇄 유일성을 유지합니다. 다른 서비스 DB를 조회해 시각을 채우지 않습니다.

### payout_jobs

소유: `settlement`. 지급 작업.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `payout_id` | `uuid` | →payouts.id |
| `business_key` | `uuid` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `attempts` | `integer` | 업무 값 |
| `next_run_at` | `timestamptz` | 업무 값 |
| `lease_owner` | `varchar(100)?` | 업무 값 |
| `lease_token` | `uuid?` | 지급 Job 임대마다 새 UUID; 오래된 결과 반영 차단 |
| `lease_until` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(payout_id), UNIQUE(business_key). 지급 항목 선점·금액·작업 생성 원자 저장. next_run_at은 Payout 응답의 nextAttemptAt 원천입니다. 주말·공휴일도 같은 실행·재시도 규칙을 적용하며 확정 거절은 REVIEW_REQUIRED입니다.

Payment Job과 같은 임대 필드 일치·만료 회수·결과 반영 조건을 적용합니다. 금융 결과와 지급 항목·Job을 함께 반영하고, 만료된 실행자의 결과는 모두 롤백합니다. 호출·조회·원키 재개는 [공통 복구](policies.md#공통-복구)를 따릅니다.

**인덱스:** (status,next_run_at,lease_until,id).

### settlement_reservations

소유: `settlement`. 환불·지급 예약.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `item_id` | `uuid` | →settlement_items.id |
| `previous_item_status` | `varchar(32)` | 업무 값 |
| `kind` | `varchar(16)` | 업무 값 |
| `business_ref` | `uuid` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `payout_id` | `uuid?` | →payouts.id |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(item_id,kind,business_ref). 부분 UNIQUE(item_id) WHERE status=ACTIVE, kind=REFUND/PAYOUT. amount=원항목 net_amount.

**인덱스:** UNIQUE(item_id) WHERE status=ACTIVE; (kind,business_ref,status).

## 금융 Mock

![금융 Mock ERD](diagrams/erd-finance.svg)

### mock_accounts

소유: `finance_mock`. 시험 금융 계정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `provider_id` | `varchar(16)` | 업무 값 |
| `account_ref` | `varchar(100)` | 업무 값 |
| `purpose` | `varchar(16)` | 업무 값 |
| `opening_balance` | `bigint` | 시험 계정 생성 때 받은 불변 기초잔액 |
| `balance` | `bigint` | 업무 값 |
| `reserved_amount` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(provider_id,account_ref,purpose). CHECK(0<=reserved_amount AND reserved_amount<=balance). purpose=WALLET/PAYOUT. PAYOUT의 balance=opening_balance+해당 계정 cash_entries 합계이며 예약액은 소비 전까지 차감하지 않습니다.

**인덱스:** UNIQUE(provider_id,account_ref,purpose).

### mock_funds_reservations

소유: `finance_mock`. 대역 자금 선점.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `account_id` | `uuid` | →mock_accounts.id |
| `transaction_id` | `uuid` | →mock_transactions.id |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(transaction_id), amount>0, status=RESERVED/CONSUMED/RELEASED. 계정 잔액 검사·선점과 거래 기록 원자 저장.

**인덱스:** (account_id,status).

### mock_transactions

소유: `finance_mock`. 모의 금융 거래.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `provider_id` | `varchar(16)` | 업무 값 |
| `operation` | `varchar(16)` | 업무 값 |
| `business_key` | `uuid` | 업무 값 |
| `original_id` | `uuid?` | →mock_transactions.id |
| `account_id` | `uuid?` | →mock_accounts.id |
| `method` | `varchar(24)?` | 업무 값 |
| `funding_account_id` | `uuid?` | 외부 승인/지급의 PAYOUT 재원 계정 |
| `pg_calendar_version` | `varchar(32)?` | 승인 당시 입금 달력 버전 |
| `pg_calendar` | `jsonb?` | 시간대·공휴일 목록 사본 |
| `pg_receipt_due_at` | `timestamptz?` | 승인 시각+대역별 영업일 |
| `pg_received_at` | `timestamptz?` | 실제 입금/입금 전 환불 상쇄 처리 시각 |
| `pg_received_amount` | `bigint?` | 입금 처리 전 NULL, 처리 뒤 E 또는 0 |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `succeeded_at` | `timestamptz?` | 업무 값 |
| `scenario_version` | `varchar(32)` | 업무 값 |
| `result_seed` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(provider_id,operation,business_key). 진행 중/성공 환불은 original_id별1개(부분 UNIQUE). amount>0. 카드 승인은 내부 잔액 차감 없이 결과만 모의. 최초 SUCCEEDED 시각 succeeded_at을 결과와 원자 저장하고 이후 불변.

기존 멱등 기록의 발신 서비스·provider·operation·업무 키 범위와 원본문 해시를 거래 생성·잔액 변경과 같은 트랜잭션에 저장합니다. 같은 키·다른 본문은 409이며 같은 본문은 원거래를 반환합니다. 금융 시나리오 버전은 원거래에 고정하고, GET의 조회용 버전으로 금융 결과를 바꾸지 않습니다. [원키 재요청 정책](policies.md#금융-결과-조회와-원키-재요청)을 따릅니다.

원승인당 입금은 한 번이며 입금/환불은 원승인 행 잠금으로 경합합니다. 승인 성공·입금 예정 정보는 같은 트랜잭션에서 저장합니다. 입금 전 확정 환불은 0 입금으로 종결하고, 입금 후 환불은 E 차감·환불 결과·원수단 잔액 복원을 원자 저장합니다. 입금 후 환불액도 사전 준비한 충분한 모의 재원에 포함합니다. pg_received_amount=0인 상쇄에는 현금 행을 만들지 않습니다. P/M은 PG 입금에 포함하지 않습니다.

**인덱스:** (operation,status,pg_receipt_due_at,id) WHERE pg_received_at IS NULL; (provider_id,status,created_at,id); (original_id).

### mock_cash_entries

소유: `finance_mock`. PAYOUT의 PG 입금·환불·지급 차감을 보존하는 추가 전용 현금 기록. 선지급 재원은 항상 충분하며 이 기록으로 재원 부족 보류를 만들지 않습니다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `account_id` | `uuid` | →mock_accounts.id; purpose=PAYOUT |
| `kind` | `varchar(24)` | PG_RECEIPT/REFUND_DEBIT/PAYOUT_DEBIT |
| `transaction_id` | `uuid` | →mock_transactions.id |
| `amount` | `bigint` | 입금은 양수, 환불/지급 차감은 음수 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(kind,transaction_id). amount!=0, kind에 맞는 부호·거래 종류·계정을 검증합니다. 원래 거래 ID·금액을 한 번만 반영하고 행·계정 잔액을 같은 트랜잭션으로 갱신합니다. UPDATE/DELETE를 제공하지 않습니다. 지급 성공 차감은 대역 예약 소비와 원자 저장합니다.

**인덱스:** (account_id,created_at,id); UNIQUE(kind,transaction_id).

### mock_scenarios

소유: `finance_mock`. 장애 시험 설정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `settlement_delay_business_days` | `integer?` | PG/EASY_PAY의 입금 지연 0~365; PAYOUT은 NULL |
| `provider_id` | `varchar(16)` | 업무 값 |
| `scenario_version` | `varchar(32)` | 업무 값 |
| `min_delay_ms` | `integer` | 업무 값 |
| `max_delay_ms` | `integer` | 업무 값 |
| `rejection_rate` | `numeric(8,7)` | 업무 값 |
| `response_loss_rate` | `numeric(8,7)` | 업무 값 |
| `lookup_failure_rate` | `numeric(8,7)` | 업무 값 |
| `refund_min_delay_ms` | `integer?` | PG/EASY_PAY 환불 지연 최솟값; PAYOUT은 NULL |
| `refund_max_delay_ms` | `integer?` | PG/EASY_PAY 환불 지연 최댓값; PAYOUT은 NULL |
| `refund_rejection_rate` | `numeric(8,7)?` | 환불 확정 거절확률; PAYOUT은 NULL |
| `refund_response_loss_rate` | `numeric(8,7)?` | 환불 결과 저장 후 응답 유실확률; PAYOUT은 NULL |
| `lookup_min_delay_ms` | `integer` | GET 조회 지연 최솟값 |
| `lookup_max_delay_ms` | `integer` | GET 조회 지연 최댓값 |
| `seed` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(provider_id,scenario_version). 기존 min/max_delay_ms·rejection_rate·response_loss_rate는 APPROVE 또는 PAYOUT용입니다. PG/EASY_PAY는 환불 4필드가 필수이고 PAYOUT은 모두 NULL입니다. 각 작업별 0<=min<=max, 확률 0..1. GET은 명시한 조회 버전의 lookup 필드만 적용하고 설정 부재는 503입니다. 시나리오 행은 불변이며 조회 버전 교체로 저장 거래의 금융 결과를 바꾸지 않습니다.

**인덱스:** UNIQUE(provider_id,scenario_version).

## 거래 알림

![거래 알림 ERD](diagrams/erd-notifications.svg)

네 테이블은 커머스(`commerce`)가 소유하며 `commerce` DB에 저장합니다. 알림 전용 DB나 물리 FK를 만들지 않습니다. [거래 알림 정책](policies.md#거래-알림)이 종류·수신자·묶음·재시도 기준입니다.

### notification_deliveries

소유: `commerce`. 거래 알림과 발송 작업.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; deliveryId이자 모의 수신 멱등키 |
| `kind` | `varchar(40)` | 정책의 거래 알림 종류 |
| `recipient_kind` | `varchar(16)` | BUYER/SELLER |
| `recipient_id` | `uuid` | 종류에 맞는 계정 ID; 논리 참조 |
| `channel` | `varchar(24)` | MOCK_KAKAO 고정 |
| `dedup_key` | `varchar(200)` | 단건=원업무 ID, 판매 묶음=KST 구간 시작 |
| `item_count` | `integer` | 중복 제외 항목 수; 1 이상 |
| `first_occurred_at` | `timestamptz` | 항목 중 가장 이른 발생 시각 |
| `total_amount` | `bigint` | 판매 묶음의 주문 결제액 합계; 그 외 0 |
| `template_version` | `varchar(32)` | 최초 렌더 때 고정 |
| `title` | `varchar(120)` | 안내 제목 |
| `body` | `varchar(1000)` | 모의 안내 본문; COLLECTING이면 빈 값 |
| `status` | `varchar(24)` | COLLECTING/PENDING/SENDING/UNKNOWN/RETRY/SENT/REVIEW_REQUIRED |
| `bucket_start` | `timestamptz?` | 판매 묶음만 사용 |
| `bucket_end` | `timestamptz?` | 시작+1분; 닫힌 본문은 불변 |
| `attempt_count` | `integer` | 전체 시도 일련번호; 재처리 때 초기화 금지 |
| `retry_count` | `integer` | 현재 라운드의 SEND/LOOKUP 횟수 0..10 |
| `next_run_at` | `timestamptz` | 다음 마감/발송/조회 시각 |
| `lease_token` | `uuid?` | 작업 임대마다 새 값 |
| `lease_until` | `timestamptz?` | 임대 만료 |
| `last_error_code` | `varchar(80)?` | 민감값 없는 실패 코드 |
| `sent_at` | `timestamptz?` | 원모의 수신 ACCEPTED 확인 시각 |
| `created_at` | `timestamptz` | 생성 |
| `updated_at` | `timestamptz` | 갱신 |
| `version` | `bigint` | 낙관적 버전 |

**제약:** UNIQUE(channel,kind,recipient_kind,recipient_id,dedup_key). recipient_kind에 맞는 계정을 검증하며 수신처는 mock:<종류>:<ID>로 계산한다. SALE_COMPLETED만 COLLECTING과 1분 구간을 사용한다. 마감 시 행 잠금 아래 항목 추가를 차단하고 본문·템플릿·합계를 고정한다. 같은 계정의 동일 구간에 열린 묶음 하나만 존재한다. 그 외 종류의 bucket 값은 NULL이다. SENT에는 sent_at 필수. lease_token과 lease_until은 함께 설정/해제한다. attempt_count/retry_count>=0, retry_count<=10.

**인덱스:** (status,next_run_at,lease_until,id); (recipient_kind,recipient_id,created_at DESC,id DESC).

### notification_items

소유: `commerce`. 원거래별 알림 항목.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `delivery_id` | `uuid` | →notification_deliveries.id |
| `source_service` | `varchar(16)` | commerce/payment/settlement |
| `source_event_id` | `uuid` | 수신 eventId 또는 로컬 전이에서 생성한 고정 ID |
| `kind` | `varchar(40)` | 알림 종류 |
| `business_ref` | `uuid` | 원주문/요청/결제/환불/배치/지급 ID |
| `recipient_kind` | `varchar(16)` | BUYER/SELLER |
| `recipient_id` | `uuid` | 계정 ID |
| `occurred_at` | `timestamptz` | 원업무 발생 시각 |
| `payload` | `jsonb` | 해당 수신자에게 허용된 거래·금액·잔액 사본 |
| `created_at` | `timestamptz` | 커머스 최초 접수 시각 |

**제약:** UNIQUE(source_service,kind,business_ref,recipient_kind,recipient_id). delivery와 종류·수신자가 같아야 한다. 항목·묶음 연결·item_count/first_occurred_at/total_amount 갱신은 한 트랜잭션으로 저장하며 중복이면 집계도 증가시키지 않는다. 목록 조회는 저장 집계를 사용하고 항목별 추가 조회를 만들지 않는다. 판매자 항목/본문에는 구매자 지갑 잔액을 복사하지 않는다. Payment의 원본 Inbox payload는 내부 복구 전용이다. 결제 business_ref=paymentId, 환불=refundId, 반품=requestId, 발송/구매확정/미결제 취소/판매=orderId, 정산/지급조치=batchId, 지급완료=payoutId.

**인덱스:** (delivery_id,occurred_at,id); (source_event_id).

### notification_attempts

소유: `commerce`. 발송·조회·운영 재처리 이력.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `delivery_id` | `uuid` | →notification_deliveries.id |
| `attempt_no` | `integer` | 발송의 전체 단조 증가 일련번호 |
| `operation` | `varchar(16)` | SEND/LOOKUP/REPLAY |
| `status` | `varchar(24)` | RUNNING/SUCCEEDED/RETRYABLE_FAILURE/PERMANENT_FAILURE/UNKNOWN |
| `started_at` | `timestamptz` | 시작 |
| `finished_at` | `timestamptz?` | 종료 |
| `error_code` | `varchar(80)?` | 오류 코드 |
| `operator_ref` | `varchar(100)?` | REPLAY 실행 서비스 자격의 운영자 |
| `reason` | `varchar(200)?` | REPLAY 사유 |

**제약:** UNIQUE(delivery_id,attempt_no), attempt_no>=1. REPLAY는 operator_ref/reason 필수이며 원키·본문을 바꾸지 않고 retry_count만 0으로 재개한다. SEND/LOOKUP 시작과 delivery 임대·카운트 갱신을 원자 저장한다. 만료된 RUNNING은 UNKNOWN으로 남기고 LOOKUP부터 재개한다.

**인덱스:** (delivery_id,attempt_no); (status,started_at).

### notification_mock_receipts

소유: `commerce`. 모의 수신 원결과.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `delivery_id` | `uuid` | →notification_deliveries.id; 모의 수신 멱등키 |
| `request_hash` | `char(64)` | 수신자·종류·템플릿·고정 본문의 canonical SHA-256 |
| `result` | `varchar(16)` | ACCEPTED/REJECTED; 첫 확정 결과 불변 |
| `processed_at` | `timestamptz` | 모의 수신 결과 최초 저장 시각 |

**제약:** UNIQUE(delivery_id). 원키+같은 해시는 기존 결과를 반환하고 다른 해시는 충돌로 거절한다. 모의 수신 어댑터는 호출자 임대와 별도 DB 트랜잭션으로 먼저 커밋한다. 응답 유실 뒤에도 수신 결과를 조회할 수 있다. 실제 카카오 수신/읽음 이력이 아니다.

**인덱스:** UNIQUE(delivery_id).

## 메시지·멱등성·감사

![메시지·멱등성·감사 ERD](diagrams/erd-integration.svg)

Outbox·Inbox·금융 Job·격리 기록과 금융/주문 멱등 기록은 [보존·삭제 초기값](decisions/0005-kafka-event-delivery-and-capacity.md#보존과-삭제-초기값)을 따릅니다. 첫 구현은 이 DB 기록의 자동 삭제를 비활성화하며 일반 관리·가입의 기존 만료 정책은 유지합니다. 해석 불가 메시지의 격리는 원본 bytes·topic·partition·offset·실패 사유를 같은 소비자 DB에 저장할 수 있어야 하고, 구체 테이블·컬럼은 해당 소비자의 스키마 DDL에서 정의합니다.

### outbox

소유: `각 서비스 DB`. 발행할 이벤트.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `sequence_no` | `bigint` | DB identity; 같은 집계 버전 안의 저장 순번 |
| `event_type` | `varchar(80)` | 업무 값 |
| `event_version` | `integer` | 업무 값 |
| `aggregate_id` | `uuid` | 업무 값 |
| `aggregate_version` | `bigint` | 업무 값 |
| `topic` | `varchar(100)` | 업무 값 |
| `occurred_at` | `timestamptz` | 원천 occurredAt의 DB 정밀도 조회용 표현 |
| `message_key` | `varchar(100)` | 업무 값 |
| `payload` | `jsonb` | 업무 값 |
| `raw_body` | `bytea` | 최초 직렬화한 Envelope 전체의 불변 UTF-8 bytes |
| `status` | `varchar(16)` | 업무 값 |
| `attempts` | `integer` | 업무 값 |
| `next_run_at` | `timestamptz` | 업무 값 |
| `lease_until` | `timestamptz?` | 업무 값 |
| `published_partition` | `integer?` | 발행 ack의 파티션; 위치 미확정이면 null |
| `published_offset` | `bigint?` | 발행 ack의 offset; 위치 미확정이면 null |
| `published_at` | `timestamptz?` | ack의 위치를 DB에 반영한 시각; 위치 미확정이면 null |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** id=eventId. `payload`는 Envelope 전체의 JSON이며 `raw_body`를 JSON으로 해석한 값과 같아야 합니다. 업무 상태와 함께 원자 저장합니다. `occurred_at`은 원래 occurredAt을 PostgreSQL `timestamptz` 정밀도로 변환한 조회용 값이며, 마이크로초보다 세밀한 원시 시각은 `raw_body`·`payload`에 그대로 보존합니다. 발행할 때 조회용 시각으로 Envelope를 다시 만들거나 bytes를 재직렬화하지 않습니다. published_partition·published_offset·published_at은 모두 null이거나 모두 값이 있어야 하며 파티션·offset은 0 이상입니다. 발행 완료 상태와 위치를 같은 DB 트랜잭션으로 저장합니다. 미발행·ack 후 저장 전 중단은 위치 미확정이며 대조에서 제외하지 않습니다. 재발행은 원래 id·본문을 유지합니다. 위치가 바뀌어도 소비자의 원래 eventId 처리 기록은 유효합니다. payload에 주소·비밀번호·인증 토큰·실결제 비밀값을 넣지 않습니다.

현재 Commerce 저장 상태는 PENDING/RUNNING/RETRY/PUBLISHED/REVIEW_REQUIRED입니다. 저장 포트는 호출자의 업무 트랜잭션에 참여하며 트랜잭션 밖 호출을 거절합니다. 같은 집계의 append는 트랜잭션 잠금으로 직렬화하고 이전보다 낮은 집계 버전의 신규 삽입을 거절합니다. 같은 버전은 `sequence_no` 순서로 처리하며 identity의 빈 번호는 의미가 없습니다. 선행 미발행 행이 임대·재시도 대기·수동 확인 상태여도 후행을 먼저 발행하지 않습니다. 선점마다 version을 증가하고 유효 임대·version이 맞는 결과만 반영합니다. 이 DB 검사는 이미 시작된 Kafka 전송을 취소하지 못하므로 늦은 중복도 원래 ID로 수용해야 합니다.

**인덱스:** (status,next_run_at,lease_until,id); (occurred_at,created_at,id).

Commerce는 추가로 전체 행의 (aggregate_id,aggregate_version,sequence_no)를 인덱싱해 완료 이력을 포함한 최대 버전 조회와 선행 미발행 검사를 지원합니다.

### inbox

소유: `각 서비스 DB`. 수신 이벤트.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `consumer` | `varchar(100)` | 업무 값 |
| `event_id` | `uuid` | 업무 값 |
| `event_type` | `varchar(80)` | 업무 값 |
| `event_version` | `integer` | 업무 값 |
| `occurred_at` | `timestamptz` | 업무 값 |
| `payload` | `jsonb` | 업무 값 |
| `raw_body` | `bytea` | 최초 수신 Envelope 전체의 불변 bytes |
| `body_hash` | `char(64)` | raw_body의 SHA-256 |
| `source_topic` | `varchar(100)` | 최초 수신 원본 토픽 |
| `source_partition` | `integer` | 최초 수신 파티션; 0 이상 |
| `source_offset` | `bigint` | 최초 수신 offset; 0 이상 |
| `next_run_at` | `timestamptz?` | 업무 값 |
| `aggregate_id` | `uuid` | 업무 값 |
| `aggregate_version` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `result_ref` | `uuid?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(consumer,event_id). status=RECEIVED/WAITING/APPLIED/REVIEW_REQUIRED. WAITING은 원 event_type/version/occurred_at/payload와 다음 재판정 시각을 보존해 Kafka ack·재시작 뒤에도 로컬에서 재개. payload에 금융 계약 외의 주소/인증 비밀값 금지.

현재 Payment 수신은 원본과 `WAITING`·`next_run_at`을 한 행으로 저장해 메시지 처리 대기 작업을 영속화합니다. 별도 `inbox_jobs`는 만들지 않습니다. 조회용 `occurred_at`과 불변 bytes의 정밀도 구분은 Outbox와 같습니다. 같은 consumer·eventId의 동일 bytes 재전달은 원래 위치·상태·결과를 유지합니다. 다른 bytes는 아래 충돌 기록에 보존하고 그 격리 트랜잭션을 커밋한 뒤 ack합니다. 이는 금융 접수나 APPLIED를 뜻하지 않습니다. 후속 결제 유즈케이스가 대기 행을 읽고 결제·마일리지 예약·필요한 금융 `payment_jobs`와 Inbox 완료를 같은 트랜잭션에 저장해야 합니다. 결제 요청 처리기는 Worker에서 대기 행을 선점해 결제·마일리지 예약·승인 `payment_jobs`·Inbox 완료를 같은 트랜잭션에 저장하며, 외부 결제액이 0원이면 같은 트랜잭션에서 마일리지를 차감하고 결제를 확정합니다. 가용 마일리지가 부족하거나 지갑이 없으면 결제를 만들지 않고 `REVIEW_REQUIRED`로 남깁니다. 새 결제를 만든 같은 트랜잭션에서 `PaymentAccepted`를 Outbox에 저장하며, Relay는 Worker에서만 발행합니다. 처리기와 Relay는 기본 비활성이며 재시도 상한과 외부 금융 실행은 아직 연결하지 않습니다.

**인덱스:** (status,created_at,id).

Payment는 추가로 WAITING 행의 (consumer,next_run_at,id)를 인덱싱합니다.

### inbox_conflicts

소유: 현재 `payment`. 같은 consumer·eventId에 다른 bytes가 도착한 기록입니다. 원래 Inbox·처리 결과를 덮어쓰지 않습니다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `inbox_id` | `uuid` | 기존 Inbox의 애플리케이션 참조 |
| `consumer`, `event_id` | `varchar(100)`, `uuid` | 충돌한 수신 업무 키 |
| `raw_body`, `body_hash` | `bytea`, `char(64)` | 충돌 원본과 SHA-256 |
| `source_topic`, `source_partition`, `source_offset` | `varchar(100)`, `integer`, `bigint` | 충돌 메시지의 원래 위치; 파티션·offset은 0 이상 |
| `error_code`, `status` | `varchar(80)`, `varchar(24)` | EVENT_BODY_CONFLICT·REVIEW_REQUIRED |
| `created_at` | `timestamptz` | 관측 시각 |

**제약:** UNIQUE(consumer,event_id,source_topic,source_partition,source_offset). 자동 삭제하지 않습니다. 격리 저장이 실패하면 원본 offset을 ack하지 않습니다. 스키마 해석 불가 bytes의 별도 격리·DLT 실행은 아직 연결하지 않았으며 현재 Listener는 그 입력에서 무ack 중단합니다.

**인덱스:** (consumer,event_id,created_at).

### idempotency_records

소유: `각 서비스 DB`. 요청 멱등 기록.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `actor_kind` | `varchar(24)` | 업무 값 |
| `actor_id` | `varchar(100)` | 업무 값 |
| `operation` | `varchar(100)` | 업무 값 |
| `key_hash` | `char(64)` | 업무 값 |
| `request_hash` | `char(64)` | 업무 값 |
| `resource_id` | `uuid?` | 업무 값 |
| `response_status` | `integer?` | 업무 값 |
| `response_snapshot` | `jsonb?` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `expires_at` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(actor_kind,actor_id,operation,key_hash). PENDING/COMPLETED; 본문 불일치409. 금융/주문 보존, 일반 관리24h. 민감 응답 원문은 보존하지 않음.

구매자 가입은 서버가 `actor_kind=PUBLIC`, `actor_id=BUYER_REGISTRATION`, `operation=REGISTER`로 고정해 계정 생성 전에도 같은 키를 찾습니다. 기존 테이블을 사용하며 `request_hash`는 가입 본문의 서버 키 기반 HMAC-SHA256, `resource_id`는 생성한 구매자 계정 ID, `response_status`는 201입니다. 계정·웰컴 발급·이 기록의 COMPLETED 전이는 같은 트랜잭션이며 별도 가입 테이블을 만들지 않습니다. 재요청·충돌·만료의 기준은 [구매자 가입 재시도](policies.md#구매자-가입-재시도)를 따릅니다.

**인덱스:** (expires_at); (resource_id).

### audit_logs

소유: `각 서비스 DB`. 주요 변경의 실행 주체·행위 기록이며 금융 원장을 대신하지 않습니다. 커머스·Payment·Settlement·Finance Mock이 자기 DB에 각각 저장합니다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `actor_kind` | `varchar(16)` | BUYER/SELLER/SERVICE/OPERATOR/SYSTEM |
| `actor_ref` | `varchar(100)` | 검증한 계정 UUID·서비스/운영자/배치 식별자; 이메일 금지 |
| `action` | `varchar(80)` | ORDER_CREATED/REFUND_REQUESTED/PAYMENT_CONFIRMED/MILEAGE_DEBIT/PAYOUT_SUCCEEDED/NOTIFICATION_REPLAY 등 허용 목록 |
| `aggregate_type` | `varchar(50)` | 기록 대상 종류 |
| `aggregate_id` | `uuid` | 원주문/거래/작업 ID; 논리 참조 |
| `before_status` | `varchar(32)?` | 최초 생성이면 NULL |
| `after_status` | `varchar(32)?` | 잔액 변화 등 상태 없는 행위이면 NULL |
| `reason_code` | `varchar(80)?` | 거절·보상·운영 처리 사유 코드 |
| `event_id` | `uuid?` | 수신 이벤트이면 원 eventId |
| `correlation_id` | `varchar(100)` | 요청/이벤트/배치의 비밀값 없는 추적 ID |
| `occurred_at` | `timestamptz` | 해당 서비스에서 변경한 시각 |

**제약:** 추가 전용이며 UPDATE/DELETE API를 만들지 않는다. 업무 변경·감사 행을 같은 트랜잭션으로 저장하고 둘 다 롤백한다. 멱등 재요청은 기존 결과를 반환하며 상태 변경 감사 행을 다시 만들지 않는다. 실제 운영 재처리는 새 실행 행위를 기록한다. 계정 인증 변경·가드 복구는 ACCOUNT_AUTH_CHANGED/ACCOUNT_AUTH_GUARD_RECOVERED로 기록하고 correlation_id에 pendingChangeId를 사용해 DB 확정 결과와 연결한다. 월간 오류 교정은 COUPON_ISSUE_RUN_REPLACED와 COUPON_ISSUE_RUN_CREATED를 같은 트랜잭션에 남기고 reason_code=AGGREGATION_ERROR, 검증한 OPERATOR와 원회차·대체 회차를 기록한다. 원금액 변화의 근거는 원결제·마일리지/정산 원장을 참조한다. 전체 요청/응답·주소·전화번호·비밀번호·토큰·자유 형식 본문을 저장하지 않는다. 인증 실패는 별도 보안 로그이며 성공한 업무 감사 행으로 위장하지 않는다.

**인덱스:** (aggregate_type,aggregate_id,occurred_at,id); (actor_kind,actor_ref,occurred_at,id); (correlation_id).

## 트랜잭션과 관계

- `buyer_accounts/seller_accounts`는 독립 계정이다. Redis 세션과 password_reset_tokens의 계정 종류+ID가 해당 계정을 선택한다. 로그인 세션은 Redis, 복구 토큰과 인증 버전은 PostgreSQL에 두며 두 저장소의 변경을 단일 트랜잭션으로 취급하지 않는다.
- 주문·주소 사본·재고/쿠폰/이벤트 예약·Outbox를 커머스 한 트랜잭션으로 저장한다. 멱등 기록도 같은 트랜잭션에 완료한다.
- 이벤트 공개는 일반 가용 재고에서 Q를 배정하고, 참여는 R을 증가한다. 승인 시 R 감소·S 증가·배정 재고 감소·판매량 증가를 함께 반영한다. 종료 시 미사용분만 일반 재고로 반환하며 미확정 R은 유지한다.
- 기존 주문의 재고 수량을 바꾸는 트랜잭션은 orders → product_inventory → sale_events → event_participations 순서로 행을 잠근다. products와 product_inventory를 함께 다루는 트랜잭션(신규 일반 주문, 상품 이름·가격·상태 변경과 논리삭제, 판매자 재고 조정, 이벤트 공개·종료)은 product_inventory를 먼저 배타 잠금한 뒤 products를 읽거나 갱신한다. products를 먼저 잠그거나 products에 공유 잠금을 걸지 않는다. 상품 변경은 이 잠금 아래 공개 후 종료 전 이벤트가 없는지 확인하고 버전을 증가시킨다. 선착순 예약은 products·재고 행을 잠그지 않고 sale_events의 조건부 갱신(`status=OPEN AND sold_count+reserved_count<quota`) 한 번으로 R을 늘린다. 잠금 경합 검증은 상품 상태 중단/가격 변경과 신규 주문의 잠금 획득 순서를 각각 확인한다. 주문이 먼저 잠그면 주문 저장 뒤 상품 변경이 진행되고, 상품 변경이 먼저 커밋되면 주문은 새 상태·버전을 반영해 거절되어야 한다.
- 마일리지 잔액의 reserved_amount는 활성 예약 합계와 일치해야 한다. 사용 시 예약/잔액 차감·DEBIT·결제 확정·Outbox를 한 번에 기록하고, 환불 시 원외부 환불 확인 뒤 RESTORE·잔액·환불 상태·Outbox를 한 번에 기록한다.
- 정산 항목은 결제당 하나, 지급은 배치당 하나이다. 활성 정산 예약은 항목당 하나여서 환불과 지급이 동시에 선점할 수 없다. partial UNIQUE로 보호하고 모든 상태/금액을 같은 DB에서 검사한다.
- 정산 원장은 SOURCE(E/M/P)와 ALLOCATION(판매자/수수료)의 두 측을 분리한다. 각 측의 합계가 B이고 환불 뒤 0으로 상쇄된다. 두 측을 합쳐 매출을 두 배로 세지 않는다. reversal_of는 원원장 항목당 한 번이다.
- outbox/inbox/idempotency_records 정의는 commerce/payment/settlement/finance_mock 각각에 적용한다. 서비스 간 공유 테이블이나 SQL join은 만들지 않는다. inbox의 event_id는 발신 outbox id와 논리적으로 대응하며 여러 consumer가 각각 수신할 수 있다.
- 로컬 주문 전이·알림 항목, 외부 알림 Inbox·항목은 각각 커머스 한 트랜잭션에 저장한다. 알림 모의 수신은 별도 트랜잭션으로 기록하고 금융 DB를 갱신하지 않는다.
- PK/UNIQUE/CHECK는 DB 제약으로, 합계·참조·다중 행 전이는 행 잠금/조건부 갱신·동일 트랜잭션으로 구현한다. ERD의 관계선은 FK constraint가 있다는 뜻이 아니다. FK 미사용의 보완책과 재검토 조건은 [FK 미사용 결정](decisions/0006-no-foreign-keys-within-service.md)을 따른다.

## 저장 상태와 전이

| 대상 | 정상·실패 전이 |
| --- | --- |
| 주문 | 배송 UNSHIPPED→SHIPPED→DELIVERED이며 반품 입고는 SHIPPED/DELIVERED→RETURN_RECEIVED. PAYMENT_PENDING→PAID/DECLINED/CANCEL_PENDING; 재시도 대기 DECLINED→CANCEL_PENDING; 승인 차단 확인 뒤 CANCEL_PENDING→CANCELLED. PAID→REFUND_PENDING→REFUNDED 또는 안전한 종결 후 PAID. PAID→PURCHASE_CONFIRMED는 배송완료·환불 마감 초과·미진행 환불일 때만. |
| 결제 | PENDING→PROCESSING→CONFIRMED/DECLINED/UNKNOWN. E=0이면 PROCESSING 없이 PENDING→CONFIRMED. 외부 호출 전 미결제 취소만 PENDING→CANCELLED이며 PROCESSING·UNKNOWN은 취소하지 않고 예약을 유지·조회. UNKNOWN은 원대역 조회와 거래 부재 시의 원키 재요청 결과로만 확정하며 원업무 키·예약을 유지. DECLINED 재시도는 예약 재확보 후 새 attempt. 재시도 대기 중에는 DECLINED를 유지하고, 취소 요청을 처리해 이후 승인·재시도를 차단할 때만 DECLINED→CANCELLED. CONFIRMED·CANCELLED는 종료. |
| 외부 시도 | PENDING→PROCESSING→APPROVED/DECLINED/UNKNOWN. M 전액이면 attempt 없음. |
| 금융 환불 | RESERVING→EXTERNAL_PENDING→RESTORING→INTERNAL_PENDING→COMPLETED. 원장 상쇄·커머스 복구 확인 대기는 INTERNAL_PENDING이며, RefundCompleted 발행만으로 완료하지 않는다. RefundOrderRestored를 수신·반영한 뒤 COMPLETED로 전이한다. 외부 거절은 COMPENSATING→DECLINED, 결과 불명은 UNKNOWN. |
| 정산 항목 | WAITING_CONFIRMATION→PAYABLE은 구매확정과 선행 원장이 모두 준비된 ready_at 시점. WAITING_CONFIRMATION/PAYABLE→REFUND_RESERVED→REFUNDED. 지급은 PAYABLE→PAYOUT_RESERVED→PAID만 허용. 보상은 previous_item_status와 최신 구매확정/환불 정보를 대조해 조건부 복귀. |
| 거래 알림 | 판매 COLLECTING→PENDING, 나머지 PENDING→SENDING→SENT/RETRY/UNKNOWN/REVIEW_REQUIRED. UNKNOWN은 원수신 조회부터 재개. SENT는 모의 수신 확인이며 금융 성공 상태를 바꾸지 않는다. |
| 재고 예약 | RESERVED→CONSUMED→RESTORED, RESERVED→RELEASED→RESERVED(새 결제 회차). 회차가 일치하는 결과만 반영하며 세부 조건은 [inventory_reservations](#inventory_reservations). |
| 쿠폰·이벤트·예약 | [정책](policies.md)의 상태와 시각 조건을 함께 적용한다. 상태를 덮어써 사용/복원 이력을 지우지 않는다. |

## 구현 시 검증

테이블을 구현할 때 위 부분 유니크·체크·인덱스와 실제 enum을 DDL에 명시한다. 동시에 같은 쿠폰·마일리지·마지막 재고를 쓰는 경우와 환불/지급 예약 경합, 자동 구매확정/환불 마감 경합을 실제 DB로 검사한다. 상품 변경과 신규 일반 주문·이벤트 공개의 양쪽 잠금 순서, 이벤트 기간의 상품 변경 거절, 미결제 기한 취소와 결제 확정/재시도의 경합도 실제 DB에서 검사하고, Payment의 승인 차단 확정 전에는 예약이 유지되는지 확인한다. 현재 문서·그림의 구조 검사는 DB 동작 검증을 대신하지 않는다.
