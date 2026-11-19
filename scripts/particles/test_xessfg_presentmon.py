import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "merge_xessfg_presentmon", Path(__file__).with_name("merge-xessfg-presentmon.py"))
merge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(merge)


class ProxyTimingTest(unittest.TestCase):
    def test_overlapping_dxgi_calls_are_clipped_and_counted_once(self):
        frame = {"session_id": "1", "present_enter_qpc": "100", "present_exit_qpc": "120"}
        calls = [
            {"type": "Application", "qpc": 98, "api_ticks": 5, "address": 8, "app_id": 50},
            {"type": "Application", "qpc": 102, "api_ticks": 5, "address": 8, "app_id": 51},
            {"type": "Intel XeSS-FG", "qpc": 118, "api_ticks": 4, "address": 8, "app_id": 52},
            {"type": "Application", "qpc": 100, "api_ticks": 20, "address": 9, "app_id": 52},
        ]
        self.assertEqual(list(merge.proxy_breakdown([frame], calls, {"1": 8}, 1000)), [{
            "proxy_to_first_dxgi_ms": 2.0,
            "proxy_dxgi_overlap_ms": 9.0,
            "proxy_outside_dxgi_ms": 11.0,
        }])

    def test_missing_api_duration_leaves_overlap_unknown(self):
        frame = {"session_id": "1", "present_enter_qpc": "100", "present_exit_qpc": "120"}
        call = {"type": "Intel XeSS-FG", "qpc": 110, "api_ticks": None, "address": 8}
        self.assertEqual(list(merge.proxy_breakdown([frame], [call], {"1": 8}, 1000)),
                         [{"proxy_to_first_dxgi_ms": 10.0}])


if __name__ == "__main__":
    unittest.main()
