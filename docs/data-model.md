# 데이터 모델

서비스별 PostgreSQL 설계입니다. 물리 FK는 만들지 않고 아래 관계를 애플리케이션에서 검증합니다. 식별자는 서버 생성 UUID, 금액·누적 수량은 bigint, 시각은 UTC `timestamptz`입니다. `?`는 nullable, 나머지 컬럼은 NOT NULL입니다. 숫자 버전은 0부터 시작하고 조건부 갱신마다 증가합니다. 실제 Flyway migration은 아직 없으며 [DB 변경 정책](migrations.md)을 따릅니다.

아래 컬럼·제약·인덱스가 기준이고 ERD는 주요 필드와 관계를 요약합니다. 각 뷰는 같은 DB 안의 관계를 보여주며 외부 서비스 ID는 값으로만 저장합니다. 주소·연락처는 별도 암호화 사본이고 금융 이벤트에 포함하지 않습니다.

## 계정·상품

![계정·상품 ERD](diagrams/erd-accounts.svg)

### buyer_accounts

소유: `seller`. 구매자 계정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `email_normalized` | `varchar(254)` | 업무 값 |
| `password_hash` | `varchar(255)` | 업무 값 |
| `display_name` | `varchar(80)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** UNIQUE(email_normalized), 삭제 뒤에도 유지. status=ACTIVE/SUSPENDED/DELETED.

**인덱스:** (status,id).

### auth_sessions

소유: `seller`. 세션·복구 토큰.

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

**제약:** UNIQUE(token_hash). actor_kind=BUYER/SELLER, purpose=SESSION/PASSWORD_RESET. 목적·종류·만료·폐기를 DB에서 검사. RESET 발급은 계정 잠금 아래 이전 RESET을 폐기하고, 재설정 성공은 해당 계정의 모든 세션/RESET을 폐기.

**인덱스:** (actor_kind,actor_id,purpose); (expires_at).

### seller_accounts

소유: `seller`. 판매자 계정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `email_normalized` | `varchar(254)` | 업무 값 |
| `password_hash` | `varchar(255)` | 업무 값 |
| `display_name` | `varchar(80)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `payout_account_ref` | `varchar(100)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |
| `deleted_at` | `timestamptz?` | 논리삭제 |

**제약:** UNIQUE(email_normalized), 구매자 계정과 별도. status=ACTIVE/SUSPENDED/DELETED. 지급 계정은 시험용 참조.

**인덱스:** (status,id).

### buyer_addresses

소유: `seller`. 구매자 주소록.

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

**제약:** 부분 UNIQUE(buyer_id) WHERE is_default AND deleted_at IS NULL. 타인 주소 접근 금지.

**인덱스:** (buyer_id,deleted_at,created_at,id).

### product_inventory

소유: `seller`. 재고.

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

**제약:** UNIQUE(product_id). 모든 수량>=0. 공개·일반 주문·이벤트 수량 배정은 동일 재고 행 잠금 순서를 사용.

**인덱스:** UNIQUE(product_id).

### products

소유: `seller`. 상품.

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

소유: `seller`. 재고 변경 이력.

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

**제약:** UNIQUE(product_id,operation,business_ref). 증감과 현재 재고를 같은 트랜잭션으로 기록.

**인덱스:** (product_id,created_at,id).

## 주문·선착순 이벤트

![주문·선착순 이벤트 ERD](diagrams/erd-orders.svg)

### sale_events

소유: `seller`. 선착순 이벤트.

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

소유: `seller`. 이벤트 참여.

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

소유: `seller`. 주문.

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
| `coupon_amount` | `bigint` | 업무 값 |
| `total_amount` | `bigint` | 업무 값 |
| `mileage_amount` | `bigint` | 업무 값 |
| `external_amount` | `bigint` | 업무 값 |
| `payment_cycle_id` | `uuid` | 업무 값 |
| `settlement_anchor_at` | `timestamptz` | 업무 값 |
| `external_method` | `varchar(24)?` | 업무 값 |
| `provider_id` | `varchar(16)?` | 업무 값 |
| `method_account_ref` | `varchar(100)?` | 시험용 |
| `status` | `varchar(32)` | 업무 값 |
| `fulfillment_status` | `varchar(24)` | 업무 값 |
| `payment_confirmed_at` | `timestamptz?` | 업무 값 |
| `refund_deadline_at` | `timestamptz?` | 업무 값 |
| `purchase_confirmed_at` | `timestamptz?` | 업무 값 |
| `payment_wait_until` | `timestamptz` | 업무 값 |
| `policy_version` | `varchar(32)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** G=unit_price*quantity, T=G-D=E+M; 1<=T<=10000000, 0<=D<G, 0<=M<=T, E>=0. 일반 quantity 1..10; event_id가 있으면1. Payment 연결 ID는 알려진 경우 UNIQUE. 상태 전이는 아래 표.

**인덱스:** (buyer_id,created_at,id); (seller_id,created_at,id); (status,refund_deadline_at,id); UNIQUE(payment_id) WHERE payment_id IS NOT NULL.

### order_refund_requests

소유: `seller`. 취소·반품 요청.

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

소유: `seller`. 주문 재고 예약.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `order_id` | `uuid` | →orders.id |
| `product_id` | `uuid` | →products.id |
| `event_id` | `uuid?` | →sale_events.id |
| `quantity` | `integer` | 업무 값 |
| `cycle_id` | `uuid` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `expires_at` | `timestamptz` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(order_id). quantity>0. status=RESERVED/CONSUMED/RELEASED/RESTORED. 기한만으로 RELEASED 전이 금지.

**인덱스:** (status,expires_at,id); (product_id,status).

### order_addresses

소유: `seller`. 주문 주소 사본.

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

소유: `seller`. 쿠폰 템플릿.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `seller_id` | `uuid` | →seller_accounts.id |
| `product_id` | `uuid?` | →products.id |
| `name` | `varchar(80)` | 업무 값 |
| `discount_amount` | `bigint` | 업무 값 |
| `min_order_amount` | `bigint` | 업무 값 |
| `issuance_limit` | `integer` | 업무 값 |
| `issued_count` | `integer` | 업무 값 |
| `valid_from` | `timestamptz` | 업무 값 |
| `valid_until` | `timestamptz` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** 정액 할인>0, 1<=min_order_amount<=10000000, 0<=issued_count<=issuance_limit, valid_from<valid_until. status=ACTIVE/CLOSED. 발급 뒤 조건 변경 금지.

**인덱스:** (seller_id,status,created_at,id).

### buyer_coupons

소유: `seller`. 발급 쿠폰.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `template_id` | `uuid` | →coupon_templates.id |
| `buyer_id` | `uuid` | →buyer_accounts.id |
| `seller_id` | `uuid` | 업무 값 |
| `product_id` | `uuid?` | 업무 값 |
| `discount_amount` | `bigint` | 업무 값 |
| `min_order_amount` | `bigint` | 업무 값 |
| `valid_from` | `timestamptz` | 업무 값 |
| `expires_at` | `timestamptz` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `reserved_order_id` | `uuid?` | →orders.id |
| `used_order_id` | `uuid?` | →orders.id |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(template_id,buyer_id), expires_at=min(created_at+30일,템플릿종료). 원할인/기간 사본 불변. status=ISSUED/RESERVED/USED/EXPIRED. 유효성은 상태와 시각 동시 검사.

**인덱스:** (buyer_id,status,expires_at,id); UNIQUE(reserved_order_id) WHERE reserved_order_id IS NOT NULL; UNIQUE(used_order_id) WHERE used_order_id IS NOT NULL.

### coupon_history

소유: `seller`. 쿠폰 예약·복구 이력.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `buyer_coupon_id` | `uuid` | →buyer_coupons.id |
| `order_id` | `uuid` | →orders.id |
| `operation` | `varchar(16)` | 업무 값 |
| `business_ref` | `uuid` | 업무 값 |
| `from_status` | `varchar(16)` | 업무 값 |
| `to_status` | `varchar(16)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(buyer_coupon_id,operation,business_ref). operation=RESERVE/USE/RELEASE/RESTORE/EXPIRE. RESERVE/RELEASE는 payment_cycle_id별, USE/RESTORE는 원결제별 한 번.

**인덱스:** (buyer_coupon_id,created_at,id).

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
| `settlement_anchor_at` | `timestamptz` | 업무 값 |
| `current_cycle_id` | `uuid` | 업무 값 |
| `gross_amount` | `bigint` | 업무 값 |
| `coupon_amount` | `bigint` | 업무 값 |
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

**제약:** UNIQUE(order_id). T=E+M, fee=floor(T*5/100), seller_net=T-fee; 모두 비음수,T>=1. 같은 주문 재시도는 같은 결제의 새 attempt.

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
| `lease_until` | `timestamptz?` | 업무 값 |
| `last_error_code` | `varchar(80)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(operation,business_key). 작업 저장 뒤 메시지 ack. status=PENDING/RUNNING/RETRY/REVIEW_REQUIRED/DONE.

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
| `business_key` | `uuid` | 업무 값 |
| `external_ref` | `uuid?` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `scenario_version` | `varchar(32)?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(payment_id,attempt_no), UNIQUE(provider_id,business_key). 진행 중/성공 시도는 결제별1개(부분 UNIQUE). E>0인 경우만 생성. 상태=PENDING/PROCESSING/UNKNOWN/APPROVED/DECLINED/CANCELLED.

**인덱스:** (status,updated_at,id); UNIQUE(payment_id) WHERE status IN (PENDING,PROCESSING,UNKNOWN,APPROVED).

## 정산·지급

![정산·지급 ERD](diagrams/erd-settlement.svg)

### settlement_batches

소유: `settlement`. 정산 배치.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `seller_id` | `uuid` | 업무 값 |
| `product_id` | `uuid` | 업무 값 |
| `anchor_at` | `timestamptz` | 업무 값 |
| `cycle_no` | `bigint` | 업무 값 |
| `cutoff_at` | `timestamptz` | 업무 값 |
| `policy_version` | `varchar(32)` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `net_amount` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(product_id,cycle_no,policy_version); cutoff_at=anchor_at+cycle_no*14일. net_amount>=0, status=CREATED/RESERVED/PROCESSING/COMPLETED/REVIEW_REQUIRED.

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
| `succeeded_at` | `timestamptz?` | 업무 값 |
| `amount` | `bigint` | 업무 값 |
| `business_key` | `uuid` | 업무 값 |
| `external_ref` | `uuid?` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
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
| `settlement_anchor_at` | `timestamptz` | 업무 값 |
| `eligible_received_at` | `timestamptz?` | 업무 값 |
| `cycle_no` | `bigint?` | 업무 값 |
| `scheduled_cutoff_at` | `timestamptz?` | 업무 값 |
| `total_amount` | `bigint` | 업무 값 |
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

**제약:** UNIQUE(payment_id). T=E+M=fee+net. 상태 조건과 예약을 같은 트랜잭션으로 보호.

**인덱스:** (seller_id,status,purchase_confirmed_at,id); (payout_id); UNIQUE(payment_id).

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
| `reversal_of` | `uuid?` | →settlement_entries.id |
| `policy_version` | `varchar(32)` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(payment_id,leg,business_ref), UNIQUE(reversal_of) WHERE NOT NULL. SOURCE:EXTERNAL/MILEAGE, ALLOCATION:SELLER/FEE. 각 측 합계=T; 환불은 원항목 반대 부호. 판매자 잔액은 SELLER leg만 집계.

**인덱스:** (item_id,created_at,id); (payment_id,side).

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
| `lease_until` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(payout_id), UNIQUE(business_key). 지급 항목 선점·금액·작업 생성 원자 저장.

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
| `balance` | `bigint` | 업무 값 |
| `reserved_amount` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(provider_id,account_ref,purpose). CHECK(0<=reserved_amount AND reserved_amount<=balance). purpose=GIFT/WALLET/PAYOUT.

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
| `amount` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `succeeded_at` | `timestamptz?` | 업무 값 |
| `scenario_version` | `varchar(32)` | 업무 값 |
| `result_seed` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(provider_id,operation,business_key). 진행 중/성공 환불은 original_id별1개(부분 UNIQUE). amount>0. 카드 승인은 내부 잔액 차감 없이 결과만 모의. 최초 SUCCEEDED 시각 succeeded_at을 결과와 원자 저장하고 이후 불변.

**인덱스:** (provider_id,status,created_at,id); (original_id).

### mock_scenarios

소유: `finance_mock`. 장애 시험 설정.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `provider_id` | `varchar(16)` | 업무 값 |
| `scenario_version` | `varchar(32)` | 업무 값 |
| `min_delay_ms` | `integer` | 업무 값 |
| `max_delay_ms` | `integer` | 업무 값 |
| `rejection_rate` | `numeric(8,7)` | 업무 값 |
| `response_loss_rate` | `numeric(8,7)` | 업무 값 |
| `lookup_failure_rate` | `numeric(8,7)` | 업무 값 |
| `seed` | `bigint` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |

**제약:** UNIQUE(provider_id,scenario_version). 0<=min<=max, 각 확률0..1. 저장 거래의 결과는 설정 변경 뒤에도 불변.

**인덱스:** UNIQUE(provider_id,scenario_version).

## 메시지·멱등성

![메시지·멱등성 ERD](diagrams/erd-integration.svg)

### outbox

소유: `각 서비스 DB`. 발행할 이벤트.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| `id` | `uuid` | PK; 서버 생성 |
| `event_type` | `varchar(80)` | 업무 값 |
| `event_version` | `integer` | 업무 값 |
| `aggregate_id` | `uuid` | 업무 값 |
| `aggregate_version` | `bigint` | 업무 값 |
| `topic` | `varchar(100)` | 업무 값 |
| `message_key` | `varchar(100)` | 업무 값 |
| `payload` | `jsonb` | 업무 값 |
| `status` | `varchar(16)` | 업무 값 |
| `attempts` | `integer` | 업무 값 |
| `next_run_at` | `timestamptz` | 업무 값 |
| `lease_until` | `timestamptz?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** id=eventId. 상태와 함께 원자 저장. payload에 주소·비밀번호·인증 토큰·실결제 비밀값 금지.

**인덱스:** (status,next_run_at,lease_until,id).

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
| `next_run_at` | `timestamptz?` | 업무 값 |
| `aggregate_id` | `uuid` | 업무 값 |
| `aggregate_version` | `bigint` | 업무 값 |
| `status` | `varchar(24)` | 업무 값 |
| `result_ref` | `uuid?` | 업무 값 |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL |
| `version` | `bigint` | NOT NULL; 낙관적 버전 |

**제약:** UNIQUE(consumer,event_id). status=RECEIVED/WAITING/APPLIED/REVIEW_REQUIRED. WAITING은 원 event_type/version/occurred_at/payload와 다음 재판정 시각을 보존해 Kafka ack·재시작 뒤에도 로컬에서 재개. payload에 금융 계약 외의 주소/인증 비밀값 금지.

**인덱스:** (status,created_at,id).

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

**인덱스:** (expires_at); (resource_id).

## 트랜잭션과 관계

- `buyer_accounts/seller_accounts`는 독립 계정이다. `auth_sessions`의 actor_kind+actor_id가 해당 테이블을 선택한다. 로그인/복구 토큰 목적을 섞지 않는다.
- 주문·주소 사본·재고/쿠폰/이벤트 예약·Outbox를 커머스 한 트랜잭션으로 저장한다. 멱등 기록도 같은 트랜잭션에 완료한다.
- 이벤트 공개는 일반 가용 재고에서 Q를 배정하고, 참여는 R을 증가한다. 승인 시 R 감소·S 증가·배정 재고 감소·판매량 증가를 함께 반영한다. 종료 시 미사용분만 일반 재고로 반환하며 미확정 R은 유지한다.
- 마일리지 잔액의 reserved_amount는 활성 예약 합계와 일치해야 한다. 사용 시 예약/잔액 차감·DEBIT·결제 확정·Outbox를 한 번에 기록하고, 환불 시 원외부 환불 확인 뒤 RESTORE·잔액·환불 상태·Outbox를 한 번에 기록한다.
- 정산 항목은 결제당 하나, 지급은 배치당 하나이다. 활성 정산 예약은 항목당 하나여서 환불과 지급이 동시에 선점할 수 없다. partial UNIQUE로 보호하고 모든 상태/금액을 같은 DB에서 검사한다.
- 정산 원장은 SOURCE(E/M)와 ALLOCATION(판매자/수수료)의 두 측을 분리한다. 각 측의 합계가 T이고 환불 뒤 0으로 상쇄된다. 두 측을 합쳐 매출을 두 배로 세지 않는다. reversal_of는 원원장 항목당 한 번이다.
- outbox/inbox/idempotency_records 정의는 seller/payment/settlement/finance_mock 각각에 적용한다. 서비스 간 공유 테이블이나 SQL join은 만들지 않는다. inbox의 event_id는 발신 outbox id와 논리적으로 대응하며 여러 consumer가 각각 수신할 수 있다.
- PK/UNIQUE/CHECK는 DB 제약으로, 합계·참조·다중 행 전이는 행 잠금/조건부 갱신·동일 트랜잭션으로 구현한다. ERD의 관계선은 FK constraint가 있다는 뜻이 아니다.

## 저장 상태와 전이

| 대상 | 정상·실패 전이 |
| --- | --- |
| 주문 | PAYMENT_PENDING→PAID/DECLINED/CANCEL_PENDING→CANCELLED. PAID→REFUND_PENDING→REFUNDED 또는 안전한 종결 후 PAID. PAID→PURCHASE_CONFIRMED는 환불 마감 초과·미진행 환불일 때만. |
| 결제 | PENDING→PROCESSING→CONFIRMED/DECLINED/UNKNOWN. UNKNOWN은 원대역 조회로만 확정. DECLINED 재시도는 예약 재확보 후 새 attempt. |
| 외부 시도 | PENDING→PROCESSING→APPROVED/DECLINED/UNKNOWN. M 전액이면 attempt 없음. |
| 금융 환불 | RESERVING→EXTERNAL_PENDING→RESTORING→INTERNAL_PENDING→COMPLETED. 외부 거절은 COMPENSATING→DECLINED, 결과 불명은 UNKNOWN. 상태만으로 커머스 복구까지 완료했다고 표시하지 않는다. |
| 정산 항목 | WAITING_CONFIRMATION/PAYABLE→REFUND_RESERVED→REFUNDED. 지급은 PAYABLE→PAYOUT_RESERVED→PAID만 허용. 보상은 previous_item_status와 최신 구매확정/환불 정보를 대조해 조건부 복귀. |
| 쿠폰·이벤트·예약 | [정책](policies.md)의 상태와 시각 조건을 함께 적용한다. 상태를 덮어써 사용/복원 이력을 지우지 않는다. |

## 구현 시 검증

migration을 추가할 때 위 부분 유니크·체크·인덱스와 실제 enum을 DDL에 명시한다. 동시에 같은 쿠폰·마일리지·마지막 재고를 쓰는 경우와 환불/지급 예약 경합, 자동 구매확정/환불 마감 경합을 실제 DB로 검사한다. 현재 문서·그림의 구조 검사는 DB 동작 검증을 대신하지 않는다.
