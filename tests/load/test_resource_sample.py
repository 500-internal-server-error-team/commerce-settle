import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import resource_sample as rs

DOCKER_OUTPUT = "\n".join([
    json.dumps({"Name": "commerce-1", "CPUPerc": "12.50%", "MemUsage": "200MiB / 512MiB",
                "MemPerc": "39.06%", "PIDs": "41"}),
    json.dumps({"Name": "payment-worker-1", "CPUPerc": "--", "MemUsage": "0B / 0B",
                "MemPerc": "--", "PIDs": "0"}),
])

VMSTAT_OUTPUT = """\
procs -----------memory---------- ---swap-- -----io---- -system-- -------cpu-------
 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st
 1  0      0 812340  10240 900000    0    0    12    40  500  900  8  3 88  1  0
 2  0      0 810000  10240 900100    0    0    16    64  650 1100 42 9 47  2  0
"""

IOSTAT_OUTPUT = """\
Linux 6.8.0 (ip-10-0-0-1)  11/12/2026  _aarch64_  (4 CPU)

Device            r/s     rkB/s   rrqm/s  %rrqm r_await rareq-sz     w/s     wkB/s   wrqm/s  %wrqm w_await wareq-sz     d/s     dkB/s   drqm/s  %drqm d_await dareq-sz  aqu-sz  %util
nvme0n1          0.00      0.00     0.00   0.00    0.00     0.00   10.00    160.00     0.00   0.00    1.20    16.00    0.00      0.00     0.00   0.00    0.00     0.00    0.01   0.50

Device            r/s     rkB/s   rrqm/s  %rrqm r_await rareq-sz     w/s     wkB/s   wrqm/s  %wrqm w_await wareq-sz     d/s     dkB/s   drqm/s  %drqm d_await dareq-sz  aqu-sz  %util
nvme0n1          1.00      4.00     0.00   0.00    0.50     4.00   250.00   4000.00     0.00   0.00    3.40    16.00    0.00      0.00     0.00   0.00    0.00     0.00    0.85  61.20
loop0            0.00      0.00     0.00   0.00    0.00     0.00    0.00      0.00     0.00   0.00    0.00     0.00    0.00      0.00     0.00   0.00    0.00     0.00    0.00   0.00
"""


class ParseDockerStatsTest(unittest.TestCase):
    def test_parses_each_container_and_treats_placeholders_as_none(self):
        rows = rs.parse_docker_stats(DOCKER_OUTPUT)
        self.assertEqual(rows[0], {"name": "commerce-1", "cpu_pct": 12.5, "mem_usage": "200MiB / 512MiB",
                                   "mem_pct": 39.06, "pids": "41"})
        self.assertIsNone(rows[1]["cpu_pct"])
        self.assertIsNone(rows[1]["mem_pct"])

    def test_empty_output_gives_empty_list(self):
        self.assertEqual(rs.parse_docker_stats(""), [])


class ParseVmstatTest(unittest.TestCase):
    def test_uses_second_sample_row(self):
        parsed = rs.parse_vmstat(VMSTAT_OUTPUT)
        self.assertEqual(parsed["us"], 42.0)
        self.assertEqual(parsed["id"], 47.0)
        self.assertEqual(parsed["wa"], 2.0)
        self.assertEqual(parsed["free"], 810000.0)

    def test_returns_none_without_header(self):
        self.assertIsNone(rs.parse_vmstat("1 0 0 0\n"))


class ParseIostatTest(unittest.TestCase):
    def test_uses_last_block_and_skips_loop_devices(self):
        devices = rs.parse_iostat(IOSTAT_OUTPUT)
        self.assertEqual(list(devices), ["nvme0n1"])
        self.assertEqual(devices["nvme0n1"]["%util"], 61.2)
        self.assertEqual(devices["nvme0n1"]["w_await"], 3.4)
        self.assertEqual(devices["nvme0n1"]["aqu-sz"], 0.85)

    def test_returns_none_without_device_header(self):
        self.assertIsNone(rs.parse_iostat("nothing here\n"))


class SampleTest(unittest.TestCase):
    def test_failed_command_is_recorded_as_error_and_does_not_raise(self):
        with mock.patch.object(rs, "_run", side_effect=RuntimeError("docker not running")):
            record = rs.sample()
        self.assertIn("error", record["docker"])
        self.assertIn("error", record["host"])
        self.assertIn("error", record["disk"])
        self.assertIn("ts", record)

    def test_successful_commands_are_parsed(self):
        outputs = {"docker": DOCKER_OUTPUT, "vmstat": VMSTAT_OUTPUT, "iostat": IOSTAT_OUTPUT}

        def fake_run(command):
            return outputs[command[0]]

        with mock.patch.object(rs, "_run", side_effect=fake_run):
            record = rs.sample()
        self.assertEqual(record["docker"][0]["name"], "commerce-1")
        self.assertEqual(record["host"]["us"], 42.0)
        self.assertEqual(record["disk"]["nvme0n1"]["%util"], 61.2)


class MainTest(unittest.TestCase):
    def test_writes_jsonl_samples_and_stops_after_seconds(self):
        def fake_sample():
            return {"ts": "t", "docker": [], "host": None, "disk": None}

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(rs, "sample", side_effect=fake_sample), \
                mock.patch.object(rs.time, "sleep"):
            out = Path(tmp) / "run"
            code = rs.main(["--out-dir", str(out), "--interval", "1", "--seconds", "0"])
            lines = (out / "resources.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(code, 0)
        self.assertEqual(len(lines), 1)
        self.assertEqual(json.loads(lines[0])["ts"], "t")

    def test_rejects_non_positive_interval(self):
        with self.assertRaises(SystemExit):
            rs.main(["--out-dir", "unused", "--interval", "0"])


if __name__ == "__main__":
    unittest.main()
