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
    order_accepted_at timestamptz,
    trace_parent varchar(55),
    observation_state varchar(16),
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
    -- 롤백된 처리 시도 횟수와 마지막 실패·업무 거절 사유. 처리에 성공해도 지우지 않는다.
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (consumer, event_id),
    CHECK (status <> 'WAITING' OR next_run_at IS NOT NULL),
    CHECK (status <> 'REVIEW_REQUIRED' OR last_error_code IS NOT NULL),
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
CREATE INDEX inbox_request_result ON payment.inbox(consumer, result_ref, created_at, id)
    WHERE result_ref IS NOT NULL AND event_type = 'OrderPaymentRequested';
CREATE INDEX inbox_order_cycle ON payment.inbox(consumer, aggregate_id, (payload -> 'payload' ->> 'paymentCycleId'), created_at, id)
    WHERE event_type IN ('OrderPaymentRequested', 'PaymentCancellationRequested');

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
    trace_parent varchar(55),
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
CREATE INDEX ix_payment_jobs_created ON payment.payment_jobs (created_at, id);
-- 작업 선점은 종류별 대기·재시도·임대 작업만 next_run_at 순서로 본다.
CREATE INDEX ix_payment_jobs_lease ON payment.payment_jobs (operation, next_run_at, id)
    WHERE status IN ('PENDING','RETRY','RUNNING');

-- 외부 승인 시도. 외부 호출 전에 provider·결제수단·계정·재원·금액·시나리오를 고정해 재전송·조회에 같은 값을 쓴다.
CREATE TABLE payment.payment_attempts (
    id                  uuid         PRIMARY KEY,
    payment_id          uuid         NOT NULL,
    attempt_no          integer      NOT NULL,
    cycle_id            uuid         NOT NULL,
    provider_id         varchar(16)  NOT NULL,
    method              varchar(24)  NOT NULL,
    account_ref         varchar(100) NOT NULL,
    funding_account_ref varchar(100) NOT NULL,
    business_key        uuid         NOT NULL,
    external_ref        uuid,
    amount              bigint       NOT NULL,
    status              varchar(24)  NOT NULL,
    scenario_version    varchar(32)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL,

    CONSTRAINT uk_payment_attempts_payment_attempt UNIQUE (payment_id, attempt_no),
    CONSTRAINT uk_payment_attempts_provider_business_key UNIQUE (provider_id, business_key),
    CONSTRAINT ck_payment_attempts_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'APPROVED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT ck_payment_attempts_provider CHECK (provider_id IN ('PG_A', 'PG_B', 'PG_C', 'EASY_PAY')),
    CONSTRAINT ck_payment_attempts_method CHECK (method IN ('CREDIT_CARD', 'DEBIT_CARD', 'EASY_PAY')),
    CONSTRAINT ck_payment_attempts_counts CHECK (attempt_no > 0 AND amount > 0 AND version >= 0)
);
CREATE INDEX ix_payment_attempts_status_updated ON payment.payment_attempts (status, updated_at, id);
CREATE UNIQUE INDEX uk_payment_attempts_active ON payment.payment_attempts (payment_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'APPROVED');

CREATE TABLE payment.mileage_wallets (
    id              uuid        PRIMARY KEY,
    buyer_id        uuid        NOT NULL,
    balance         bigint      NOT NULL,
    reserved_amount bigint      NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL,

    CONSTRAINT uk_mileage_wallets_buyer UNIQUE (buyer_id),
    CONSTRAINT ck_mileage_wallets_reserved
        CHECK (reserved_amount >= 0 AND reserved_amount <= balance),
    CONSTRAINT ck_mileage_wallets_version CHECK (version >= 0)
);

CREATE TABLE payment.mileage_reservations (
    id         uuid        PRIMARY KEY,
    wallet_id  uuid        NOT NULL,
    payment_id uuid        NOT NULL,
    cycle_id   uuid        NOT NULL,
    amount     bigint      NOT NULL,
    status     varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL,

    CONSTRAINT uk_mileage_reservations_payment_cycle UNIQUE (payment_id, cycle_id),
    CONSTRAINT ck_mileage_reservations_amount CHECK (amount > 0),
    CONSTRAINT ck_mileage_reservations_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_mileage_reservations_version CHECK (version >= 0)
);
CREATE INDEX ix_mileage_reservations_wallet_status ON payment.mileage_reservations (wallet_id, status);
CREATE UNIQUE INDEX uk_mileage_reservations_reserved ON payment.mileage_reservations (payment_id)
    WHERE status='RESERVED';
CREATE INDEX ix_mileage_reservations_status_updated ON payment.mileage_reservations (status, updated_at, id);

CREATE TABLE payment.mileage_entries (
    id            uuid        PRIMARY KEY,
    wallet_id     uuid        NOT NULL,
    payment_id    uuid,
    refund_id     uuid,
    kind          varchar(16) NOT NULL,
    business_ref  uuid        NOT NULL,
    amount        bigint      NOT NULL,
    balance_after bigint      NOT NULL,
    created_at    timestamptz NOT NULL,

    CONSTRAINT uk_mileage_entries_wallet_kind_ref UNIQUE (wallet_id, kind, business_ref),
    CONSTRAINT ck_mileage_entries_kind CHECK (kind IN ('SEED', 'RESERVE', 'RELEASE', 'DEBIT', 'RESTORE', 'ACCRUAL')),
    CONSTRAINT ck_mileage_entries_amount CHECK (amount > 0),
    CONSTRAINT ck_mileage_entries_balance_after CHECK (balance_after >= 0)
);
CREATE INDEX ix_mileage_entries_wallet_created ON payment.mileage_entries (wallet_id, created_at, id);
CREATE UNIQUE INDEX uk_mileage_entries_payment_once
    ON payment.mileage_entries (payment_id, kind) WHERE kind IN ('DEBIT', 'RESTORE', 'ACCRUAL');

-- 구매 적립. 결제 완료 반영 때 Commerce가 고정한 정책 사본으로 PENDING을 만들고, 구매확정 때 AVAILABLE로 잔액에 더한다.
-- 확정 전 환불이 완료되면 CANCELLED다. 결제당 한 건이며 금액은 정책 사본으로 다시 계산한 값과 같아야 한다.
CREATE TABLE payment.mileage_accruals (
    id                     uuid        PRIMARY KEY,
    payment_id             uuid        NOT NULL,
    order_id               uuid        NOT NULL,
    buyer_id               uuid        NOT NULL,
    base_amount            bigint      NOT NULL,
    rate_basis_points      integer     NOT NULL,
    per_order_cap          bigint      NOT NULL,
    accrual_amount         bigint      NOT NULL,
    policy_version         varchar(40) NOT NULL,
    status                 varchar(16) NOT NULL,
    payment_confirmed_at   timestamptz NOT NULL,
    purchase_confirmed_at  timestamptz,
    available_at           timestamptz,
    cancelled_at           timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL,

    CONSTRAINT uk_mileage_accruals_payment UNIQUE (payment_id),
    CONSTRAINT ck_mileage_accruals_policy CHECK (
        base_amount > 0 AND rate_basis_points BETWEEN 1 AND 10000 AND per_order_cap > 0
        AND accrual_amount > 0
        AND accrual_amount = least(per_order_cap, (base_amount * rate_basis_points) / 10000)),
    CONSTRAINT ck_mileage_accruals_status CHECK (
        (status = 'PENDING' AND available_at IS NULL AND cancelled_at IS NULL AND purchase_confirmed_at IS NULL)
        OR (status = 'AVAILABLE' AND available_at IS NOT NULL AND purchase_confirmed_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND available_at IS NULL AND purchase_confirmed_at IS NULL)),
    CONSTRAINT ck_mileage_accruals_version CHECK (version >= 0)
);
CREATE INDEX ix_mileage_accruals_buyer_status ON payment.mileage_accruals (buyer_id, status);

-- 원결제의 전액 환불. 외부 결제액·사용 마일리지는 원결제에서 복사하며 바꾸지 않는다.
CREATE TABLE payment.refunds (
    id                      uuid        PRIMARY KEY,
    payment_id              uuid        NOT NULL,
    order_refund_request_id uuid        NOT NULL,
    external_amount         bigint      NOT NULL,
    mileage_amount          bigint      NOT NULL,
    status                  varchar(32) NOT NULL,
    business_key            uuid        NOT NULL,
    external_status         varchar(24) NOT NULL,
    external_ref            uuid,
    mileage_restored_at     timestamptz,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    version                 bigint      NOT NULL,

    CONSTRAINT uk_refunds_order_refund_request UNIQUE (order_refund_request_id),
    CONSTRAINT uk_refunds_business_key UNIQUE (business_key),
    CONSTRAINT ck_refunds_status CHECK (status IN ('RESERVING', 'EXTERNAL_PENDING', 'RESTORING',
        'INTERNAL_PENDING', 'COMPLETED', 'COMPENSATING', 'DECLINED', 'UNKNOWN')),
    CONSTRAINT ck_refunds_external_status CHECK (external_status IN ('NOT_REQUIRED', 'PENDING',
        'REQUESTED', 'SUCCEEDED', 'DECLINED', 'UNKNOWN')),
    CONSTRAINT ck_refunds_amounts
        CHECK (external_amount >= 0 AND mileage_amount >= 0 AND external_amount + mileage_amount >= 1),
    -- 외부 결제액이 없으면 외부 환불을 호출하지 않는다.
    CONSTRAINT ck_refunds_external_required
        CHECK ((external_amount = 0) = (external_status = 'NOT_REQUIRED')),
    CONSTRAINT ck_refunds_external_ref
        CHECK (external_status <> 'SUCCEEDED' OR external_ref IS NOT NULL),
    CONSTRAINT ck_refunds_restored
        CHECK (status NOT IN ('INTERNAL_PENDING', 'COMPLETED') OR mileage_restored_at IS NOT NULL),
    CONSTRAINT ck_refunds_version CHECK (version >= 0)
);
-- 같은 원결제에 진행 중이거나 완료된 환불은 하나뿐이다. 확정 거절(DECLINED) 뒤에만 새 환불을 받는다.
CREATE UNIQUE INDEX uk_refunds_payment_active ON payment.refunds (payment_id)
    WHERE status IN ('RESERVING', 'EXTERNAL_PENDING', 'RESTORING', 'INTERNAL_PENDING', 'COMPLETED',
        'UNKNOWN', 'COMPENSATING');
CREATE INDEX ix_refunds_payment_created ON payment.refunds (payment_id, created_at, id);
CREATE INDEX ix_refunds_status_updated ON payment.refunds (status, updated_at, id);

-- 환불 하나의 정산 예약·외부 환불·마일리지 복원·원장 상쇄·주문 복구 진행.
CREATE TABLE payment.refund_sagas (
    id                 uuid        PRIMARY KEY,
    refund_id          uuid        NOT NULL,
    step               varchar(40) NOT NULL,
    reservation_ref    uuid,
    ledger_reversed_at timestamptz,
    order_restored_at  timestamptz,
    last_error_code    varchar(80),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL,

    CONSTRAINT uk_refund_sagas_refund UNIQUE (refund_id),
    CONSTRAINT ck_refund_sagas_step CHECK (step IN ('RESERVATION_REQUESTED', 'EXTERNAL_REFUND_PENDING',
        'MILEAGE_RESTORE_PENDING', 'LEDGER_REVERSAL_PENDING', 'ORDER_RESTORE_PENDING', 'COMPLETED',
        'RELEASE_PENDING', 'FAILED')),
    CONSTRAINT ck_refund_sagas_reserved CHECK (step IN ('RESERVATION_REQUESTED', 'FAILED')
        OR reservation_ref IS NOT NULL),
    CONSTRAINT ck_refund_sagas_ledger CHECK (step NOT IN ('ORDER_RESTORE_PENDING', 'COMPLETED')
        OR ledger_reversed_at IS NOT NULL),
    CONSTRAINT ck_refund_sagas_order CHECK (step <> 'COMPLETED' OR order_restored_at IS NOT NULL),
    CONSTRAINT ck_refund_sagas_version CHECK (version >= 0)
);
CREATE INDEX ix_refund_sagas_step_updated ON payment.refund_sagas (step, updated_at, id);

CREATE TABLE payment.outbox (
    order_accepted_at timestamptz,
    trace_parent varchar(55),
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
CREATE INDEX outbox_due ON payment.outbox(status, next_run_at, lease_until, id);
-- 발행기 선점은 미발행 행만 sequence_no 순서로 본다. 발행 완료 이력이 늘어도 선점 비용이 커지지 않게 한다.
CREATE INDEX outbox_unpublished ON payment.outbox(sequence_no) WHERE status IN ('PENDING','RUNNING','RETRY');
CREATE INDEX outbox_unpublished_attempts ON payment.outbox(attempts, sequence_no) WHERE status IN ('PENDING','RUNNING','RETRY');
CREATE INDEX outbox_occurred ON payment.outbox(occurred_at, created_at, id);
CREATE INDEX outbox_aggregate ON payment.outbox(aggregate_id, aggregate_version, sequence_no);
-- 정산 대조 피드는 금융 이벤트 두 종류만 created_at DESC,id DESC keyset으로 읽는다.
CREATE INDEX ix_outbox_reconciliation ON payment.outbox(created_at, id)
    WHERE event_type IN ('PaymentConfirmed','RefundFinancialCompleted');

-- 유효한 Envelope로 해석할 수 없는 bytes도 보존한다. null과 빈 bytes를 구분한다.
CREATE TABLE payment.inbox_quarantines (
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

CREATE TABLE payment.inbox_quarantine_deliveries (
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
CREATE UNIQUE INDEX uk_quarantine_dlt ON payment.inbox_quarantine_deliveries (quarantine_id) WHERE kind = 'DLT';
CREATE UNIQUE INDEX uk_quarantine_replay_active ON payment.inbox_quarantine_deliveries (quarantine_id)
    WHERE kind = 'REPLAY' AND status IN ('PENDING', 'RUNNING', 'RETRY');
CREATE INDEX ix_quarantine_delivery_due ON payment.inbox_quarantine_deliveries (status, next_run_at, lease_until, id);
-- 운영 조회: 원문 목록 정렬과 원문별 작업 조회.
CREATE INDEX ix_quarantine_created ON payment.inbox_quarantines (created_at, id);
CREATE INDEX ix_quarantine_delivery_quarantine ON payment.inbox_quarantine_deliveries (quarantine_id, created_at, id);

CREATE TABLE payment.inbox_quarantine_resumes (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL,
    expected_version bigint NOT NULL CHECK (expected_version >= 0),
    previous_attempts integer NOT NULL CHECK (previous_attempts >= 0),
    operator_id varchar(100) NOT NULL,
    reason_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (delivery_id, expected_version)
);

-- 운영 요청의 멱등 기록. 키와 본문 원문 대신 SHA-256만 저장하고, 금융 작업 재처리 키는 만료시키지 않는다.
CREATE TABLE payment.idempotency_records (
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
CREATE INDEX ix_idempotency_records_expires ON payment.idempotency_records (expires_at);
CREATE INDEX ix_idempotency_records_resource ON payment.idempotency_records (resource_id);

-- 주요 변경의 실행 주체·행위 기록. 추가만 하며 금융 원장을 대신하지 않는다.
CREATE TABLE payment.audit_logs (
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
    ON payment.audit_logs (aggregate_type, aggregate_id, occurred_at, id);
CREATE INDEX ix_audit_logs_actor ON payment.audit_logs (actor_kind, actor_ref, occurred_at, id);
CREATE INDEX ix_audit_logs_correlation ON payment.audit_logs (correlation_id);

-- Bounded committed-completion observation; confirmed facts survive later refunds.
CREATE INDEX ix_payments_observation_confirmed ON payment.payments (confirmed_at, id)
    WHERE confirmed_at IS NOT NULL;
CREATE INDEX ix_refund_sagas_observation_completed ON payment.refund_sagas (order_restored_at, id)
    WHERE step='COMPLETED' AND order_restored_at IS NOT NULL;
CREATE INDEX ix_inbox_observation_order_cycle ON payment.inbox
    (aggregate_id, (payload->'payload'->>'paymentCycleId'), created_at, id)
    WHERE event_type='OrderPaymentRequested';

-- Bounded completion-window observations avoid scanning terminal history on every refresh.
CREATE INDEX ix_outbox_observation_published ON payment.outbox (published_at, id) WHERE status='PUBLISHED';
CREATE INDEX ix_inbox_observation_applied ON payment.inbox (consumer, updated_at, id) WHERE status='APPLIED';
