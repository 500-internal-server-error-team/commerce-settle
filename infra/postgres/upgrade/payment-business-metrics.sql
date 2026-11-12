-- 기존 원문/상태를 갱신하거나 시각을 소급 생성하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';
ALTER TABLE payment.outbox ADD COLUMN IF NOT EXISTS order_accepted_at timestamptz;
ALTER TABLE payment.inbox ADD COLUMN IF NOT EXISTS order_accepted_at timestamptz;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='payment'
        AND table_name IN ('outbox','inbox') AND column_name='order_accepted_at'
        AND (data_type <> 'timestamp with time zone' OR is_nullable <> 'YES' OR column_default IS NOT NULL)) THEN
        RAISE EXCEPTION 'Incompatible order_accepted_at column; stop and preserve current data';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_payments_observation_confirmed ON payment.payments (confirmed_at, id)
    WHERE confirmed_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_refund_sagas_observation_completed ON payment.refund_sagas (order_restored_at, id)
    WHERE step='COMPLETED' AND order_restored_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_inbox_observation_order_cycle ON payment.inbox
    (aggregate_id, (payload->'payload'->>'paymentCycleId'), created_at, id)
    WHERE event_type='OrderPaymentRequested';
COMMIT;
