"""Join one CMI XeSS-FG capture with the PresentMon CSV from the same session."""

import argparse
from bisect import bisect_right
from collections import defaultdict
import csv
from decimal import Decimal
import json
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
    by_id = {int(frame["present_id"]): index for index, frame in enumerate(app)}
    address_to_sessions = defaultdict(set)
    for frame in app:
        address_to_sessions[int(frame["proxy_swap_chain_address"])].add(frame["session_id"])

    typed = {"Application", "Intel XeSS-FG"}
    anchors = defaultdict(lambda: defaultdict(set))
    for row in presents:
        frame = by_id.get(row["app_id"])
        if frame is not None and row["type"] in typed and starts[frame] <= row["qpc"] <= ends[frame]:
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
        frame = by_id.get(row["app_id"])
        if frame is not None and row["type"] in typed and row["qpc"] >= starts[frame]:
            session = app[frame]["session_id"]
            if verified.get(session) == row["address"]:
                candidates[frame, row["type"]].append(row_index)

    matches = {}
    for (frame, _), rows in candidates.items():
        if len(rows) == 1:
            matches[rows[0]] = (frame, "verified_app_frame_id")

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
    return matches


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


def merge(metadata_path, presentmon_path):
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
        display = None
        if dropped != "1" and until and until.upper() != "NA" and Decimal(until) > 0:
            display = qpc + round(Decimal(until) * frequency / 1000)
        presents.append(dict(qpc=qpc, address=int(row["SwapChainAddress"], 16), app_id=app_id,
                             frame_id=row.get("FrameId", ""), type=row.get("FrameType") or "Unknown",
                             mode=row.get("PresentMode", ""), display=display, dropped=dropped))

    matched = associate(app, presents)
    per_frame = defaultdict(list)
    for row, (frame, method) in matched.items():
        per_frame[frame].append((presents[row], method))
    output = metadata_path.with_name(metadata_path.stem + "-xessfg-timeline.csv")
    fields = ("match", "frame", "session_id", "present_id", "sequence", "present_enter_qpc", "present_exit_qpc",
              "arc_dma_ready_qpc", "arc_inputs_ready_qpc", "inputs_minus_present_ms", "dma_to_inputs_ms", "present_ms",
              "color_submit_qpc", "color_fence_token", "color_gl_fence_at_submit", "color_gl_fence_at_enter",
              "color_gl_fence_at_exit", "color_transfer_fence_at_enter", "color_transfer_fence_at_exit",
              "rtx_color_copy_start_qpc", "rtx_color_copy_end_qpc", "arc_color_dma_start_qpc",
              "publish_to_rtx_start_ms", "rtx_copy_ms", "rtx_end_to_arc_start_ms", "arc_dma_ms",
              "rtx_geometry_copy_start_qpc", "rtx_geometry_copy_end_qpc", "arc_geometry_dma_start_qpc",
              "arc_geometry_dma_end_qpc", "arc_color_fence_observed_qpc",
              "arc_wait_before_geometry_ms", "arc_wait_overlapping_geometry_ms", "arc_wait_after_geometry_ms",
              "arc_wait_after_observed_fence_ms",
              "pm_frame_type", "pm_swap_chain", "pm_frame_id", "pm_app_frame_id", "pm_present_mode",
              "pm_present_qpc", "display_qpc", "pm_dropped",
              "present_exit_to_display_ms")
    with output.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fields)
        writer.writeheader()
        for index, frame in enumerate(app):
            enter = int(frame["present_enter_qpc"])
            exit_qpc = int(frame["present_exit_qpc"])
            dma = optional_int(frame.get("arc_dma_ready_qpc"))
            inputs = optional_int(frame.get("arc_inputs_ready_qpc"))
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
                        present_ms=milliseconds(exit_qpc - enter, frequency))
            base.update(geometry_wait(frame, rtx_end, arc_start, frequency))
            for row, method in per_frame[index] or [(None, "unmatched_app")]:
                writer.writerow(dict(base, match=method, **display_fields(row, exit_qpc, frequency)))
        for index, row in enumerate(presents):
            if index not in matched:
                writer.writerow(dict(match="unmatched_display", **display_fields(row, None, frequency)))

    rows_with_matches = sum(bool(rows) for rows in per_frame.values())
    print(f"Timeline: {output}")
    print(f"Matched app frames: {rows_with_matches}/{len(app)}. PresentMon rows: {len(matched)}/{len(presents)}. "
          f"Unmatched rows are retained.")
    generated = [presents[index] for index in matched if presents[index]["type"] == "Intel XeSS-FG"]
    if generated:
        displayed = sum(row["display"] is not None for row in generated)
        print(f"Matched generated frames displayed: {displayed}/{len(generated)}.")


def display_fields(row, exit_qpc, frequency):
    if row is None:
        return {}
    return dict(pm_frame_type=row["type"], pm_swap_chain=f"0x{row['address']:x}", pm_app_frame_id=row["app_id"],
                pm_frame_id=row["frame_id"], pm_present_mode=row["mode"],
                pm_present_qpc=row["qpc"], display_qpc=row["display"], pm_dropped=row["dropped"],
                present_exit_to_display_ms=milliseconds(delta(row["display"], exit_qpc), frequency))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frame_capture", type=Path)
    parser.add_argument("presentmon_csv", type=Path)
    args = parser.parse_args()
    merge(args.frame_capture, args.presentmon_csv)
