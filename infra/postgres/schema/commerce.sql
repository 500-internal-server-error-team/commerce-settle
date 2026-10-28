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
