-- 과거 예약·원장·승인 이력은 수정하지 않고 회차별 예약을 보존한다.
-- 새 회차 기록 후에는 cycle-aware 조회를 포함한 이미지로만 복구한다.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='payment.mileage_reservations'::regclass
        AND conname='uk_mileage_reservations_payment'
        AND pg_get_constraintdef(oid)<>'UNIQUE (payment_id)') THEN
        RAISE EXCEPTION 'Incompatible legacy reservation constraint; preserve current schema';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='payment.mileage_reservations'::regclass
        AND conname='uk_mileage_reservations_payment_cycle'
        AND pg_get_constraintdef(oid)<>'UNIQUE (payment_id, cycle_id)') THEN
        RAISE EXCEPTION 'Incompatible cycle reservation constraint; preserve current schema';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='payment.mileage_reservations'::regclass
        AND conname='uk_mileage_reservations_payment_cycle') THEN
        ALTER TABLE payment.mileage_reservations
            ADD CONSTRAINT uk_mileage_reservations_payment_cycle UNIQUE (payment_id, cycle_id);
    END IF;
END $$;
ALTER TABLE payment.mileage_reservations DROP CONSTRAINT IF EXISTS uk_mileage_reservations_payment;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mileage_reservations_reserved ON payment.mileage_reservations (payment_id)
    WHERE status='RESERVED';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_index
        WHERE indexrelid='payment.uk_mileage_reservations_reserved'::regclass
          AND indrelid='payment.mileage_reservations'::regclass AND indisunique AND indisvalid
          AND indnkeyatts=1 AND indnatts=1
          AND pg_get_indexdef(indexrelid,1,true)='payment_id'
          AND pg_get_expr(indpred,indrelid)='((status)::text = ''RESERVED''::text)') THEN
        RAISE EXCEPTION 'Incompatible active reservation index; preserve current schema';
    END IF;
END $$;
COMMIT;
