from copy import deepcopy
import math
import unittest

import performance


def metadata():
    return {"revision": "a"*40, "imageDigests": {image: "sha256:"+"a"*64 for image in performance.IMAGES},
            "conditions": {"workload": "order-refund", "datasetSha256": "b"*64,
                           "arrivalRate": 100, "mix": ["card"], "scenario": "normal",
                           "generator": "same-host", "resources": {"appCpu": 2},
                           "runtime": {"jdk": "21"}, "warmupSeconds": 60},
            "http": {"application": "gateway", "uri": "/api/orders", "method": "POST"},
            "operations": ["payment.confirmed", "refund.completed", "order.settlement.applied", "payment.settlement.applied"]}


def vector(value, operation=None):
    return {"metric": {"operation": operation} if operation else {}, "value": [1000,str(value)]}


def fetch(expression, samples=20000):
    if expression.startswith("business:source"):
        return [vector(990, op) for op in performance.OPERATIONS]
    if expression.startswith("business:"):
        value = 0 if "duration_missing" in expression else samples
        return [vector(value, op) for op in performance.OPERATIONS]
    if "business:" in expression:
        return [vector(.5, op) for op in performance.OPERATIONS]
    return [vector(samples if expression.startswith("sum(") else .5)]


class PerformanceTest(unittest.TestCase):
    def test_new_http_counter_without_scrape_history_is_not_ready_but_is_not_zero_success(self):
        with self.assertRaises(performance.ObservationNotReady):
            performance.capture(metadata(), 900, 1000,
                lambda expression: [] if expression.startswith("sum(increase(http_server_requests_seconds_count") else fetch(expression))
        report = performance.capture(metadata(), 900, 1000, fetch)
        self.assertEqual(report["http"]["samples"], 20000)

    def test_payout_comparison_includes_unpaid_cohorts_and_missing_origins(self):
        item = metadata()
        item["operations"] = ["payment.payout.succeeded", "ready.payout.succeeded"]
        item["payoutPopulationCutoff"] = 900
        reasons = ("purchase_pending", "refund_pending", "batch_wait", "payout_pending",
                   "payout_unknown", "payout_declined", "refunded")

        def query(expression):
            if expression == "business:cohort_source":
                return [vector(990, op) for op in ("payout.total", "payout.ready", "payout.unapplied")]
            if expression in ("business:cohort_cutoff", "business:cohort_snapshot", "business:cohort_capture_complete"):
                return [vector({"business:cohort_cutoff": 910, "business:cohort_snapshot": 950,
                                "business:cohort_capture_complete": 1}[expression], "payout.unapplied")]
            if expression.startswith("business:cohort_"):
                return [{"metric": {"operation": op, "reason": reason}, "value": [1000, "1"]}
                        for op in ("payout.total", "payout.ready", "payout.unapplied")
                        for reason in (reasons if op != "payout.unapplied" else
                                       ("wait_publication", "wait_delivery", "wait_application", "quarantined",
                                        "defect", "pending", "unverified", "unclassified"))]
            return fetch(expression)

        report = performance.capture(item, 300, 1000, query)
        self.assertEqual(set(report["cohorts"]), {"payout.total", "payout.ready", "payout.unapplied"})
        self.assertFalse(performance.compare(report, report)["complete"])
        for key in ("cohorts", "payoutPopulation"):
            broken = deepcopy(report)
            broken.pop(key)
            with self.assertRaisesRegex(ValueError, "payout report"):
                performance.compare(broken, broken)
        broken = deepcopy(report)
        invalid = deepcopy(report)
        invalid["metadata"]["payoutPopulationCutoff"] = True
        invalid["payoutPopulation"]["requestedCutoff"] = True
        with self.assertRaises(ValueError):
            performance.compare(invalid, report)
        invalid["payoutPopulation"]["requestedCutoff"] = 1
        with self.assertRaises(ValueError):
            performance.compare(invalid, invalid)
        invalid = deepcopy(report)
        invalid["metadata"].pop("operations")
        invalid.pop("payoutPopulation")
        invalid.pop("cohorts")
        with self.assertRaises(ValueError):
            performance.compare(invalid, invalid)
        broken["payoutPopulation"]["capture_complete"] = 0
        with self.assertRaises(ValueError):
            performance.compare(broken, report)

        with self.assertRaises(ValueError):
            performance.capture(item, 300, 1000,
                                lambda expr: [] if expr == "business:cohort_source" else query(expr))

    def test_captures_actual_completion_tps_and_window_quantiles(self):
        result=performance.capture(metadata(),300,1000,fetch)
        self.assertAlmostEqual(result["business"]["payment.confirmed"]["tps"],20000/300)
        self.assertEqual(result["business"]["order.settlement.applied"]["p99Seconds"],.5)
        self.assertEqual(result["measurementStart"],700)

    def test_compares_only_matching_conditions_but_records_changed_code_and_tuning(self):
        before=performance.capture(metadata(),300,1000,fetch)
        after=deepcopy(before)
        after["metadata"]["revision"]="c"*40
        after["metadata"]["variant"]={"approvalConcurrency":12}
        after["business"]["payment.confirmed"]["tps"]*=1.1
        result=performance.compare(before,after)
        self.assertTrue(result["complete"])
        self.assertAlmostEqual(result["deltas"]["payment.confirmed.tps"]["percent"],10)
        for key in performance.CONDITIONS:
            changed=deepcopy(after);changed["metadata"]["conditions"][key]="different"
            with self.subTest(key=key),self.assertRaises(ValueError):
                performance.compare(before,changed)
        after["windowSeconds"]=900
        with self.assertRaisesRegex(ValueError,"windows differ"):
            performance.compare(before,after)

    def test_low_sample_p99_and_missing_origin_are_incomplete_not_successful_comparisons(self):
        result=performance.capture(metadata(),300,1000,lambda expr:fetch(expr,100))
        self.assertIsNone(result["http"]["p99Seconds"])
        self.assertIsNone(result["business"]["payment.confirmed"]["p99Seconds"])
        self.assertFalse(performance.compare(result,result)["complete"])
        result=performance.capture(metadata(),300,1000,fetch)
        result["business"]["order.settlement.applied"]["missingDuration"]=1
        compared=performance.compare(result,result)
        self.assertFalse(compared["complete"])
        self.assertIn("order.settlement.applied.durationCoverage",compared["unavailable"])

    def test_stale_future_missing_and_duplicate_sources_fail_closed(self):
        for rows in [[],[vector(900,op) for op in performance.OPERATIONS],
                     [vector(1010,op) for op in performance.OPERATIONS],
                     [vector(990,"payment.confirmed"),vector(990,"payment.confirmed")]]:
            with self.subTest(rows=rows),self.assertRaises(ValueError):
                performance.capture(metadata(),300,1000,
                    lambda expr:rows if expr.startswith("business:source") else fetch(expr))

    def test_absent_http_histogram_is_not_replaced_with_zero_latency(self):
        with self.assertRaisesRegex(ValueError,"histogram"):
            performance.capture(metadata(),300,1000,
                lambda expr:[] if "http_server_requests_seconds_bucket" in expr else fetch(expr))

    def test_missing_image_or_invalid_load_conditions_fail_before_query(self):
        for key,value in [("arrivalRate",-1),("warmupSeconds",-1),("resources",{}),("mix",[])]:
            item=metadata();item["conditions"][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):
                performance.capture(item,300,1000,lambda _:self.fail("queried before validation"))
        item=metadata();item["imageDigests"].pop("payment")
        with self.assertRaises(ValueError):
            performance.capture(item,300,1000,lambda _:self.fail("queried before validation"))

    def test_nonfinite_or_duplicate_business_observation_is_rejected(self):
        with self.assertRaises(ValueError):
            performance.business_values([vector(1,"x"),vector(2,"x")])
        self.assertIsNone(performance.one([vector(math.nan)]))


if __name__=="__main__":
    unittest.main()
