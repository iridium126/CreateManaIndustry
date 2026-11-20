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

    def test_display_p99_includes_missed_refresh_and_midpoint_uses_following_real_id(self):
        app = [{"session_id": "a", "fg_gameplay_eligible": "true"} for _ in range(5)]
        app[4]["fg_skip_reason"] = "2"
        app.append({"session_id": "b", "fg_gameplay_eligible": "true"})

        def event(frame, display, kind="Application", dropped="0"):
            return (frame, {"type": kind, "display": display, "address": 42, "dropped": dropped})

        result = [
            event(0, 100),
            event(1, 111, "Intel XeSS-FG"),
            event(1, 120),
            event(2, None, dropped="1"),
            event(3, 151, "Intel XeSS-FG"),
            event(3, 160),
            event(4, 170),
            event(5, 1200),
        ]
        rows = [row for _, row in result]
        matches = {i: (frame, "verified") for i, (frame, _) in enumerate(result)}
        fields, summary = merge.display_pacing(app, rows, matches, 1000)
        self.assertEqual(fields[1], {"midpoint_error_ms": 1.0})
        self.assertNotIn(4, fields)
        self.assertEqual(summary["real_display_interval_ms"], {"samples": 3, "p50": 20, "p95": 40, "p99": 40})
        self.assertEqual(summary["counts"]["dropped"], 1)
        self.assertEqual(summary["counts"]["ambiguous_intervals"], 1)
        self.assertEqual(summary["generation_coverage"], 1/3)

    def test_reused_present_id_and_swap_chain_do_not_merge_sessions(self):
        app = []
        rows = []
        for session, start in enumerate((100, 200)):
            for app_id in range(1, 4):
                stamp = start + app_id * 10
                app.append({"session_id": str(session), "present_id": str(app_id),
                            "present_enter_qpc": str(stamp), "present_exit_qpc": str(stamp + 5),
                            "proxy_swap_chain_address": "42"})
                rows.append({"type": "Application", "qpc": stamp + 2, "address": 42, "app_id": app_id})
        matches, verified = merge.associate(app, rows)
        self.assertEqual(verified, {"0": 42, "1": 42})
        self.assertEqual([matches[i][0] for i in range(6)], list(range(6)))


if __name__ == "__main__":
    unittest.main()
