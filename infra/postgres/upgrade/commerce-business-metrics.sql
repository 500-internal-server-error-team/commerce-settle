-- 관측 메타데이터만 추가하며 기존 원문/상태/시각을 갱신하지 않는다.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
ALTER TABLE commerce.outbox ADD COLUMN IF NOT EXISTS order_accepted_at timestamptz;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='commerce'
        AND table_name='outbox' AND column_name='order_accepted_at'
        AND (data_type <> 'timestamp with time zone' OR is_nullable <> 'YES' OR column_default IS NOT NULL)) THEN
        RAISE EXCEPTION 'Incompatible order_accepted_at column; stop and preserve current data';
    END IF;
END $$;
COMMIT;
