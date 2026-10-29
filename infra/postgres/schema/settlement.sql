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
