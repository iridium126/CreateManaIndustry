"""Interpret backend metadata from current and archived Storm captures."""

import csv
from spatial_audit import audit


def storm_backend(metadata):
    fields = backend_fields(metadata)
    requested = fields.get("requested", "")
    state = fields.get("active", "")
    api = fields.get("simulation_api", "")
    if state == "gl":
        state, api = "running", "opengl"
    elif state == "rt" or state.startswith("rt-"):
        state = {"rt": "running", "rt-idle": "idle", "rt-waiting": "starting",
                 "rt-failed": "failed"}.get(state, "failed")
        if api in ("", "none", "cuda"):
            api = "optix" if state == "running" else "none"
    return requested, state, api


def backend_fields(metadata):
    return dict(word.split("=", 1) for word in str(metadata or "").split() if "=" in word)


def validate_storm_backend(metadata, path):
    requested, state, api = storm_backend(metadata)
    if (state and state != "running") or (requested in ("rt", "optix", "dxr")
                                           and not (state == "running" and api in ("optix", "dxr"))):
        raise ValueError(f"Storm simulation backend was not active for qualification: {metadata}")
    if state == "running" and api in ("optix", "dxr"):
        if backend_fields(metadata).get("active") == "running":
            receipts = path.with_name(path.stem + "-storm.csv")
            if not receipts.is_file() or not storm_receipts_drained(receipts):
                raise ValueError(f"Incomplete native Storm lifecycle evidence: {path}")
        spatial = path.with_name(path.stem + "-spatial.csv")
        if not spatial.is_file() or not audit(spatial, True)["passed"]:
            raise ValueError(f"Incomplete native Storm lifecycle evidence: {path}")


def storm_receipts_drained(path):
    sequences = {}
    with path.open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            worker, sequence = int(row["worker"]), int(row["sequence"])
            if row["applied"] != "true" or sequence <= sequences.get(worker, 0):
                return False
            sequences[worker] = sequence
    return bool(sequences)
