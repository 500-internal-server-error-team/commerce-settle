CREATE TABLE payments (
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

CREATE INDEX ix_payments_buyer_created ON payments (buyer_id, created_at, id);
CREATE INDEX ix_payments_status_updated ON payments (status, updated_at, id);
