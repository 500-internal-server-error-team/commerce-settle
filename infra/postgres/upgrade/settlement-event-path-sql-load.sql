-- 정산 이벤트 경로의 SQL 부하 개선용 인덱스. 기존 행·원문·상태를 갱신하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';
CREATE INDEX IF NOT EXISTS inbox_purchase_revival ON settlement.inbox
    (consumer, ((payload->'payload'->>'paymentId')::uuid))
    WHERE event_type = 'PurchaseConfirmed' AND status IN ('WAITING', 'REVIEW_REQUIRED');
DO $$
BEGIN
    IF pg_get_indexdef('settlement.inbox_purchase_revival'::regclass) <>
        'CREATE INDEX inbox_purchase_revival ON settlement.inbox USING btree (consumer, '
        || '((((payload -> ''payload''::text) ->> ''paymentId''::text))::uuid)) '
        || 'WHERE (((event_type)::text = ''PurchaseConfirmed''::text) AND ((status)::text = '
        || 'ANY ((ARRAY[''WAITING''::character varying, ''REVIEW_REQUIRED''::character varying])::text[])))' THEN
        RAISE EXCEPTION 'Incompatible inbox_purchase_revival index; stop and inspect its definition';
    END IF;
END $$;
COMMIT;
