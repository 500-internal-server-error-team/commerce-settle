-- 부하 시험 SQL 점검의 조회 인덱스만 추가한다. 기존 행·원문·상태는 갱신하지 않는다.
-- 동명 인덱스의 정의가 다르거나 유효하지 않으면 전체를 롤백한다.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
CREATE INDEX IF NOT EXISTS ix_outbox_reconciliation ON payment.outbox (created_at, id)
    WHERE event_type IN ('PaymentConfirmed','RefundFinancialCompleted');
DO $$
DECLARE
    expected record;
BEGIN
    FOR expected IN SELECT * FROM (VALUES
        ('payment.ix_outbox_reconciliation',
         'CREATE INDEX ix_outbox_reconciliation ON payment.outbox USING btree (created_at, id) WHERE ((event_type)::text = ANY ((ARRAY[''PaymentConfirmed''::character varying, ''RefundFinancialCompleted''::character varying])::text[]))')
    ) AS v(name, definition) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass(expected.name)
            AND indisvalid AND pg_get_indexdef(indexrelid) = expected.definition) THEN
            RAISE EXCEPTION 'Incompatible index %; preserve current schema', expected.name;
        END IF;
    END LOOP;
END $$;
COMMIT;
