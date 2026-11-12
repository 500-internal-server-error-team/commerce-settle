-- 기존 원문/상태를 갱신하거나 시각을 소급 생성하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';
ALTER TABLE settlement.inbox ADD COLUMN IF NOT EXISTS order_accepted_at timestamptz;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='settlement'
        AND table_name='inbox' AND column_name='order_accepted_at'
        AND (data_type <> 'timestamp with time zone' OR is_nullable <> 'YES' OR column_default IS NOT NULL)) THEN
        RAISE EXCEPTION 'Incompatible order_accepted_at column; stop and preserve current data';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_settlement_items_observation_created ON settlement.settlement_items (created_at, id);
COMMIT;
