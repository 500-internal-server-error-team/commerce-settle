-- 배포 전 개발용 초기 DDL. 빈 서비스 DB에서 한 번 실행합니다.
CREATE SCHEMA commerce;

COMMENT ON SCHEMA commerce IS 'Commerce service owned schema';

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
