-- 배포 전 개발용 초기 DDL. 빈 서비스 DB에서 한 번 실행합니다.
CREATE SCHEMA commerce;

COMMENT ON SCHEMA commerce IS 'Commerce service owned schema';

CREATE TABLE commerce.orders (
    id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    product_id uuid NOT NULL,
    event_id uuid,
    buyer_coupon_id uuid,
    payment_id uuid,
    product_name varchar(120) NOT NULL,
    unit_price bigint NOT NULL CHECK (unit_price BETWEEN 1 AND 10000000),
    quantity integer NOT NULL,
    gross_amount bigint NOT NULL CHECK (gross_amount BETWEEN 1 AND 10000000),
    coupon_amount bigint NOT NULL,
    seller_coupon_amount bigint NOT NULL CHECK (seller_coupon_amount >= 0),
    platform_coupon_amount bigint NOT NULL CHECK (platform_coupon_amount >= 0),
    settlement_base_amount bigint NOT NULL,
    total_amount bigint NOT NULL CHECK (total_amount BETWEEN 1 AND 10000000),
    mileage_amount bigint NOT NULL,
    external_amount bigint NOT NULL CHECK (external_amount >= 0),
    payment_cycle_id uuid NOT NULL,
    external_method varchar(24),
    provider_id varchar(16),
    method_account_ref varchar(100),
    status varchar(32) NOT NULL CHECK (status IN ('PAYMENT_PENDING','PAID','DECLINED',
        'CANCEL_PENDING','CANCELLED','REFUND_PENDING','REFUNDED','PURCHASE_CONFIRMED')),
    fulfillment_status varchar(24) NOT NULL
        CHECK (fulfillment_status IN ('UNSHIPPED','SHIPPED','DELIVERED','RETURN_RECEIVED')),
    simulation_seed bigint NOT NULL,
    delivery_business_days integer NOT NULL CHECK (delivery_business_days IN (2,3)),
    delivery_calendar_version varchar(32) NOT NULL,
    delivery_calendar jsonb NOT NULL CHECK (jsonb_typeof(delivery_calendar) = 'object'),
    shipped_at timestamptz,
    delivery_due_at timestamptz,
    delivered_at timestamptz,
    payment_confirmed_at timestamptz,
    refund_deadline_at timestamptz,
    purchase_confirmed_at timestamptz,
    closed_at timestamptz,
    payment_wait_until timestamptz NOT NULL,
    policy_version varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((event_id IS NULL AND quantity BETWEEN 1 AND 10)
        OR (event_id IS NOT NULL AND quantity = 1)),
    CHECK (gross_amount = unit_price * quantity),
    CHECK (coupon_amount = seller_coupon_amount + platform_coupon_amount
        AND coupon_amount >= 0 AND coupon_amount < gross_amount),
    CHECK (seller_coupon_amount = 0 OR platform_coupon_amount = 0),
    CHECK (settlement_base_amount = gross_amount - seller_coupon_amount),
    CHECK (total_amount = settlement_base_amount - platform_coupon_amount
        AND total_amount = external_amount + mileage_amount),
    CHECK (mileage_amount BETWEEN 0 AND total_amount),
    CHECK ((buyer_coupon_id IS NULL AND coupon_amount = 0)
        OR (buyer_coupon_id IS NOT NULL AND mileage_amount = 0)),
    CHECK ((external_amount = 0 AND external_method IS NULL
            AND method_account_ref IS NULL AND provider_id IS NULL)
        OR (external_amount > 0 AND external_method IS NOT NULL
            AND external_method IN ('CREDIT_CARD','DEBIT_CARD','EASY_PAY')
            AND method_account_ref IS NOT NULL AND length(trim(method_account_ref)) > 0)),
    CHECK ((shipped_at IS NULL) = (delivery_due_at IS NULL)),
    CHECK (delivered_at IS NULL OR (delivery_due_at IS NOT NULL AND delivered_at = delivery_due_at)),
    CHECK ((delivered_at IS NULL AND refund_deadline_at IS NULL)
        OR (delivered_at IS NOT NULL AND refund_deadline_at IS NOT NULL
            AND refund_deadline_at = delivered_at + interval '168 hours')),
    CHECK ((closed_at IS NOT NULL) = (status IN ('CANCELLED','REFUNDED','PURCHASE_CONFIRMED'))),
    CHECK (payment_wait_until > created_at)
);
CREATE INDEX ix_orders_retention ON commerce.orders(closed_at,id) WHERE closed_at IS NOT NULL;
CREATE INDEX ix_orders_buyer_created ON commerce.orders (buyer_id, created_at, id);
CREATE INDEX ix_orders_seller_created ON commerce.orders (seller_id, created_at, id);
CREATE INDEX ix_orders_delivery_due ON commerce.orders (fulfillment_status, delivery_due_at, id);
CREATE INDEX ix_orders_refund_deadline ON commerce.orders (status, refund_deadline_at, id);
CREATE INDEX ix_orders_payment_wait ON commerce.orders (status, payment_wait_until, id);
CREATE UNIQUE INDEX uq_orders_payment ON commerce.orders (payment_id) WHERE payment_id IS NOT NULL;

-- 구매자 의사와 종결에 사용한 소유 서비스 증빙은 추가 전용으로 보존한다.
CREATE TABLE commerce.refund_closure_confirmations (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    refund_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    order_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    request_version bigint NOT NULL CHECK (request_version >= 0),
    order_version bigint NOT NULL CHECK (order_version >= 0),
    confirmed_at timestamptz NOT NULL
);
CREATE INDEX ix_refund_closure_confirmations_request ON commerce.refund_closure_confirmations (request_id, confirmed_at);

CREATE TABLE commerce.refund_closure_evidence (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL UNIQUE,
    operator_ref varchar(100) NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    closed_at timestamptz NOT NULL
);

CREATE TABLE commerce.order_refund_requests (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('CANCEL','RETURN')),
    status varchar(32) NOT NULL CHECK (status IN ('PENDING_SELLER','APPROVED','REFUNDING','COMPLETED','REJECTED','WITHDRAWN','FAILED_REVIEW','CLOSED')),
    accepted_at timestamptz NOT NULL,
    reason varchar(200) NOT NULL CHECK (length(trim(reason)) > 0),
    refund_id uuid,
    decision_at timestamptz,
    received_at timestamptz,
    closed_reason varchar(200),
    closure_evidence_ref varchar(200),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (received_at IS NULL OR kind = 'RETURN'),
    CHECK (status <> 'CLOSED' OR (closed_reason IS NOT NULL AND closure_evidence_ref IS NOT NULL))
);
CREATE UNIQUE INDEX uq_order_refund_active ON commerce.order_refund_requests(order_id)
    WHERE status IN ('PENDING_SELLER','APPROVED','REFUNDING','COMPLETED','FAILED_REVIEW');
CREATE UNIQUE INDEX uq_order_refund_financial ON commerce.order_refund_requests(refund_id) WHERE refund_id IS NOT NULL;
CREATE INDEX ix_order_refund_order ON commerce.order_refund_requests(order_id,created_at,id);
CREATE INDEX ix_order_refund_seller ON commerce.order_refund_requests(seller_id,status,created_at,id);

CREATE TABLE commerce.order_addresses (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    recipient_ciphertext bytea NOT NULL CHECK (octet_length(recipient_ciphertext) > 0),
    phone_ciphertext bytea NOT NULL CHECK (octet_length(phone_ciphertext) > 0),
    address_ciphertext bytea NOT NULL CHECK (octet_length(address_ciphertext) > 0),
    masked_at timestamptz,
    hold_reason varchar(200),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (masked_at IS NULL OR (recipient_ciphertext=decode('00','hex')
        AND phone_ciphertext=decode('00','hex') AND address_ciphertext=decode('00','hex')))
);

CREATE TABLE commerce.outbox (
    id uuid PRIMARY KEY,
    sequence_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type varchar(80) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    topic varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    message_key varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    raw_body bytea NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'PUBLISHED', 'REVIEW_REQUIRED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_run_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    published_partition integer CHECK (published_partition >= 0),
    published_offset bigint CHECK (published_offset >= 0),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'PUBLISHED') = (published_partition IS NOT NULL)),
    CHECK ((published_partition IS NULL AND published_offset IS NULL AND published_at IS NULL)
        OR (published_partition IS NOT NULL AND published_offset IS NOT NULL AND published_at IS NOT NULL)),
    CHECK (payload = convert_from(raw_body, 'UTF8')::jsonb),
    CHECK (jsonb_typeof(payload) = 'object' AND payload ?& ARRAY[
        'eventId','eventType','eventVersion','occurredAt','aggregateId','aggregateVersion','payload']),
    CHECK (jsonb_typeof(payload->'eventId')='string' AND jsonb_typeof(payload->'eventType')='string'
        AND jsonb_typeof(payload->'eventVersion')='number' AND jsonb_typeof(payload->'occurredAt')='string'
        AND jsonb_typeof(payload->'aggregateId')='string' AND jsonb_typeof(payload->'aggregateVersion')='number'
        AND jsonb_typeof(payload->'payload')='object'),
    CHECK ((payload ->> 'eventId')::uuid = id),
    CHECK (payload ->> 'eventType' = event_type),
    CHECK ((payload ->> 'eventVersion')::integer = event_version),
    CHECK ((payload ->> 'aggregateId')::uuid = aggregate_id),
    CHECK ((payload ->> 'aggregateVersion')::bigint = aggregate_version),
    CHECK ((payload ->> 'occurredAt')::timestamptz = occurred_at)
);
CREATE INDEX outbox_due ON commerce.outbox(status, next_run_at, lease_until, id);
CREATE INDEX outbox_occurred ON commerce.outbox(occurred_at, created_at, id);
CREATE INDEX outbox_aggregate ON commerce.outbox(aggregate_id, aggregate_version, sequence_no);

CREATE TABLE commerce.products (
    id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    unit_price bigint NOT NULL,
    status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CONSTRAINT ck_products_unit_price CHECK (unit_price BETWEEN 1 AND 10000000),
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ON_SALE', 'STOPPED', 'DELETED'))
);
CREATE INDEX ix_products_seller_status_created ON commerce.products (seller_id, status, created_at, id);
CREATE INDEX ix_products_status_created ON commerce.products (status, created_at, id);

CREATE TABLE commerce.product_inventory (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    available_qty bigint NOT NULL,
    reserved_qty bigint NOT NULL,
    event_allocated_qty bigint NOT NULL,
    sold_qty bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_inventory_product UNIQUE (product_id),
    CONSTRAINT ck_product_inventory_quantities CHECK (
        available_qty >= 0 AND reserved_qty >= 0 AND event_allocated_qty >= 0 AND sold_qty >= 0
    )
);

CREATE TABLE commerce.inventory_movements (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    business_ref uuid NOT NULL,
    available_delta bigint NOT NULL,
    reserved_delta bigint NOT NULL,
    allocated_delta bigint NOT NULL,
    sold_delta bigint NOT NULL,
    reason varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_inventory_movements_business UNIQUE (product_id, operation, business_ref)
);
CREATE INDEX ix_inventory_movements_product_created
    ON commerce.inventory_movements (product_id, created_at, id);

CREATE TABLE commerce.inventory_reservations (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    event_id uuid,
    quantity integer NOT NULL,
    cycle_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_reservations_order UNIQUE (order_id),
    CONSTRAINT ck_inventory_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_reservations_status
        CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED', 'RESTORED'))
);
CREATE INDEX ix_inventory_reservations_status_expires
    ON commerce.inventory_reservations (status, expires_at, id);
CREATE INDEX ix_inventory_reservations_product_status
    ON commerce.inventory_reservations (product_id, status);

CREATE TABLE commerce.sale_events (
    id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    product_id uuid NOT NULL,
    name varchar(80) NOT NULL,
    event_price bigint NOT NULL,
    quota bigint NOT NULL,
    sold_count bigint NOT NULL,
    reserved_count bigint NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    published_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_sale_events_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_sale_events_event_price CHECK (event_price BETWEEN 1 AND 10000000),
    CONSTRAINT ck_sale_events_quota CHECK (quota > 0),
    CONSTRAINT ck_sale_events_counts CHECK (
        sold_count >= 0 AND reserved_count >= 0 AND sold_count + reserved_count <= quota
    ),
    CONSTRAINT ck_sale_events_period CHECK (starts_at < ends_at),
    CONSTRAINT ck_sale_events_status
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'OPEN', 'SOLD_OUT', 'CLOSED')),
    CONSTRAINT ck_sale_events_published CHECK ((status = 'DRAFT') = (published_at IS NULL)),
    CONSTRAINT ck_sale_events_closed
        CHECK ((status IN ('SOLD_OUT', 'CLOSED')) = (closed_at IS NOT NULL))
);
CREATE INDEX ix_sale_events_product_period ON commerce.sale_events (product_id, starts_at, ends_at);
CREATE INDEX ix_sale_events_status_period ON commerce.sale_events (status, starts_at, ends_at);
CREATE INDEX ix_sale_events_seller_created ON commerce.sale_events (seller_id, created_at, id);

CREATE TABLE commerce.event_participations (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    order_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_event_participations_order UNIQUE (order_id),
    CONSTRAINT ck_event_participations_status
        CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'REFUNDED'))
);
-- 구매자당 진행 중·확정 참여는 1건이다. 해제(RELEASED)된 참여만 기간 내 다시 참여할 수 있다.
CREATE UNIQUE INDEX uq_event_participations_active_buyer
    ON commerce.event_participations (event_id, buyer_id)
    WHERE status IN ('RESERVED', 'CONFIRMED', 'REFUNDED');
CREATE INDEX ix_event_participations_event_status
    ON commerce.event_participations (event_id, status);
CREATE INDEX ix_event_participations_buyer_created
    ON commerce.event_participations (buyer_id, created_at, id);

CREATE TABLE commerce.idempotency_records (
    id uuid PRIMARY KEY,
    actor_kind varchar(24) NOT NULL,
    actor_id varchar(100) NOT NULL,
    operation varchar(100) NOT NULL,
    key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    resource_id uuid,
    response_status integer,
    response_snapshot jsonb,
    status varchar(16) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_idempotency_records_key UNIQUE (actor_kind, actor_id, operation, key_hash),
    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('PENDING', 'COMPLETED'))
);
CREATE INDEX ix_idempotency_records_expires ON commerce.idempotency_records (expires_at);
CREATE INDEX ix_idempotency_records_resource ON commerce.idempotency_records (resource_id);

CREATE TABLE commerce.buyer_accounts (
    id               uuid         PRIMARY KEY,
    email_normalized varchar(254) NOT NULL,
    password_hash    varchar(255) NOT NULL,
    display_name     varchar(80)  NOT NULL,
    status           varchar(24)  NOT NULL,
    auth_version     bigint       NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL,
    deleted_at       timestamptz,

    CONSTRAINT uk_buyer_accounts_email UNIQUE (email_normalized),
    CONSTRAINT ck_buyer_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_buyer_accounts_deleted CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT ck_buyer_accounts_auth_version CHECK (auth_version >= 0)
);
CREATE INDEX ix_buyer_accounts_status ON commerce.buyer_accounts (status, id);

CREATE TABLE commerce.buyer_addresses (
    id                   uuid        PRIMARY KEY,
    buyer_id             uuid        NOT NULL,
    recipient_ciphertext bytea       NOT NULL,
    phone_ciphertext     bytea       NOT NULL,
    address_ciphertext   bytea       NOT NULL,
    is_default           boolean     NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    deleted_at           timestamptz,

    CONSTRAINT ck_buyer_addresses_deleted_default CHECK (deleted_at IS NULL OR NOT is_default)
);
CREATE UNIQUE INDEX uk_buyer_addresses_default ON commerce.buyer_addresses (buyer_id)
    WHERE is_default AND deleted_at IS NULL;
CREATE INDEX ix_buyer_addresses_buyer_created ON commerce.buyer_addresses (buyer_id, deleted_at, created_at, id);

CREATE TABLE commerce.seller_accounts (
    id                 uuid         PRIMARY KEY,
    email_normalized   varchar(254) NOT NULL,
    password_hash      varchar(255) NOT NULL,
    display_name       varchar(80)  NOT NULL,
    status             varchar(24)  NOT NULL,
    auth_version       bigint       NOT NULL DEFAULT 0,
    payout_account_ref varchar(100) NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL,
    deleted_at         timestamptz,

    CONSTRAINT uk_seller_accounts_email UNIQUE (email_normalized),
    CONSTRAINT ck_seller_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_seller_accounts_deleted CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT ck_seller_accounts_auth_version CHECK (auth_version >= 0)
);
CREATE INDEX ix_seller_accounts_status ON commerce.seller_accounts (status, id);

CREATE TABLE commerce.password_reset_tokens (
    id                  uuid        PRIMARY KEY,
    actor_kind          varchar(16) NOT NULL,
    actor_id            uuid        NOT NULL,
    purpose             varchar(16) NOT NULL,
    token_hash          char(64)    NOT NULL,
    expires_at          timestamptz NOT NULL,
    consumed_at         timestamptz,
    revoked_at          timestamptz,
    delivery_ciphertext bytea,
    delivery_status     varchar(16),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL,

    CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_tokens_actor_kind CHECK (actor_kind IN ('BUYER', 'SELLER')),
    CONSTRAINT ck_password_reset_tokens_purpose CHECK (purpose = 'PASSWORD_RESET'),
    CONSTRAINT ck_password_reset_tokens_closed CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT ck_password_reset_tokens_delivery CHECK (
        (delivery_ciphertext IS NULL) = (delivery_status IS NULL)
    )
);
CREATE INDEX ix_password_reset_tokens_actor
    ON commerce.password_reset_tokens (actor_kind, actor_id, purpose);
CREATE INDEX ix_password_reset_tokens_expires ON commerce.password_reset_tokens (expires_at);

CREATE TABLE commerce.audit_logs (
    id             uuid         PRIMARY KEY,
    actor_kind     varchar(16)  NOT NULL,
    actor_ref      varchar(100) NOT NULL,
    action         varchar(80)  NOT NULL,
    aggregate_type varchar(50)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    before_status  varchar(32),
    after_status   varchar(32),
    reason_code    varchar(80),
    event_id       uuid,
    correlation_id varchar(100) NOT NULL,
    occurred_at    timestamptz  NOT NULL,

    CONSTRAINT ck_audit_logs_actor_kind
        CHECK (actor_kind IN ('BUYER', 'SELLER', 'SERVICE', 'OPERATOR', 'SYSTEM'))
);
CREATE INDEX ix_audit_logs_aggregate
    ON commerce.audit_logs (aggregate_type, aggregate_id, occurred_at, id);
CREATE INDEX ix_audit_logs_actor ON commerce.audit_logs (actor_kind, actor_ref, occurred_at, id);
CREATE INDEX ix_audit_logs_correlation ON commerce.audit_logs (correlation_id);

CREATE TABLE commerce.inbox (
    id uuid PRIMARY KEY,
    consumer varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    raw_body bytea NOT NULL,
    body_hash char(64) NOT NULL,
    source_topic varchar(100) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    status varchar(24) NOT NULL CHECK (status IN ('RECEIVED', 'WAITING', 'APPLIED', 'REVIEW_REQUIRED')),
    next_run_at timestamptz,
    result_ref uuid,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (consumer, event_id),
    CHECK (status <> 'WAITING' OR next_run_at IS NOT NULL),
    CHECK (payload = convert_from(raw_body, 'UTF8')::jsonb),
    CHECK (jsonb_typeof(payload) = 'object' AND payload ?& ARRAY[
        'eventId','eventType','eventVersion','occurredAt','aggregateId','aggregateVersion','payload']),
    CHECK (jsonb_typeof(payload->'eventId')='string' AND jsonb_typeof(payload->'eventType')='string'
        AND jsonb_typeof(payload->'eventVersion')='number' AND jsonb_typeof(payload->'occurredAt')='string'
        AND jsonb_typeof(payload->'aggregateId')='string' AND jsonb_typeof(payload->'aggregateVersion')='number'
        AND jsonb_typeof(payload->'payload')='object'),
    CHECK ((payload ->> 'eventId')::uuid = event_id),
    CHECK (payload ->> 'eventType' = event_type),
    CHECK ((payload ->> 'eventVersion')::integer = event_version),
    CHECK ((payload ->> 'aggregateId')::uuid = aggregate_id),
    CHECK ((payload ->> 'aggregateVersion')::bigint = aggregate_version),
    CHECK ((payload ->> 'occurredAt')::timestamptz = occurred_at)
);
CREATE INDEX inbox_state ON commerce.inbox(status, created_at, id);
CREATE INDEX inbox_waiting ON commerce.inbox(consumer, next_run_at, id) WHERE status = 'WAITING';
CREATE INDEX inbox_applied_result ON commerce.inbox(consumer, result_ref, created_at, id) WHERE status = 'APPLIED';

CREATE TABLE commerce.inbox_conflicts (
    id uuid PRIMARY KEY,
    inbox_id uuid NOT NULL,
    consumer varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    raw_body bytea NOT NULL,
    body_hash char(64) NOT NULL,
    source_topic varchar(100) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    error_code varchar(80) NOT NULL DEFAULT 'EVENT_BODY_CONFLICT',
    status varchar(24) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status = 'REVIEW_REQUIRED'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (consumer, event_id, source_topic, source_partition, source_offset)
);
CREATE INDEX inbox_conflicts_event ON commerce.inbox_conflicts(consumer, event_id, created_at);

-- MOCK_KAKAO 거래 알림. 금융 상태와 수신자별 항목의 원자 저장을 위한 Commerce 소유 기록.
CREATE TABLE commerce.notification_deliveries (
    id uuid PRIMARY KEY,
    kind varchar(40) NOT NULL CHECK (kind IN ('PAYMENT_COMPLETED','SHIPMENT_STARTED','SHIPMENT_DELIVERED','PURCHASE_CONFIRMED','CANCELLATION_COMPLETED','RETURN_REQUESTED','RETURN_DECIDED','RETURN_RECEIVED','REFUND_COMPLETED','REFUND_ACTION_REQUIRED','SALE_COMPLETED','SETTLEMENT_READY','PAYOUT_COMPLETED','PAYOUT_ACTION_REQUIRED')),
    recipient_kind varchar(16) NOT NULL CHECK (recipient_kind IN ('BUYER','SELLER')),
    recipient_id uuid NOT NULL,
    channel varchar(24) NOT NULL CHECK (channel IN ('MOCK_KAKAO','KAKAO_SELF')),
    channel_account_ref char(64),
    dedup_key varchar(200) NOT NULL,
    item_count integer NOT NULL CHECK (item_count>=1),
    first_occurred_at timestamptz NOT NULL,
    total_amount bigint NOT NULL CHECK (total_amount>=0),
    template_version varchar(32) NOT NULL,
    title varchar(120) NOT NULL,
    body varchar(1000) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('COLLECTING','PENDING','SENDING','UNKNOWN','RETRY','SENT','REVIEW_REQUIRED')),
    bucket_start timestamptz,
    bucket_end timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 10),
    next_run_at timestamptz NOT NULL,
    lease_token uuid,
    lease_until timestamptz,
    last_error_code varchar(80),
    sent_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    UNIQUE NULLS NOT DISTINCT(channel,channel_account_ref,kind,recipient_kind,recipient_id,dedup_key),
    CHECK ((channel='MOCK_KAKAO' AND channel_account_ref IS NULL) OR (channel='KAKAO_SELF' AND channel_account_ref IS NOT NULL AND channel_account_ref ~ '^[0-9a-f]{64}$')),
    CHECK ((kind='SALE_COMPLETED')=(bucket_start IS NOT NULL AND bucket_end IS NOT NULL)),
    CHECK (bucket_start IS NULL OR bucket_end=bucket_start+interval '1 minute'),
    CHECK (kind='SALE_COMPLETED' OR (bucket_start IS NULL AND bucket_end IS NULL AND item_count=1 AND total_amount=0)),
    CHECK (status<>'COLLECTING' OR (kind='SALE_COMPLETED' AND body='')),
    CHECK ((lease_token IS NULL)=(lease_until IS NULL)),
    CHECK ((status='SENDING')=(lease_token IS NOT NULL)),
    CHECK (status<>'SENT' OR sent_at IS NOT NULL)
);
CREATE INDEX notification_deliveries_due ON commerce.notification_deliveries(status,next_run_at,lease_until,id);
CREATE INDEX notification_deliveries_recipient ON commerce.notification_deliveries(recipient_kind,recipient_id,created_at DESC,id DESC);

CREATE TABLE commerce.notification_items (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL,
    source_service varchar(16) NOT NULL CHECK (source_service IN ('commerce','payment','settlement')),
    source_event_id uuid NOT NULL,
    kind varchar(40) NOT NULL,
    business_ref uuid NOT NULL,
    recipient_kind varchar(16) NOT NULL CHECK (recipient_kind IN ('BUYER','SELLER')),
    recipient_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE(source_service,kind,business_ref,recipient_kind,recipient_id),
    CHECK (recipient_kind<>'SELLER' OR NOT (payload ?| ARRAY['mileageBalanceAfter','walletVersion']))
);
CREATE INDEX notification_items_delivery ON commerce.notification_items(delivery_id,occurred_at,id);
CREATE INDEX notification_items_event ON commerce.notification_items(source_event_id);

CREATE TABLE commerce.notification_attempts (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL,
    attempt_no integer NOT NULL CHECK (attempt_no>=1),
    operation varchar(16) NOT NULL CHECK (operation IN ('SEND','LOOKUP','REPLAY')),
    status varchar(24) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','RETRYABLE_FAILURE','PERMANENT_FAILURE','UNKNOWN')),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    error_code varchar(80),
    operator_ref varchar(100),
    reason varchar(200),
    UNIQUE(delivery_id,attempt_no),
    CHECK ((status='RUNNING')=(finished_at IS NULL)),
    CHECK (operation<>'REPLAY' OR (operator_ref IS NOT NULL AND reason IS NOT NULL))
);
CREATE INDEX notification_attempts_delivery ON commerce.notification_attempts(delivery_id,attempt_no);
CREATE INDEX notification_attempts_status ON commerce.notification_attempts(status,started_at);

CREATE TABLE commerce.notification_mock_receipts (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL UNIQUE,
    request_hash char(64) NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('ACCEPTED','REJECTED')),
    processed_at timestamptz NOT NULL
);

CREATE TABLE commerce.notification_inbox_quarantines (
    id uuid PRIMARY KEY,
    consumer varchar(100) NOT NULL CHECK (consumer='commerce-notifications.v1'),
    source_topic varchar(249) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition>=0),
    source_offset bigint NOT NULL CHECK (source_offset>=0),
    message_key text,
    raw_body bytea,
    error_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(consumer,source_topic,source_partition,source_offset)
);

-- 보존 작업의 재조회 지점과 처리 이력. 개인정보·알림 본문은 기록하지 않습니다.
CREATE TABLE commerce.notification_retention_batches (
    batch_id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    closed_at timestamptz,
    hold_reason varchar(200)
);

CREATE INDEX ix_notification_items_retention_batch ON commerce.notification_items((CAST(payload->>'batchId' AS uuid)),delivery_id) WHERE source_service='settlement';

CREATE TABLE commerce.retention_checks (
    target_kind varchar(16) NOT NULL CHECK (target_kind IN ('ORDER','DELIVERY','SETTLEMENT')),
    target_id uuid NOT NULL,
    result varchar(32) NOT NULL,
    checked_at timestamptz NOT NULL,
    next_run_at timestamptz NOT NULL,
    PRIMARY KEY(target_kind,target_id)
);
CREATE TABLE commerce.retention_history (
    id uuid PRIMARY KEY,
    target_kind varchar(16) NOT NULL CHECK (target_kind IN ('ORDER','DELIVERY','SETTLEMENT')),
    target_id uuid NOT NULL,
    result varchar(32) NOT NULL,
    checked_at timestamptz NOT NULL
);
CREATE INDEX ix_retention_history_target ON commerce.retention_history(target_kind,target_id,checked_at,id);

-- 삭제된 본문을 다시 만들지 않기 위한 최소 업무 멱등 기록.
CREATE TABLE commerce.notification_retired_keys (
    source_service varchar(16) NOT NULL,
    kind varchar(40) NOT NULL,
    business_ref uuid NOT NULL,
    recipient_kind varchar(16) NOT NULL,
    recipient_id uuid NOT NULL,
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash)=32),
    retired_at timestamptz NOT NULL,
    PRIMARY KEY(source_service,kind,business_ref,recipient_kind,recipient_id)
);

CREATE INDEX ix_notification_items_retention_order ON commerce.notification_items((CAST(payload->>'orderId' AS uuid)),delivery_id);
