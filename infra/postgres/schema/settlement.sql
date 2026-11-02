-- 배포 전 개발용 초기 DDL. 빈 서비스 DB에서 한 번 실행합니다.
CREATE SCHEMA settlement;

COMMENT ON SCHEMA settlement IS 'Settlement service owned schema';

CREATE TABLE settlement.settlement_items (
    id                     uuid        PRIMARY KEY,
    payment_id             uuid        NOT NULL,
    order_id               uuid        NOT NULL,
    product_id             uuid        NOT NULL,
    seller_id              uuid        NOT NULL,
    eligible_received_at   timestamptz,
    ready_at               timestamptz,
    total_amount           bigint      NOT NULL,
    platform_coupon_amount bigint      NOT NULL,
    settlement_base_amount bigint      NOT NULL,
    external_amount        bigint      NOT NULL,
    mileage_amount         bigint      NOT NULL,
    fee_amount             bigint      NOT NULL,
    net_amount             bigint      NOT NULL,
    status                 varchar(32) NOT NULL,
    purchase_confirmed_at  timestamptz,
    order_version          bigint      NOT NULL,
    payout_id              uuid,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL,

    CONSTRAINT uk_settlement_items_payment_id UNIQUE (payment_id),
    CONSTRAINT ck_settlement_items_status
        CHECK (status IN ('WAITING_CONFIRMATION', 'PAYABLE', 'REFUND_RESERVED', 'REFUNDED',
            'PAYOUT_RESERVED', 'PAID')),
    CONSTRAINT ck_settlement_items_non_negative_amounts
        CHECK (total_amount >= 0 AND platform_coupon_amount >= 0 AND settlement_base_amount >= 0
            AND external_amount >= 0 AND mileage_amount >= 0 AND fee_amount >= 0
            AND net_amount >= 0),
    CONSTRAINT ck_settlement_items_payment_split
        CHECK (total_amount = external_amount + mileage_amount),
    CONSTRAINT ck_settlement_items_settlement_base
        CHECK (settlement_base_amount = total_amount + platform_coupon_amount),
    CONSTRAINT ck_settlement_items_fee_net
        CHECK (settlement_base_amount = fee_amount + net_amount),
    CONSTRAINT ck_settlement_items_fee
        CHECK (fee_amount = settlement_base_amount * 15 / 100),
    CONSTRAINT ck_settlement_items_ready_at
        CHECK (ready_at IS NULL
            OR (purchase_confirmed_at IS NOT NULL AND eligible_received_at IS NOT NULL
                AND ready_at = GREATEST(purchase_confirmed_at, eligible_received_at))),
    CONSTRAINT ck_settlement_items_version CHECK (version >= 0)
);

CREATE INDEX ix_settlement_items_seller_status_ready
    ON settlement.settlement_items (seller_id, status, ready_at, id);
CREATE INDEX ix_settlement_items_payout_id ON settlement.settlement_items (payout_id);

-- 판매자·마감 날짜·정책 버전별 정산 배치. 대상·합계를 고정한 뒤 항목을 추가하지 않는다.
CREATE TABLE settlement.settlement_batches (
    id             uuid        PRIMARY KEY,
    seller_id      uuid        NOT NULL,
    batch_date     date        NOT NULL,
    cutoff_at      timestamptz NOT NULL,
    policy_version varchar(32) NOT NULL,
    status         varchar(24) NOT NULL,
    net_amount     bigint      NOT NULL,
    closed_at      timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL,

    CONSTRAINT uk_settlement_batches_seller_date_policy
        UNIQUE (seller_id, batch_date, policy_version),
    CONSTRAINT ck_settlement_batches_cutoff
        CHECK (cutoff_at = (batch_date + time '10:00') AT TIME ZONE 'Asia/Seoul'),
    CONSTRAINT ck_settlement_batches_net_amount CHECK (net_amount >= 0),
    CONSTRAINT ck_settlement_batches_status
        CHECK (status IN ('CREATED', 'RESERVED', 'PROCESSING', 'COMPLETED', 'REVIEW_REQUIRED')),
    CONSTRAINT ck_settlement_batches_version CHECK (version >= 0),
    CONSTRAINT ck_settlement_batches_closed CHECK ((closed_at IS NOT NULL) = (status = 'COMPLETED'))
);

CREATE INDEX ix_settlement_batches_status_created
    ON settlement.settlement_batches (status, created_at, id);

-- 배치마다 하나인 지급. 수취·출금 계정과 금융 시나리오 버전은 생성 때 고정한다.
CREATE TABLE settlement.payouts (
    id                        uuid         PRIMARY KEY,
    batch_id                  uuid         NOT NULL,
    seller_id                 uuid         NOT NULL,
    recipient_account_ref     varchar(100) NOT NULL,
    recipient_account_version bigint       NOT NULL,
    funding_account_ref       varchar(100) NOT NULL,
    scenario_version          varchar(32)  NOT NULL,
    succeeded_at              timestamptz,
    unknown_since             timestamptz,
    amount                    bigint       NOT NULL,
    business_key              uuid         NOT NULL,
    external_ref              uuid,
    status                    varchar(24)  NOT NULL,
    hold_reason               varchar(32),
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    version                   bigint       NOT NULL,

    CONSTRAINT uk_payouts_batch_id UNIQUE (batch_id),
    CONSTRAINT uk_payouts_business_key UNIQUE (business_key),
    CONSTRAINT ck_payouts_amount CHECK (amount > 0),
    CONSTRAINT ck_payouts_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'DECLINED')),
    CONSTRAINT ck_payouts_hold_reason
        CHECK (hold_reason IS NULL OR hold_reason IN ('UNKNOWN', 'CONFIRMED_REJECTION')),
    CONSTRAINT ck_payouts_succeeded_at
        CHECK (status <> 'SUCCEEDED' OR succeeded_at IS NOT NULL),
    CONSTRAINT ck_payouts_version CHECK (version >= 0)
);

CREATE INDEX ix_payouts_seller_created ON settlement.payouts (seller_id, created_at, id);
CREATE INDEX ix_payouts_status_updated ON settlement.payouts (status, updated_at, id);

-- 지급 실행 작업. 지급 Worker가 임대해 한 건씩 실행한다.
CREATE TABLE settlement.payout_jobs (
    id           uuid         PRIMARY KEY,
    payout_id    uuid         NOT NULL,
    business_key uuid         NOT NULL,
    status       varchar(24)  NOT NULL,
    attempts     integer      NOT NULL,
    next_run_at  timestamptz  NOT NULL,
    lease_owner  varchar(100),
    lease_token  uuid,
    lease_until  timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL,

    CONSTRAINT uk_payout_jobs_payout_id UNIQUE (payout_id),
    CONSTRAINT uk_payout_jobs_business_key UNIQUE (business_key),
    CONSTRAINT ck_payout_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'REVIEW_REQUIRED', 'DONE')),
    CONSTRAINT ck_payout_jobs_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_payout_jobs_version CHECK (version >= 0)
);

CREATE INDEX ix_payout_jobs_due ON settlement.payout_jobs (status, next_run_at, lease_until, id);

-- 항목별 환불·지급 예약. 한 항목에는 ACTIVE 예약이 하나뿐이라 환불과 지급이 배타적이다.
CREATE TABLE settlement.settlement_reservations (
    id                   uuid        PRIMARY KEY,
    item_id              uuid        NOT NULL,
    previous_item_status varchar(32) NOT NULL,
    kind                 varchar(16) NOT NULL,
    business_ref         uuid        NOT NULL,
    amount               bigint      NOT NULL,
    status               varchar(16) NOT NULL,
    payout_id            uuid,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL,

    CONSTRAINT uk_settlement_reservations_item_kind_ref UNIQUE (item_id, kind, business_ref),
    CONSTRAINT ck_settlement_reservations_kind CHECK (kind IN ('REFUND', 'PAYOUT')),
    CONSTRAINT ck_settlement_reservations_status
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_settlement_reservations_amount CHECK (amount >= 0),
    CONSTRAINT ck_settlement_reservations_payout
        CHECK (kind <> 'PAYOUT' OR payout_id IS NOT NULL),
    CONSTRAINT ck_settlement_reservations_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_settlement_reservations_active_item
    ON settlement.settlement_reservations (item_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_settlement_reservations_kind_ref
    ON settlement.settlement_reservations (kind, business_ref, status);

-- 정산 원장. 결제 1건의 SOURCE(E·M·P)와 ALLOCATION(판매자·수수료)을 0원 leg까지 기록하고 각 측 합계는 B다.
-- 환불 상쇄는 원 leg마다 반대 부호 1건(reversal_of)으로 남긴다.
CREATE TABLE settlement.settlement_entries (
    id                 uuid        PRIMARY KEY,
    item_id            uuid        NOT NULL,
    payment_id         uuid        NOT NULL,
    side               varchar(16) NOT NULL,
    leg                varchar(24) NOT NULL,
    amount             bigint      NOT NULL,
    business_ref       uuid        NOT NULL,
    source_event_id    uuid        NOT NULL,
    source_occurred_at timestamptz NOT NULL,
    reversal_of        uuid,
    policy_version     varchar(32) NOT NULL,
    created_at         timestamptz NOT NULL,

    CONSTRAINT uk_settlement_entries_payment_leg_ref UNIQUE (payment_id, leg, business_ref),
    CONSTRAINT ck_settlement_entries_side_leg
        CHECK ((side = 'SOURCE' AND leg IN ('EXTERNAL', 'MILEAGE', 'PLATFORM_COUPON'))
            OR (side = 'ALLOCATION' AND leg IN ('SELLER', 'FEE'))),
    CONSTRAINT ck_settlement_entries_original_amount CHECK (reversal_of IS NOT NULL OR amount >= 0)
);

CREATE UNIQUE INDEX uk_settlement_entries_reversal_of
    ON settlement.settlement_entries (reversal_of) WHERE reversal_of IS NOT NULL;
CREATE INDEX ix_settlement_entries_item_created ON settlement.settlement_entries (item_id, created_at, id);
CREATE INDEX ix_settlement_entries_payment_side ON settlement.settlement_entries (payment_id, side);
CREATE INDEX ix_settlement_entries_source_occurred
    ON settlement.settlement_entries (source_occurred_at, payment_id, id);
CREATE INDEX ix_settlement_entries_source_event ON settlement.settlement_entries (source_event_id);

-- 수신 이벤트. Payment와 같은 컬럼·제약이다. 선행 결제가 없는 구매확정은 WAITING으로 남겨 처리기가 다시 판정하고,
-- Commerce Inbox와 같이 attempts·last_error_code로 10회 상한을 둔다.
CREATE TABLE settlement.inbox (
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
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code varchar(80),
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
CREATE INDEX inbox_state ON settlement.inbox(status, created_at, id);
CREATE INDEX inbox_waiting ON settlement.inbox(consumer, next_run_at, id) WHERE status = 'WAITING';
-- 같은 환불의 원요청 조회(먼저 받은 같은 refundId의 예약 요청). Listener가 refundId 형식을 검사한 뒤 저장한다.
CREATE INDEX inbox_refund_request_lookup ON settlement.inbox
    (consumer, ((payload->'payload'->>'refundId')::uuid), created_at, id)
    WHERE event_type = 'RefundReservationRequested';

CREATE TABLE settlement.inbox_conflicts (
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
CREATE INDEX inbox_conflicts_event ON settlement.inbox_conflicts(consumer, event_id, created_at);

-- 해석할 수 없는 대상 이벤트의 원문 격리와 DLT 발행 작업. Payment와 같은 컬럼·제약이다. null과 빈 bytes를 구분한다.
CREATE TABLE settlement.inbox_quarantines (
    id uuid PRIMARY KEY,
    consumer varchar(100) NOT NULL,
    source_topic varchar(249) NOT NULL,
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    message_key bytea,
    raw_body bytea,
    error_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (consumer, source_topic, source_partition, source_offset)
);

CREATE TABLE settlement.inbox_quarantine_deliveries (
    id uuid PRIMARY KEY,
    quarantine_id uuid NOT NULL,
    kind varchar(8) NOT NULL CHECK (kind IN ('DLT', 'REPLAY')),
    topic varchar(249) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'PUBLISHED', 'REVIEW_REQUIRED')),
    operator_id varchar(100),
    reason_code varchar(80),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_run_at timestamptz NOT NULL,
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((kind = 'DLT' AND operator_id IS NULL AND reason_code IS NULL)
        OR (kind = 'REPLAY' AND operator_id IS NOT NULL AND reason_code IS NOT NULL))
);
CREATE UNIQUE INDEX uk_quarantine_dlt ON settlement.inbox_quarantine_deliveries (quarantine_id) WHERE kind = 'DLT';
CREATE UNIQUE INDEX uk_quarantine_replay_active ON settlement.inbox_quarantine_deliveries (quarantine_id)
    WHERE kind = 'REPLAY' AND status IN ('PENDING', 'RUNNING', 'RETRY');
CREATE INDEX ix_quarantine_delivery_due ON settlement.inbox_quarantine_deliveries (status, next_run_at, lease_until, id);

CREATE TABLE settlement.inbox_quarantine_resumes (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL,
    expected_version bigint NOT NULL CHECK (expected_version >= 0),
    previous_attempts integer NOT NULL CHECK (previous_attempts >= 0),
    operator_id varchar(100) NOT NULL,
    reason_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (delivery_id, expected_version)
);

-- Settlement 발행 Outbox. Payment·Commerce outbox와 같은 구조다(ADR 0005).
CREATE TABLE settlement.outbox (
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
CREATE INDEX outbox_due ON settlement.outbox(status, next_run_at, lease_until, id);
CREATE INDEX outbox_occurred ON settlement.outbox(occurred_at, created_at, id);
CREATE INDEX outbox_aggregate ON settlement.outbox(aggregate_id, aggregate_version, sequence_no);

-- Spring Batch 5.2 메타데이터. 라이브러리의 schema-postgresql.sql에서 서비스 내부 FK만 뺐다(ADR 0006).
-- Spring Batch가 시각을 LocalDateTime으로 쓰므로 원본의 timestamp를 유지한다.
CREATE TABLE settlement.BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT       NOT NULL PRIMARY KEY,
    VERSION         BIGINT,
    JOB_NAME        VARCHAR(100) NOT NULL,
    JOB_KEY         VARCHAR(32)  NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE settlement.BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT        NOT NULL PRIMARY KEY,
    VERSION          BIGINT,
    JOB_INSTANCE_ID  BIGINT        NOT NULL,
    CREATE_TIME      TIMESTAMP     NOT NULL,
    START_TIME       TIMESTAMP DEFAULT NULL,
    END_TIME         TIMESTAMP DEFAULT NULL,
    STATUS           VARCHAR(10),
    EXIT_CODE        VARCHAR(2500),
    EXIT_MESSAGE     VARCHAR(2500),
    LAST_UPDATED     TIMESTAMP
);

CREATE TABLE settlement.BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT        NOT NULL,
    PARAMETER_NAME   VARCHAR(100)  NOT NULL,
    PARAMETER_TYPE   VARCHAR(100)  NOT NULL,
    PARAMETER_VALUE  VARCHAR(2500),
    IDENTIFYING      CHAR(1)       NOT NULL
);

CREATE TABLE settlement.BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID  BIGINT        NOT NULL PRIMARY KEY,
    VERSION            BIGINT        NOT NULL,
    STEP_NAME          VARCHAR(100)  NOT NULL,
    JOB_EXECUTION_ID   BIGINT        NOT NULL,
    CREATE_TIME        TIMESTAMP     NOT NULL,
    START_TIME         TIMESTAMP DEFAULT NULL,
    END_TIME           TIMESTAMP DEFAULT NULL,
    STATUS             VARCHAR(10),
    COMMIT_COUNT       BIGINT,
    READ_COUNT         BIGINT,
    FILTER_COUNT       BIGINT,
    WRITE_COUNT        BIGINT,
    READ_SKIP_COUNT    BIGINT,
    WRITE_SKIP_COUNT   BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT     BIGINT,
    EXIT_CODE          VARCHAR(2500),
    EXIT_MESSAGE       VARCHAR(2500),
    LAST_UPDATED       TIMESTAMP
);

CREATE TABLE settlement.BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID  BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT
);

CREATE TABLE settlement.BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID   BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT
);

CREATE SEQUENCE settlement.BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE settlement.BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE settlement.BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
