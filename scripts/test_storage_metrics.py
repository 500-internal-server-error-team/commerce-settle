"""Storage collector source, failure and atomic exposition contracts."""

import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("storage_metrics", Path(__file__).resolve().parents[1] / "infra/observability/storage_metrics.py")
storage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(storage)


class StorageMetricsTest(unittest.TestCase):
    def test_reads_real_filesystem_and_keeps_scrape_timestamp(self):
        collector = storage.StorageMetrics({"kafka": ["/"], "postgres": ["/"]})
        self.assertIn(b'0\n', collector.render())
        collector.refresh()
        original = collector.render()
        collector.refresh()
        self.assertNotEqual(original, collector.render())
        self.assertNotIn(b'NaN', original)
        self.assertIn(b'signal="STORAGE",resource="kafka"', original)

    def test_all_paths_use_maximum_pressure_and_oldest_original_time(self):
        collector = storage.StorageMetrics({"kafka": ["/one", "/two"], "postgres": ["/three"]})
        stats = [SimpleNamespace(f_blocks=100, f_bavail=20), SimpleNamespace(f_blocks=100, f_bavail=60), SimpleNamespace(f_blocks=100, f_bavail=50)]
        with patch.object(storage.os, "statvfs", side_effect=stats), patch.object(storage.time, "time", side_effect=[10, 11, 12]):
            collector.refresh()
        self.assertIn(b'store="kafka"} 0.8', collector.render())
        self.assertIn(b'resource="kafka"} 10', collector.render())

    def test_partial_failure_invalidates_the_store_and_real_recovery_replaces_it(self):
        collector = storage.StorageMetrics({"kafka": ["/", "/missing-data-filesystem-148"], "postgres": ["/"]})
        collector.refresh()
        self.assertIn(b'store="kafka"} NaN', collector.render())
        self.assertIn(b'resource="kafka"} 0\n', collector.render())
        self.assertNotIn(b'store="postgres"} NaN', collector.render())
        collector.paths["kafka"] = ["/"]
        collector.refresh()
        self.assertNotIn(b'NaN', collector.render())

    def test_rejects_unbound_and_empty_store_paths(self):
        for paths in [{}, {"kafka": ["/"]}, {"kafka": [], "postgres": ["/"]}, {"kafka": ["relative"], "postgres": ["/"]}]:
            with self.subTest(paths=paths), self.assertRaises(ValueError):
                storage.StorageMetrics(paths)


if __name__ == "__main__":
    unittest.main()
