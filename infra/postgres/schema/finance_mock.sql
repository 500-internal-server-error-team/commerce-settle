-- 배포 전 개발용 초기 DDL. 빈 서비스 DB에서 한 번 실행합니다.
CREATE SCHEMA finance_mock;

COMMENT ON SCHEMA finance_mock IS 'Finance Mock owned schema';

-- PG·간편결제·지급 대역이 같이 쓰는 시험 계정. 지급 재원은 purpose=PAYOUT 계정이다.
CREATE TABLE finance_mock.mock_accounts (
    id              uuid         PRIMARY KEY,
    provider_id     varchar(16)  NOT NULL,
    account_ref     varchar(100) NOT NULL,
    purpose         varchar(16)  NOT NULL,
    opening_balance bigint       NOT NULL,
    balance         bigint       NOT NULL,
    reserved_amount bigint       NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_mock_accounts_ref UNIQUE (provider_id, account_ref, purpose),
    CONSTRAINT ck_mock_accounts_purpose CHECK (purpose IN ('WALLET', 'PAYOUT')),
    CONSTRAINT ck_mock_accounts_opening CHECK (opening_balance >= 0),
    CONSTRAINT ck_mock_accounts_reserved CHECK (0 <= reserved_amount AND reserved_amount <= balance),
    CONSTRAINT ck_mock_accounts_version CHECK (version >= 0)
);

-- 장애 시험 설정. 행은 만든 뒤 바꾸지 않는다. PAYOUT은 환불·입금 지연 필드를 쓰지 않는다.
CREATE TABLE finance_mock.mock_scenarios (
    id                             uuid         PRIMARY KEY,
    settlement_delay_business_days integer,
    provider_id                    varchar(16)  NOT NULL,
    scenario_version               varchar(32)  NOT NULL,
    min_delay_ms                   integer      NOT NULL,
    max_delay_ms                   integer      NOT NULL,
    rejection_rate                 numeric(8,7) NOT NULL,
    response_loss_rate             numeric(8,7) NOT NULL,
    lookup_failure_rate            numeric(8,7) NOT NULL,
    refund_min_delay_ms            integer,
    refund_max_delay_ms            integer,
    refund_rejection_rate          numeric(8,7),
    refund_response_loss_rate      numeric(8,7),
    lookup_min_delay_ms            integer      NOT NULL,
    lookup_max_delay_ms            integer      NOT NULL,
    seed                           bigint       NOT NULL,
    created_at                     timestamptz  NOT NULL,
    CONSTRAINT uq_mock_scenarios_version UNIQUE (provider_id, scenario_version),
    CONSTRAINT ck_mock_scenarios_delay CHECK (0 <= min_delay_ms AND min_delay_ms <= max_delay_ms),
    CONSTRAINT ck_mock_scenarios_lookup_delay
        CHECK (0 <= lookup_min_delay_ms AND lookup_min_delay_ms <= lookup_max_delay_ms),
    CONSTRAINT ck_mock_scenarios_rates CHECK (
        rejection_rate BETWEEN 0 AND 1
        AND response_loss_rate BETWEEN 0 AND 1
        AND lookup_failure_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_mock_scenarios_payout_fields CHECK (
        provider_id <> 'PAYOUT'
        OR (settlement_delay_business_days IS NULL
            AND refund_min_delay_ms IS NULL AND refund_max_delay_ms IS NULL
            AND refund_rejection_rate IS NULL AND refund_response_loss_rate IS NULL)),
    CONSTRAINT ck_mock_scenarios_settlement_delay
        CHECK (settlement_delay_business_days IS NULL OR settlement_delay_business_days BETWEEN 0 AND 365),
    CONSTRAINT ck_mock_scenarios_refund_delay CHECK (
        refund_min_delay_ms IS NULL OR refund_max_delay_ms IS NULL
        OR (0 <= refund_min_delay_ms AND refund_min_delay_ms <= refund_max_delay_ms)),
    CONSTRAINT ck_mock_scenarios_refund_rates CHECK (
        (refund_rejection_rate IS NULL OR refund_rejection_rate BETWEEN 0 AND 1)
        AND (refund_response_loss_rate IS NULL OR refund_response_loss_rate BETWEEN 0 AND 1))
);

-- 모의 금융 거래. 같은 대역·작업·업무 키에는 거래가 하나뿐이다.
CREATE TABLE finance_mock.mock_transactions (
    id                  uuid         PRIMARY KEY,
    provider_id         varchar(16)  NOT NULL,
    operation           varchar(16)  NOT NULL,
    business_key        uuid         NOT NULL,
    original_id         uuid,
    account_id          uuid,
    method              varchar(24),
    funding_account_id  uuid,
    pg_calendar_version varchar(32),
    pg_calendar         jsonb,
    pg_receipt_due_at   timestamptz,
    pg_received_at      timestamptz,
    pg_received_amount  bigint,
    amount              bigint       NOT NULL,
    status              varchar(24)  NOT NULL,
    succeeded_at        timestamptz,
    scenario_version    varchar(32)  NOT NULL,
    result_seed         bigint       NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_mock_transactions_key UNIQUE (provider_id, operation, business_key),
    CONSTRAINT ck_mock_transactions_operation CHECK (operation IN ('APPROVE', 'REFUND', 'PAYOUT')),
    CONSTRAINT ck_mock_transactions_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'DECLINED')),
    CONSTRAINT ck_mock_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_transactions_succeeded
        CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL)),
    CONSTRAINT ck_mock_transactions_received_amount
        CHECK (pg_received_amount IS NULL OR pg_received_amount >= 0),
    CONSTRAINT ck_mock_transactions_version CHECK (version >= 0)
);
-- 진행 중이거나 성공한 환불은 원거래마다 하나다.
CREATE UNIQUE INDEX uq_mock_transactions_active_refund
    ON finance_mock.mock_transactions (original_id)
    WHERE operation = 'REFUND' AND status IN ('PROCESSING', 'SUCCEEDED');
CREATE INDEX ix_mock_transactions_pg_receipt
    ON finance_mock.mock_transactions (operation, status, pg_receipt_due_at, id)
    WHERE pg_received_at IS NULL;
CREATE INDEX ix_mock_transactions_provider_status
    ON finance_mock.mock_transactions (provider_id, status, created_at, id);
CREATE INDEX ix_mock_transactions_original ON finance_mock.mock_transactions (original_id);

-- 대역 자금 선점. 거래마다 하나이며 성공하면 소비, 확정 거절이면 해제한다.
CREATE TABLE finance_mock.mock_funds_reservations (
    id             uuid        PRIMARY KEY,
    account_id     uuid        NOT NULL,
    transaction_id uuid        NOT NULL,
    amount         bigint      NOT NULL,
    status         varchar(16) NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_mock_funds_reservations_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_mock_funds_reservations_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_funds_reservations_status
        CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_mock_funds_reservations_version CHECK (version >= 0)
);
CREATE INDEX ix_mock_funds_reservations_account
    ON finance_mock.mock_funds_reservations (account_id, status);

-- PAYOUT 계정의 추가 전용 현금 기록. 입금은 양수, 환불·지급 차감은 음수다.
CREATE TABLE finance_mock.mock_cash_entries (
    id             uuid        PRIMARY KEY,
    account_id     uuid        NOT NULL,
    kind           varchar(24) NOT NULL,
    transaction_id uuid        NOT NULL,
    amount         bigint      NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT uq_mock_cash_entries_transaction UNIQUE (kind, transaction_id),
    CONSTRAINT ck_mock_cash_entries_kind
        CHECK (kind IN ('PG_RECEIPT', 'REFUND_DEBIT', 'PAYOUT_DEBIT')),
    CONSTRAINT ck_mock_cash_entries_sign CHECK (
        (kind = 'PG_RECEIPT' AND amount > 0)
        OR (kind IN ('REFUND_DEBIT', 'PAYOUT_DEBIT') AND amount < 0))
);
CREATE INDEX ix_mock_cash_entries_account
    ON finance_mock.mock_cash_entries (account_id, created_at, id);

-- 발신 서비스·대역·작업·업무 키 범위의 요청 멱등 기록.
CREATE TABLE finance_mock.idempotency_records (
    id                uuid         PRIMARY KEY,
    actor_kind        varchar(24)  NOT NULL,
    actor_id          varchar(100) NOT NULL,
    operation         varchar(100) NOT NULL,
    key_hash          char(64)     NOT NULL,
    request_hash      char(64)     NOT NULL,
    resource_id       uuid,
    response_status   integer,
    response_snapshot jsonb,
    status            varchar(16)  NOT NULL,
    expires_at        timestamptz,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_idempotency_records_key UNIQUE (actor_kind, actor_id, operation, key_hash),
    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('PENDING', 'COMPLETED'))
);
CREATE INDEX ix_idempotency_records_expires ON finance_mock.idempotency_records (expires_at);
CREATE INDEX ix_idempotency_records_resource ON finance_mock.idempotency_records (resource_id);
