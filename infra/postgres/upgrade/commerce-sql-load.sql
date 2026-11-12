-- 부하 시험 SQL 점검의 조회 인덱스만 추가하며 기존 행·원문·상태를 갱신하지 않는다.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
CREATE INDEX IF NOT EXISTS inbox_waiting_accepted ON commerce.inbox(consumer, next_run_at, id)
    WHERE status = 'WAITING' AND event_type = 'PaymentAccepted';
CREATE INDEX IF NOT EXISTS inbox_waiting_results ON commerce.inbox(consumer, next_run_at, id)
    WHERE status = 'WAITING' AND event_type <> 'PaymentAccepted';
CREATE INDEX IF NOT EXISTS notification_deliveries_action_created ON commerce.notification_deliveries(created_at)
    WHERE kind IN ('REFUND_ACTION_REQUIRED','PAYOUT_ACTION_REQUIRED');
DO $$
BEGIN
    IF (SELECT count(*) FROM pg_indexes WHERE schemaname='commerce' AND indexname IN
        ('inbox_waiting_accepted','inbox_waiting_results','notification_deliveries_action_created')) <> 3
        OR EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='commerce' AND (
            (indexname='inbox_waiting_accepted' AND indexdef <> 'CREATE INDEX inbox_waiting_accepted ON commerce.inbox USING btree (consumer, next_run_at, id) WHERE (((status)::text = ''WAITING''::text) AND ((event_type)::text = ''PaymentAccepted''::text))')
            OR (indexname='inbox_waiting_results' AND indexdef <> 'CREATE INDEX inbox_waiting_results ON commerce.inbox USING btree (consumer, next_run_at, id) WHERE (((status)::text = ''WAITING''::text) AND ((event_type)::text <> ''PaymentAccepted''::text))')
            OR (indexname='notification_deliveries_action_created' AND indexdef <> 'CREATE INDEX notification_deliveries_action_created ON commerce.notification_deliveries USING btree (created_at) WHERE ((kind)::text = ANY ((ARRAY[''REFUND_ACTION_REQUIRED''::character varying, ''PAYOUT_ACTION_REQUIRED''::character varying])::text[]))'))) THEN
        RAISE EXCEPTION 'Incompatible SQL load index; stop and inspect current definition';
    END IF;
END $$;
COMMIT;
