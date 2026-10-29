-- 배포 전 개발용 초기 DDL. 빈 서비스 DB에서 한 번 실행합니다.
CREATE SCHEMA payment;

COMMENT ON SCHEMA payment IS 'Payment service owned schema';

CREATE TABLE payment.payments (
    id                     uuid        PRIMARY KEY,
    order_id               uuid        NOT NULL,
    buyer_id               uuid        NOT NULL,
    seller_id              uuid        NOT NULL,
    order_version          bigint      NOT NULL,
    product_id             uuid        NOT NULL,
    current_cycle_id       uuid        NOT NULL,
    gross_amount           bigint      NOT NULL,
    coupon_amount          bigint      NOT NULL,
    seller_coupon_amount   bigint      NOT NULL,
    platform_coupon_amount bigint      NOT NULL,
    settlement_base_amount bigint      NOT NULL,
    total_amount           bigint      NOT NULL,
    mileage_amount         bigint      NOT NULL,
    external_amount        bigint      NOT NULL,
    fee_amount             bigint      NOT NULL,
    seller_net_amount      bigint      NOT NULL,
    status                 varchar(32) NOT NULL,
    confirmed_at           timestamptz,
    policy_version         varchar(32) NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL,

    CONSTRAINT uk_payments_order_id UNIQUE (order_id),
    CONSTRAINT ck_payments_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'CONFIRMED', 'DECLINED', 'UNKNOWN', 'CANCELLED')),
    CONSTRAINT ck_payments_non_negative_amounts
        CHECK (gross_amount >= 0 AND coupon_amount >= 0 AND seller_coupon_amount >= 0
            AND platform_coupon_amount >= 0 AND settlement_base_amount >= 0 AND total_amount >= 0
            AND mileage_amount >= 0 AND external_amount >= 0 AND fee_amount >= 0
            AND seller_net_amount >= 0),
    CONSTRAINT ck_payments_settlement_base
        CHECK (settlement_base_amount = gross_amount - seller_coupon_amount),
    CONSTRAINT ck_payments_total
        CHECK (total_amount = settlement_base_amount - platform_coupon_amount AND total_amount >= 1),
    CONSTRAINT ck_payments_payment_split
        CHECK (total_amount = external_amount + mileage_amount),
    CONSTRAINT ck_payments_coupon_amount
        CHECK (coupon_amount = seller_coupon_amount + platform_coupon_amount),
    CONSTRAINT ck_payments_single_coupon_funder
        CHECK (seller_coupon_amount = 0 OR platform_coupon_amount = 0),
    CONSTRAINT ck_payments_coupon_excludes_mileage
        CHECK (coupon_amount = 0 OR mileage_amount = 0),
    CONSTRAINT ck_payments_fee
        CHECK (fee_amount = settlement_base_amount * 15 / 100),
    CONSTRAINT ck_payments_seller_net
        CHECK (seller_net_amount = settlement_base_amount - fee_amount),
    CONSTRAINT ck_payments_version CHECK (version >= 0)
);

CREATE INDEX ix_payments_buyer_created ON payment.payments (buyer_id, created_at, id);
CREATE INDEX ix_payments_status_updated ON payment.payments (status, updated_at, id);

CREATE TABLE payment.inbox (
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
CREATE INDEX inbox_state ON payment.inbox(status, created_at, id);
CREATE INDEX inbox_waiting ON payment.inbox(consumer, next_run_at, id) WHERE status = 'WAITING';

CREATE TABLE payment.inbox_conflicts (
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
CREATE INDEX inbox_conflicts_event ON payment.inbox_conflicts(consumer, event_id, created_at);

CREATE TABLE payment.payment_jobs (
    id              uuid         PRIMARY KEY,
    payment_id      uuid         NOT NULL,
    refund_id       uuid,
    operation       varchar(32)  NOT NULL,
    business_key    uuid         NOT NULL,
    status          varchar(24)  NOT NULL,
    attempts        integer      NOT NULL,
    next_run_at     timestamptz  NOT NULL,
    lease_owner     varchar(100),
    lease_token     uuid,
    lease_until     timestamptz,
    last_error_code varchar(80),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL,

    CONSTRAINT uk_payment_jobs_operation_business_key UNIQUE (operation, business_key),
    CONSTRAINT ck_payment_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'REVIEW_REQUIRED', 'DONE')),
    CONSTRAINT ck_payment_jobs_lease_together
        CHECK ((lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
            OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_payment_jobs_counts CHECK (attempts >= 0 AND version >= 0)
);
CREATE INDEX ix_payment_jobs_status_next_run ON payment.payment_jobs (status, next_run_at, lease_until, id);
