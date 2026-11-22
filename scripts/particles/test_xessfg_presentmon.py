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
        app[1]["hud_copy_route"] = "sparse"
        app[3]["hud_copy_route"] = "deadline"
        app[4]["fg_skip_reason"] = "3"
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
        self.assertEqual(summary["display_interval_ms"], {"samples": 5, "p50": 10, "p95": 31, "p99": 31})
        self.assertEqual(summary["counts"]["dropped"], 1)
        self.assertEqual(summary["counts"]["ambiguous_intervals"], 1)
        self.assertEqual(summary["generation_coverage"], 1/3)
        self.assertEqual(summary["hud_copy_routes"], {"sparse": 1, "deadline": 1})

    def test_generated_presents_can_precede_the_proxy_call(self):
        app, rows = [], []
        for index in range(3):
            stamp = 100 + index * 100
            app.append({"session_id": "1", "present_id": str(index + 10),
                        "present_enter_qpc": str(stamp), "present_exit_qpc": str(stamp + 40),
                        "proxy_swap_chain_address": "42"})
            rows.append({"type": "Application", "qpc": stamp + 20, "address": 42, "app_id": 10 + index})
        rows.append({"type": "Intel XeSS-FG", "qpc": 250, "address": 42, "app_id": 12})
        rows.append({"type": "Intel XeSS-FG", "qpc": 255, "address": 42, "app_id": 12})
        matches, verified = merge.associate(app, rows)
        self.assertEqual(verified, {"1": 42})
        self.assertEqual(matches[3], (2, "verified_app_frame_id"))
        self.assertEqual(matches[4], (2, "verified_app_frame_id"))

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

    def test_fixed_refresh_splits_use_display_time_and_exclude_unquantized_intervals(self):
        app = [{"session_id": "a"} for _ in range(4)]
        app[1]["arc_inputs_ready_qpc"] = "17"
        rows = [dict(type=kind, display=display, address=42) for kind, display in [
            ("Application", 0), ("Intel XeSS-FG", 30), ("Application", 40),
            ("Intel XeSS-FG", 60), ("Application", 80),
            ("Intel XeSS-FG", 95), ("Application", 110),
        ]]
        matches = {index: ((index + 1) // 2, "verified") for index in range(len(rows))}
        fields, summary = merge.display_pacing(app, rows, matches, 1000, 100)
        self.assertEqual(summary["fixed_refresh"]["splits"], {"3:1": 1, "2:2": 1})
        self.assertEqual(summary["fixed_refresh"]["unquantized_intervals"], 1)
        self.assertEqual(summary["fixed_refresh"]["four_refresh_intervals"], 2)
        self.assertEqual(summary["fixed_refresh"]["four_refresh_midpoint_fraction"], 0.5)
        self.assertEqual(fields[1]["arc_inputs_to_midpoint_ms"], 3)
        self.assertEqual(fields[1]["arc_inputs_to_generated_ms"], 13)
        self.assertNotIn("refresh_split", fields[5])

    def test_multiple_generated_frames_report_closest_to_midpoint(self):
        app = [{"session_id": "a", "fg_gameplay_eligible": "true"} for _ in range(2)]
        rows = [dict(type=kind, display=display, address=42) for kind, display in [
            ("Application", 0), ("Intel XeSS-FG", 20), ("Intel XeSS-FG", 30),
            ("Intel XeSS-FG", 40), ("Application", 60),
        ]]
        matches = {index: (frame, "verified") for index, frame in enumerate((0, 1, 1, 1, 1))}
        fields, summary = merge.display_pacing(app, rows, matches, 1000, 100)
        self.assertEqual(summary["midpoint_error_ms"]["samples"], 0)
        self.assertEqual(summary["interval_generated_frames"], {"3": 1})
        self.assertEqual(summary["interval_midpoint_error_ms"], {"samples": 1, "p50": 0, "p95": 0, "p99": 0})
        self.assertEqual(summary["interval_generation_coverage"], 1)
        self.assertEqual(summary["fixed_refresh"]["interval_splits"], {"3:3": 1})
        self.assertEqual(fields[2]["midpoint_error_ms"], 0)

    def test_four_refresh_midpoint_fraction_counts_intervals_without_generation(self):
        app = [{"session_id": "a"} for _ in range(3)]
        rows = [dict(type=kind, display=display, address=42) for kind, display in [
            ("Application", 0), ("Intel XeSS-FG", 20), ("Application", 40), ("Application", 80),
        ]]
        matches = {index: (frame, "verified") for index, frame in enumerate((0, 1, 1, 2))}
        _, summary = merge.display_pacing(app, rows, matches, 1000, 100)
        self.assertEqual(summary["fixed_refresh"]["four_refresh_intervals"], 2)
        self.assertEqual(summary["fixed_refresh"]["four_refresh_midpoint_fraction"], 0.5)

    def test_full_group_sequences_and_phase_distinguish_midpoint_from_uniformity(self):
        app = [{"session_id": "a", "fg_gameplay_eligible": "true"} for _ in range(4)]
        generated = "Intel XeSS-FG"
        rows = [dict(type=kind, display=display, address=42) for kind, display in [
            ("Application", 0), (generated, 30), (generated, 40), (generated, 50), ("Application", 60),
            (generated, 70), (generated, 80), (generated, 90), ("Application", 100),
            (generated, 120), ("Application", 140),
        ]]
        rows[-1]["input_latency_ms"] = 9
        matches = {index: (frame, "verified") for index, frame in enumerate((0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3))}
        fields, summary = merge.display_pacing(app, rows, matches, 1000, 100, expected_generated=3)
        self.assertEqual(summary["fixed_refresh"]["interval_sequences"], {"3:1:1:1": 1, "1:1:1:1": 1, "2:2": 1})
        self.assertEqual(summary["fixed_refresh"]["single_refresh_per_frame_fraction"], 0.5)
        self.assertEqual(summary["full_generation_coverage"], 2/3)
        self.assertEqual(summary["display_fps"], 1000 * 10 / 140)
        self.assertEqual(fields[1]["midpoint_error_ms"], 0)
        self.assertEqual(fields[1]["generated_phase_error_ms"], 15)
        self.assertEqual(fields[3]["generated_ordinal"], 3)
        self.assertNotIn("generated_ordinal", fields[9])
        self.assertEqual(fields[10]["real_input_to_display_ms"], 9)


if __name__ == "__main__":
    unittest.main()
