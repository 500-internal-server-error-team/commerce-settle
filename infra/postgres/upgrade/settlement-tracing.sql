-- 기존 데이터는 갱신하지 않는다. 소유 DB에서 앱 배포 전에 적용한다.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';
ALTER TABLE settlement.outbox ADD COLUMN IF NOT EXISTS trace_parent varchar(55);
ALTER TABLE settlement.inbox ADD COLUMN IF NOT EXISTS trace_parent varchar(55);
ALTER TABLE settlement.payout_jobs ADD COLUMN IF NOT EXISTS trace_parent varchar(55);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'settlement'
        AND table_name IN ('outbox','inbox','payout_jobs') AND column_name = 'trace_parent'
        AND (data_type <> 'character varying' OR character_maximum_length <> 55 OR is_nullable <> 'YES')) THEN
        RAISE EXCEPTION 'Incompatible trace_parent column; preserve current schema and stop';
    END IF;
END $$;
COMMIT;
