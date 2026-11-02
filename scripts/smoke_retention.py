#!/usr/bin/env python3
"""Verify source-backed retention only inside the disposable CI Compose project."""

import os
import subprocess
import time
import uuid
from smoke import ROOT, compose


def require_ci_project():
    if os.environ.get("CI") != "true" or os.environ.get("COMPOSE_PROJECT_NAME") != "commerce-settle-ci":
        raise RuntimeError("Retention fixture writes require the disposable commerce-settle-ci project")


def sql(database, statement):
    return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                   "-At", "-v", "ON_ERROR_STOP=1", input_text=statement).stdout.strip()


def set_retention(enabled):
    environment = {**os.environ, "COMMERCE_RETENTION_ENABLED": str(enabled).lower()}
    subprocess.run(["docker", "compose", "up", "-d", "--no-deps", "--wait", "--wait-timeout", "120", "commerce"],
                   cwd=ROOT, env=environment, capture_output=True, text=True, timeout=150, check=True)


def run():
    require_ci_project()
    fixtures = []
    for state in ("expired", "pending", "held"):
        batch, seller, delivery, item, event, attempt, receipt = [str(uuid.uuid4()) for _ in range(7)]
        fixtures.append((state, batch, delivery))
        status = "REVIEW_REQUIRED" if state == "pending" else "COMPLETED"
        closed = "NULL" if state == "pending" else "now()-interval '91 days'"
        sql("settlement", f"""
          INSERT INTO settlement.settlement_batches(id,seller_id,batch_date,cutoff_at,policy_version,status,
            net_amount,closed_at,created_at,updated_at,version)
          VALUES ('{batch}','{seller}',current_date-92,
            ((current_date-92)+time '10:00') AT TIME ZONE 'Asia/Seoul','retention-smoke','{status}',5000,
            {closed},now()-interval '92 days',now()-interval '91 days',0);
        """)
        hold = "'CI_HOLD'" if state == "held" else "NULL"
        sql("commerce", f"""
          INSERT INTO commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version)
          VALUES ('{seller}','{seller}@retention.invalid','test','retention smoke','ACTIVE','test-account',now(),now(),0);
          INSERT INTO commerce.notification_retention_batches(batch_id,seller_id,hold_reason) VALUES ('{batch}','{seller}',{hold});
          INSERT INTO commerce.notification_deliveries(id,kind,recipient_kind,recipient_id,channel,dedup_key,item_count,
            first_occurred_at,total_amount,template_version,title,body,status,next_run_at,sent_at,created_at,updated_at,attempt_count)
          VALUES ('{delivery}','SETTLEMENT_READY','SELLER','{seller}','MOCK_KAKAO','{batch}',1,
            now()-interval '92 days',0,'smoke','test','test','SENT',now(),now(),now(),now(),1);
          INSERT INTO commerce.notification_items(id,delivery_id,source_service,source_event_id,kind,business_ref,
            recipient_kind,recipient_id,occurred_at,payload,created_at)
          VALUES ('{item}','{delivery}','settlement','{event}','SETTLEMENT_READY','{batch}','SELLER','{seller}',
            now()-interval '92 days',jsonb_build_object('batchId','{batch}','sellerId','{seller}','amount',5000),now());
          INSERT INTO commerce.notification_attempts(id,delivery_id,attempt_no,operation,status,started_at,finished_at)
          VALUES ('{attempt}','{delivery}',1,'SEND','SUCCEEDED',now(),now());
          INSERT INTO commerce.notification_mock_receipts(id,delivery_id,request_hash,result,processed_at)
          VALUES ('{receipt}','{delivery}',repeat('0',64),'ACCEPTED',now());
        """)
    try:
        set_retention(True)
        expired = fixtures[0][2]
        deadline = time.monotonic()+60
        while sql("commerce", f"SELECT count(*) FROM commerce.notification_deliveries WHERE id='{expired}';") != "0":
            if time.monotonic() >= deadline:
                raise AssertionError("Owner-verified expired notification was not purged")
            time.sleep(1)
        for state, batch, delivery in fixtures:
            expected = "0" if state == "expired" else "1"
            for table in ("notification_items", "notification_attempts", "notification_mock_receipts"):
                assert sql("commerce", f"SELECT count(*) FROM commerce.{table} WHERE delivery_id='{delivery}';") == expected, (state,table)
            assert sql("settlement", f"SELECT count(*) FROM settlement.settlement_batches WHERE id='{batch}';") == "1"
        assert sql("commerce", f"SELECT count(*) FROM commerce.notification_retired_keys WHERE business_ref='{fixtures[0][1]}';") == "1"
        print("PASS source-backed retention: actual Commerce → Settlement HTTP, expired graph purge, pending/held protection, financial source preserved", flush=True)
    finally:
        set_retention(False)


if __name__ == "__main__":
    run()
