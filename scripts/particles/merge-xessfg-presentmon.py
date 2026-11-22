"""Join one CMI XeSS-FG capture with the PresentMon CSV from the same session."""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
import csv
from decimal import Decimal
import json
from math import ceil, isfinite
from pathlib import Path


def read_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def optional_int(value):
    return int(value) if value and value.upper() != "NA" else None


def associate(app, presents):
    """Map PresentMon rows to app frames, leaving ambiguous rows unresolved."""
    starts = [int(frame["present_enter_qpc"]) for frame in app]
    ends = [int(frame["present_exit_qpc"]) for frame in app]
    by_id = defaultdict(list)
    address_to_sessions = defaultdict(set)
    for index, frame in enumerate(app):
        by_id[int(frame["present_id"])].append(index)
        address_to_sessions[int(frame["proxy_swap_chain_address"])].add(frame["session_id"])

    typed = {"Application", "Intel XeSS-FG"}
    anchors = defaultdict(lambda: defaultdict(set))
    for row in presents:
        if row["type"] in typed:
            for frame in by_id[row["app_id"]]:
                if starts[frame] <= row["qpc"] <= ends[frame]:
                    anchors[app[frame]["session_id"]][row["address"]].add(frame)

    # The generated DXGI Present can occur inside the proxy call, with the real
    # DXGI Present for the same app ID arriving during a later proxy call.
    verified = {}
    for session, chains in anchors.items():
        ranked = sorted(((len(frames), chain) for chain, frames in chains.items()), reverse=True)
        if ranked[0][0] >= 3 and (len(ranked) == 1 or ranked[0][0] > ranked[1][0]):
            verified[session] = ranked[0][1]

    candidates = defaultdict(list)
    for row_index, row in enumerate(presents):
        if row["type"] not in typed:
            continue
        frames = [frame for frame in by_id[row["app_id"]]
                  if verified.get(app[frame]["session_id"]) == row["address"]]
        if row["type"] == "Application":
            frames = [frame for frame in frames if starts[frame] <= row["qpc"]]
        elif len(frames) > 1:
            # A reused present ID across sessions keeps the proxy-call window.
            frames = [frame for frame in frames if starts[frame] <= row["qpc"] <= ends[frame]]
        if frames:
            candidates[frames[-1], row["type"]].append(row_index)

    matches = {}
    for (frame, kind), rows in candidates.items():
        # Several generated presents share one present ID when the proxy generates
        # more than one interpolated frame; the SDK can submit them before the proxy
        # call that owns the ID.
        if kind == "Intel XeSS-FG" or len(rows) == 1:
            for row_index in rows:
                matches[row_index] = (frame, "verified_app_frame_id")

    direct = defaultdict(list)
    occupied = {frame for frame, _ in matches.values()}
    for index, row in enumerate(presents):
        if row["app_id"] or row["type"] not in {"Application", "Unknown"}:
            continue
        frame = bisect_right(starts, row["qpc"]) - 1
        if frame < 0 or frame in occupied or row["qpc"] > ends[frame]:
            continue
        if app[frame]["session_id"] in address_to_sessions[row["address"]]:
            direct[frame].append(index)
    for frame, rows in direct.items():
        if len(rows) == 1:
            matches[rows[0]] = (frame, "present_qpc")
    return matches, verified


def percentiles(samples):
    values = sorted(samples)
    return {f"p{p}": values[ceil(len(values) * p / 100) - 1] if values else None
            for p in (50, 95, 99)}


def refresh_steps(ticks, period):
    steps = round(ticks / period)
    # Leave non-quantized samples out of the fixed-refresh histogram.
    return steps if steps > 0 and abs(ticks - steps * period) <= 0.1 * period else None


def display_pacing(app, presents, matches, frequency, refresh_hz=None, expected_generated=None):
    """Measure display intervals and attribute each generated frame to its following real frame."""
    timelines = defaultdict(list)
    known = set()
    counts = Counter()
    for index, (frame, _) in matches.items():
        row = presents[index]
        kind = row["type"]
        if kind not in {"Application", "Intel XeSS-FG"}:
            continue
        known.add((frame, kind))
        if row["display"] is None:
            counts["dropped" if row["dropped"] == "1" else "missing_display_time"] += 1
            continue
        timelines[app[frame]["session_id"], row["address"]].append((row["display"], frame, kind, index))
    counts["unmatched_real"] = sum((frame, "Application") not in known for frame in range(len(app)))
    counts["unmatched_display_rows"] = sum(index not in matches for index in range(len(presents)))

    intervals, display_intervals, input_latencies, midpoint, annotations = [], [], [], [], {}
    period = frequency / refresh_hz if refresh_hz is not None else None
    refresh_splits = Counter()
    four_refresh_intervals = 0
    # Multiple generated frames can occupy one interval when the proxy is initialized
    # with more than one interpolated frame. Keep the single-frame fields above for
    # compatibility and summarize every interval here.
    interval_counts = Counter()
    interval_errors = []
    interval_eligible = interval_covered = 0
    all_splits = Counter()
    interval_sequences = Counter()
    full_eligible = full_covered = quantified = uniform = 0
    generated_phase = defaultdict(list)
    for events in timelines.values():
        events.sort()
        display_intervals.extend(milliseconds(b[0] - a[0], frequency) for a, b in zip(events, events[1:])
                                 if b[0] > a[0])
        preceding = None
        generated = []
        for event in events:
            time, frame, kind, index = event
            if kind == "Intel XeSS-FG":
                generated.append(event)
                continue
            latency = presents[index].get("input_latency_ms")
            if latency is not None:
                input_latencies.append(latency)
                annotations.setdefault(index, {})["real_input_to_display_ms"] = latency
            if preceding is not None:
                last_time, last_frame, _, _ = preceding
                gap = milliseconds(time - last_time, frequency)
                intervals.append(gap)
                if period is not None and refresh_steps(time - last_time, period) == 4:
                    four_refresh_intervals += 1
                real_annotation = annotations.setdefault(index, {})
                real_annotation["real_display_interval_ms"] = gap
                # Count every gameplay-eligible interval, including intentional overload skips.
                current = app[frame]
                eligible = current.get("fg_gameplay_eligible", current.get("interpolate")) == "true"
                reset = current.get("reset_history") == "true"
                if eligible and (not reset or current.get("fg_skip_reason") not in {None, "", "0"}):
                    counts["eligible_intervals"] += 1
                if frame == last_frame + 1:
                    ordered = [(gen_time, gen_frame, gen_index) for gen_time, gen_frame, _, gen_index in generated
                               if last_time < gen_time < time]
                    steps = None
                    if period is not None:
                        timestamps = [last_time, *(gen_time for gen_time, _, _ in ordered), time]
                        candidate = [refresh_steps(b - a, period) for a, b in zip(timestamps, timestamps[1:])]
                        if all(step is not None for step in candidate):
                            steps = candidate
                            split = ":".join(map(str, steps))
                            real_annotation["interval_refresh_sequence"] = split
                            interval_sequences[split] += 1

                    # A missing or mismatched generated frame makes the remaining ordinals ambiguous.
                    expected = expected_generated
                    if expected is None:
                        queued = optional_int(current.get("queued"))
                        expected = queued - 1 if queued is not None and queued > 1 else None
                    complete = bool(expected and len(ordered) == expected
                                    and all(gen_frame == frame for _, gen_frame, _ in ordered))
                    if eligible and not reset and expected_generated is not None:
                        full_eligible += 1
                        if complete:
                            full_covered += 1
                            if steps:
                                quantified += 1
                                uniform += all(step == 1 for step in steps)
                    if complete:
                        for ordinal, (gen_time, _, gen_index) in enumerate(ordered, 1):
                            error = milliseconds((expected + 1) * (gen_time - last_time)
                                                 - ordinal * (time - last_time), (expected + 1) * frequency)
                            annotations.setdefault(gen_index, {}).update(generated_ordinal=ordinal,
                                                                        generated_phase_error_ms=error)
                            generated_phase[ordinal].append(error)
                    interval_counts[str(len(generated))] += 1
                    if eligible and (not reset or current.get("fg_skip_reason") not in {None, "", "0"}):
                        interval_eligible += 1
                    displayed = [(gen_time, gen_index) for gen_time, _, _, gen_index in generated
                                 if last_time < gen_time < time]
                    if displayed:
                        gen_time, gen_index = min(displayed, key=lambda gen: abs(2 * gen[0] - last_time - time))
                        error = milliseconds(2 * gen_time - last_time - time, 2 * frequency)
                        interval_errors.append(error)
                        annotations.setdefault(gen_index, {})["midpoint_error_ms"] = error
                        ready = optional_int(current.get("arc_inputs_ready_qpc"))
                        if ready is not None:
                            annotations[gen_index]["arc_inputs_to_generated_ms"] = milliseconds(
                                gen_time - ready, frequency)
                            annotations[gen_index]["arc_inputs_to_midpoint_ms"] = milliseconds(
                                last_time + time - 2 * ready, 2 * frequency)
                        if period is not None:
                            before = refresh_steps(gen_time - last_time, period)
                            after = refresh_steps(time - gen_time, period)
                            if before is not None and after is not None:
                                split = f"{before}:{after}"
                                annotations[gen_index]["refresh_split"] = split
                                all_splits[split] += 1
                        if eligible and not reset:
                            interval_covered += 1
                if len(generated) == 1 and generated[0][1] == frame and frame == last_frame + 1:
                    gen_time, _, _, gen_index = generated[0]
                    if last_time < gen_time < time:
                        error = milliseconds(2 * gen_time - last_time - time, 2 * frequency)
                        midpoint.append(error)
                        phase = {"midpoint_error_ms": error}
                        ready = optional_int(current.get("arc_inputs_ready_qpc"))
                        if ready is not None:
                            phase["arc_inputs_to_generated_ms"] = milliseconds(gen_time - ready, frequency)
                            phase["arc_inputs_to_midpoint_ms"] = milliseconds(
                                last_time + time - 2 * ready, 2 * frequency)
                        if period is not None:
                            before = refresh_steps(gen_time - last_time, period)
                            after = refresh_steps(time - gen_time, period)
                            if before is not None and after is not None:
                                split = f"{before}:{after}"
                                phase["refresh_split"] = split
                                refresh_splits[split] += 1
                        annotations.setdefault(gen_index, {}).update(phase)
                        if eligible and not reset:
                            counts["covered_intervals"] += 1
                    else:
                        counts["ambiguous_intervals"] += 1
                elif generated:
                    counts["ambiguous_intervals"] += 1
            preceding = event
            generated.clear()
    eligible = counts["eligible_intervals"]
    bytes_written = [int(frame["cross_bytes"]) for frame in app if frame.get("cross_bytes")]
    gpu = {}
    for key in ("depth_motion_gl_ns", "hudless_gl_ns", "final_color_gl_ns", "producer_geometry_gpu_ns",
                "producer_hudless_gpu_ns", "producer_color_gpu_ns", "arc_texture_upload_gpu_ns",
                "arc_color_unpack_gpu_ns", "arc_hud_base_unpack_gpu_ns"):
        samples = [int(frame[key]) / 1_000_000 for frame in app if frame.get(key)]
        if samples:
            gpu[key.removesuffix("_ns") + "_ms"] = {"samples": len(samples), **percentiles(samples)}
    summary = {
        "display_interval_ms": {"samples": len(display_intervals), **percentiles(display_intervals)},
        "real_display_interval_ms": {"samples": len(intervals), **percentiles(intervals)},
        "display_fps": 1000 * len(display_intervals) / sum(display_intervals) if display_intervals else None,
        "real_display_fps": 1000 * len(intervals) / sum(intervals) if intervals and sum(intervals) else None,
        "expected_generated_frames": expected_generated,
        "full_generation_coverage": full_covered / full_eligible if full_eligible else None,
        "generated_phase_error_ms": {str(k): {"samples": len(v), **percentiles(v)}
                                     for k, v in sorted(generated_phase.items())},
        "absolute_generated_phase_error_ms": {str(k): {"samples": len(v), **percentiles([abs(error) for error in v])}
                                              for k, v in sorted(generated_phase.items())},
        "real_input_to_display_ms": {"samples": len(input_latencies), **percentiles(input_latencies)},
        "midpoint_error_ms": {"samples": len(midpoint), **percentiles(midpoint)},
        "absolute_midpoint_error_ms": percentiles([abs(value) for value in midpoint]),
        "generation_coverage": counts["covered_intervals"] / eligible if eligible else None,
        "interval_generated_frames": dict(interval_counts),
        "interval_midpoint_error_ms": {"samples": len(interval_errors), **percentiles(interval_errors)},
        "absolute_interval_midpoint_error_ms": percentiles([abs(value) for value in interval_errors]),
        "interval_generation_coverage": interval_covered / interval_eligible if interval_eligible else None,
        "cross_bytes_per_frame": {"samples": len(bytes_written), **percentiles(bytes_written)},
        "hud_copy_routes": dict(Counter(frame["hud_copy_route"] for frame in app if frame.get("hud_copy_route"))),
        "gpu_stages": gpu,
        "counts": dict(counts),
    }
    if period is not None:
        summary["fixed_refresh"] = {
            "refresh_hz": refresh_hz,
            "splits": dict(refresh_splits),
            "interval_splits": dict(all_splits),
            "interval_sequences": dict(interval_sequences),
            "complete_quantized_intervals": quantified,
            "single_refresh_per_frame_intervals": uniform,
            "single_refresh_per_frame_fraction": uniform / quantified if quantified else None,
            "unquantized_intervals": len(midpoint) - sum(refresh_splits.values()),
            "four_refresh_intervals": four_refresh_intervals,
            "four_refresh_midpoint_fraction": refresh_splits["2:2"] / four_refresh_intervals
            if four_refresh_intervals else None,
        }
    return annotations, summary


def proxy_breakdown(app, presents, verified, frequency):
    by_chain = defaultdict(list)
    for row in presents:
        if row["type"] in {"Application", "Intel XeSS-FG"}:
            by_chain[row["address"]].append(row)
    timelines = {}
    for chain in set(verified.values()):
        rows = sorted(by_chain[chain], key=lambda row: row["qpc"])
        starts = [row["qpc"] for row in rows]
        longest = max((row["api_ticks"] or 0 for row in rows), default=0)
        timelines[chain] = (rows, starts, longest)

    for frame in app:
        timeline = timelines.get(verified.get(frame["session_id"]))
        if timeline is None:
            yield {}
            continue
        rows, starts, longest = timeline
        enter, exit_qpc = int(frame["present_enter_qpc"]), int(frame["present_exit_qpc"])
        first = bisect_left(starts, enter)
        end = bisect_right(starts, exit_qpc)
        if first == end:
            yield {}
            continue
        data = {"proxy_to_first_dxgi_ms": milliseconds(starts[first] - enter, frequency)}
        seen = rows[bisect_left(starts, enter - longest):end]
        if all(row["api_ticks"] is not None for row in seen):
            # The prior app ID can flip within this proxy call. Intersect time
            # intervals on the verified chain and count each instant only once.
            covered, covered_until = 0, enter
            for row in seen:
                left = max(row["qpc"], covered_until)
                right = min(row["qpc"] + row["api_ticks"], exit_qpc)
                if right > left:
                    covered += right - left
                    covered_until = right
            data.update(proxy_dxgi_overlap_ms=milliseconds(covered, frequency),
                        proxy_outside_dxgi_ms=milliseconds(exit_qpc - enter - covered, frequency))
        yield data


def milliseconds(delta, frequency):
    return round(delta * 1000 / frequency, 6) if delta is not None else ""


def delta(left, right):
    return left - right if left is not None and right is not None else None


def geometry_wait(frame, rtx_end, arc_start, frequency):
    geometry_start = optional_int(frame.get("arc_geometry_dma_start_qpc"))
    geometry_end = optional_int(frame.get("arc_geometry_dma_end_qpc"))
    if None in (rtx_end, arc_start, geometry_start, geometry_end):
        return {}
    if geometry_start > geometry_end or geometry_end > arc_start or rtx_end > arc_start:
        return {}

    overlap = max(0, geometry_end - max(rtx_end, geometry_start))
    before = max(0, geometry_start - rtx_end)
    after = arc_start - max(geometry_end, rtx_end)
    fence = optional_int(frame.get("arc_color_fence_observed_qpc"))
    after_fence = max(0, arc_start - max(geometry_end, fence, rtx_end)) if fence is not None else None
    return dict(arc_wait_before_geometry_ms=milliseconds(before, frequency),
                arc_wait_overlapping_geometry_ms=milliseconds(overlap, frequency),
                arc_wait_after_geometry_ms=milliseconds(after, frequency),
                arc_wait_after_observed_fence_ms=milliseconds(after_fence, frequency))


def merge(metadata_path, presentmon_path, refresh_hz=None, multiplier=None):
    metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    native_path = metadata_path.with_name(metadata_path.stem + "-xessfg.csv")
    app = sorted((row for row in read_rows(native_path) if row.get("present_enter_qpc")
                  and row.get("present_exit_qpc")), key=lambda row: int(row["present_enter_qpc"]))
    if not app:
        print(f"No XeSS-FG Present timestamps in {native_path.name}. Preserved both source captures.")
        return
    frequency = int(app[0]["qpc_frequency"])
    if frequency <= 0 or any(int(frame["qpc_frequency"]) != frequency for frame in app):
        raise ValueError("Inconsistent QPC frequency in frame capture.")
    raw = read_rows(presentmon_path)
    if raw:
        fields = raw[0].keys()
        if not {"ProcessID", "SwapChainAddress"} <= fields or not ({"MsUntilDisplayed", "msUntilDisplayed"} & fields):
            raise ValueError("PresentMon CSV must include display tracking.")
        if not ({"TimeInQPC", "QPCTime"} & fields):
            raise ValueError("PresentMon CSV is missing the raw Present QPC column. Use --qpc_time.")
    pid = int(metadata["process_id"])
    first = int(app[0]["present_enter_qpc"])
    last = int(app[-1]["present_exit_qpc"])
    ids = {int(frame["present_id"]) for frame in app}
    presents = []
    for row in raw:
        if optional_int(row["ProcessID"]) != pid:
            continue
        qpc = optional_int(row.get("TimeInQPC") or row.get("QPCTime"))
        app_id = optional_int(row.get("AppFrameId"))
        if qpc is None or (not first <= qpc <= last and (app_id not in ids or not first <= qpc <= last + frequency)):
            continue
        until = row.get("MsUntilDisplayed") or row.get("msUntilDisplayed", "")
        dropped = row.get("Dropped", "")
        api = row.get("MsInPresentAPI") or row.get("msInPresentAPI", "")
        api_ticks = round(Decimal(api) * frequency / 1000) if api and api.upper() != "NA" else None
        display = None
        if dropped != "1" and until and until.upper() != "NA" and Decimal(until) > 0:
            display = qpc + round(Decimal(until) * frequency / 1000)
        latency = row.get("MsAllInputToPhotonLatency", "")
        input_latency = float(latency) if latency and latency.upper() != "NA" else None
        presents.append(dict(qpc=qpc, address=int(row["SwapChainAddress"], 16), app_id=app_id,
                             frame_id=row.get("FrameId", ""), type=row.get("FrameType") or "Unknown",
                             mode=row.get("PresentMode", ""), display=display, dropped=dropped, api_ticks=api_ticks,
                             input_latency_ms=input_latency if input_latency is not None and input_latency > 0 else None))

    matched, verified = associate(app, presents)
    recorded_multiplier = metadata.get("xessfg_config", {}).get("multiplier")
    if multiplier is not None and recorded_multiplier is not None and recorded_multiplier != multiplier:
        raise ValueError(f"Requested {multiplier}x does not match the recorded {recorded_multiplier}x")
    effective_multiplier = recorded_multiplier if recorded_multiplier is not None else multiplier
    expected = effective_multiplier - 1 if effective_multiplier in (2, 3, 4) else None
    pacing_fields, pacing = display_pacing(app, presents, matched, frequency, refresh_hz, expected)
    per_frame = defaultdict(list)
    for row, (frame, method) in matched.items():
        per_frame[frame].append((row, method))
    output = metadata_path.with_name(metadata_path.stem + "-xessfg-timeline.csv")
    fields = ("match", "frame", "session_id", "present_id", "sequence", "present_enter_qpc", "present_exit_qpc",
              "arc_dma_ready_qpc", "arc_inputs_ready_qpc", "inputs_minus_present_ms", "dma_to_inputs_ms", "present_ms",
              "arc_input_prepare_start_qpc", "dma_to_prepare_ms", "prepare_to_inputs_ms",
              "color_submit_qpc", "color_fence_token", "color_gl_fence_at_submit", "color_gl_fence_at_enter",
              "color_gl_fence_at_exit", "color_transfer_fence_at_enter", "color_transfer_fence_at_exit",
              "rtx_color_copy_start_qpc", "rtx_color_copy_end_qpc", "arc_color_dma_start_qpc",
              "publish_to_rtx_start_ms", "rtx_copy_ms", "rtx_end_to_arc_start_ms", "arc_dma_ms",
              "rtx_geometry_copy_start_qpc", "rtx_geometry_copy_end_qpc", "arc_geometry_dma_start_qpc",
              "arc_geometry_dma_end_qpc", "arc_color_fence_observed_qpc",
              "arc_wait_before_geometry_ms", "arc_wait_overlapping_geometry_ms", "arc_wait_after_geometry_ms",
              "arc_wait_after_observed_fence_ms",
              "transport", "cross_bytes", "cross_geometry_bytes", "cross_color_bytes",
              "pending_present_at_enqueue", "present_enqueue_ns", "present_backpressure_ns", "producer_wait_ns",
              "present_queue_delay_ns", "worker_present_duration_ns", "arc_color_unpack_gpu_ns",
              "presenter_thread_id", "proxy_present_thread_cpu_ns", "proxy_top_level", "proxy_to_first_dxgi_ms",
              "proxy_dxgi_overlap_ms", "proxy_outside_dxgi_ms",
              "pm_frame_type", "pm_swap_chain", "pm_frame_id", "pm_app_frame_id", "pm_present_mode",
              "pm_present_qpc", "pm_present_api_ms", "display_qpc", "pm_dropped",
              "present_exit_to_display_ms", "real_display_interval_ms", "real_input_to_display_ms", "midpoint_error_ms",
              "interval_refresh_sequence", "generated_ordinal", "generated_phase_error_ms",
              "refresh_split", "arc_inputs_to_generated_ms", "arc_inputs_to_midpoint_ms",
              "fg_gameplay_eligible", "fg_skip_reason", "fg_geometry_width", "fg_geometry_height",
              "producer_hudless_gpu_ns", "hud_copy_route", "arc_hud_base_unpack_gpu_ns", "arc_hud_base_ready_qpc")
    with output.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fields)
        writer.writeheader()
        for index, (frame, breakdown) in enumerate(zip(app, proxy_breakdown(app, presents, verified, frequency))):
            enter = int(frame["present_enter_qpc"])
            exit_qpc = int(frame["present_exit_qpc"])
            dma = optional_int(frame.get("arc_dma_ready_qpc"))
            inputs = optional_int(frame.get("arc_inputs_ready_qpc"))
            prepare_start = optional_int(frame.get("arc_input_prepare_start_qpc"))
            base = {field: frame.get(field, "") for field in fields if field in frame}
            submit = optional_int(frame.get("color_submit_qpc"))
            rtx_start = optional_int(frame.get("rtx_color_copy_start_qpc"))
            rtx_end = optional_int(frame.get("rtx_color_copy_end_qpc"))
            arc_start = optional_int(frame.get("arc_color_dma_start_qpc"))
            base.update(publish_to_rtx_start_ms=milliseconds(delta(rtx_start, submit), frequency),
                        rtx_copy_ms=milliseconds(delta(rtx_end, rtx_start), frequency),
                        rtx_end_to_arc_start_ms=milliseconds(delta(arc_start, rtx_end), frequency),
                        arc_dma_ms=milliseconds(delta(dma, arc_start), frequency),
                        inputs_minus_present_ms=milliseconds(delta(inputs, enter), frequency),
                        dma_to_inputs_ms=milliseconds(delta(inputs, dma), frequency),
                        dma_to_prepare_ms=milliseconds(delta(prepare_start, dma), frequency),
                        prepare_to_inputs_ms=milliseconds(delta(inputs, prepare_start), frequency),
                        present_ms=milliseconds(exit_qpc - enter, frequency))
            base.update(geometry_wait(frame, rtx_end, arc_start, frequency))
            base.update(breakdown)
            for row, method in per_frame[index] or [(None, "unmatched_app")]:
                present = presents[row] if row is not None else None
                writer.writerow(dict(base, match=method, **display_fields(present, exit_qpc, frequency),
                                     **pacing_fields.get(row, {})))
        for index, row in enumerate(presents):
            if index not in matched:
                writer.writerow(dict(match="unmatched_display", **display_fields(row, None, frequency)))

    rows_with_matches = sum(bool(rows) for rows in per_frame.values())
    print(f"Timeline: {output}")
    report = output.with_name(metadata_path.stem + "-xessfg-pacing.json")
    report.write_text(json.dumps(pacing, indent=2) + "\n", encoding="utf-8")
    print(f"Display pacing: {report}")
    print(f"Matched app frames: {rows_with_matches}/{len(app)}. PresentMon rows: {len(matched)}/{len(presents)}. "
          f"Unmatched rows are retained.")
    generated = [presents[index] for index in matched if presents[index]["type"] == "Intel XeSS-FG"]
    if generated:
        displayed = sum(row["display"] is not None for row in generated)
        print(f"Matched generated frames displayed: {displayed}/{len(generated)}.")
    modes = Counter((presents[index]["type"], presents[index]["mode"]) for index in matched
                    if presents[index]["display"] is not None)
    for (kind, mode), count in sorted(modes.items()):
        print(f"Displayed {kind} | {mode or 'unknown mode'} | {count}")


def display_fields(row, exit_qpc, frequency):
    if row is None:
        return {}
    return dict(pm_frame_type=row["type"], pm_swap_chain=f"0x{row['address']:x}", pm_app_frame_id=row["app_id"],
                pm_frame_id=row["frame_id"], pm_present_mode=row["mode"],
                pm_present_qpc=row["qpc"], pm_present_api_ms=milliseconds(row["api_ticks"], frequency),
                display_qpc=row["display"], pm_dropped=row["dropped"],
                present_exit_to_display_ms=milliseconds(delta(row["display"], exit_qpc), frequency))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frame_capture", type=Path)
    parser.add_argument("presentmon_csv", type=Path)
    parser.add_argument("--refresh-hz", type=float, help="Fixed display refresh rate for the phase histogram")
    parser.add_argument("--multiplier", type=int, choices=(2, 3, 4), help="Known multiplier of a legacy capture")
    args = parser.parse_args()
    if args.refresh_hz is not None and (not isfinite(args.refresh_hz) or args.refresh_hz <= 0):
        parser.error("--refresh-hz must be a finite positive number")
    merge(args.frame_capture, args.presentmon_csv, args.refresh_hz, args.multiplier)
