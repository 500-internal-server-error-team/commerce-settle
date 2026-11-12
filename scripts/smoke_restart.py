#!/usr/bin/env python3
"""Verify existing smoke business records survive the disposable CI stack restart."""

import json
import subprocess
import sys

from smoke import ROOT, compose, require_ci_project
import sync_redis_run_id


TABLES = ("seller_accounts", "buyer_accounts", "buyer_coupons")


def read_business_data():
    result = compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "commerce",
                     "-Atq", "-v", "ON_ERROR_STOP=1", input_text="""
SELECT jsonb_build_object(
  'seller_accounts', (SELECT coalesce(jsonb_object_agg(s.id::text, to_jsonb(s)), '{}'::jsonb)
    FROM (SELECT id, display_name, payout_account_ref, status, version
          FROM commerce.seller_accounts) s),
  'buyer_accounts', (SELECT coalesce(jsonb_object_agg(b.id::text, to_jsonb(b)), '{}'::jsonb)
    FROM (SELECT id, display_name, status, version FROM commerce.buyer_accounts) b),
  'buyer_coupons', (SELECT coalesce(jsonb_object_agg(c.id::text, to_jsonb(c)), '{}'::jsonb)
    FROM (SELECT id, template_id, buyer_id, kind, discount_amount, status, version
          FROM commerce.buyer_coupons) c)
);
""")
    return json.loads(result.stdout)


def verify_preserved(expected):
    actual = read_business_data()
    for table in TABLES:
        for record_id, values in expected[table].items():
            assert actual[table].get(record_id) == values, (
                f"{table}: pre-restart record {record_id} is missing or changed"
            )


def run():
    require_ci_project()
    before = read_business_data()
    assert all(before.get(table) for table in TABLES), "Run smoke before checking retained business data"
    subprocess.run(["docker", "compose", "down"], cwd=ROOT, check=True, timeout=180)
    sync_redis_run_id.main([])
    subprocess.run(["docker", "compose", "up", "-d", "--wait", "--wait-timeout", "300"],
                   cwd=ROOT, check=True, timeout=360)
    verify_preserved(before)
    print("PASS retained business IDs and values: " + ", ".join(
        f"{table}={len(before[table])}" for table in TABLES), flush=True)
    subprocess.run([sys.executable, "-B", str(ROOT / "scripts/smoke.py")],
                   cwd=ROOT, check=True, timeout=900)


if __name__ == "__main__":
    run()
