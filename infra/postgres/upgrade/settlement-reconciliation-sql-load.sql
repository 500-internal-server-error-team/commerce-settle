-- 정합성 점검 SQL 부하 개선: 대기 회차의 무진척 횟수와 미일치 이벤트 조회 인덱스를 추가한다.
-- 기존 회차·이벤트·이력 값을 갱신하지 않는다. 새 컬럼은 상수 기본값이므로 테이블을 다시 쓰지 않는다.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';
ALTER TABLE settlement.reconciliation_runs
    ADD COLUMN IF NOT EXISTS idle_rounds integer NOT NULL DEFAULT 0 CHECK (idle_rounds >= 0);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='settlement'
        AND table_name='reconciliation_runs' AND column_name='idle_rounds'
        AND (data_type <> 'integer' OR is_nullable <> 'NO' OR column_default IS DISTINCT FROM '0')) THEN
        RAISE EXCEPTION 'Incompatible idle_rounds column; stop and preserve current data';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS reconciliation_events_unmatched
    ON settlement.reconciliation_events (run_id, checked_at NULLS FIRST, event_id) WHERE status <> 'MATCH';
COMMIT;
