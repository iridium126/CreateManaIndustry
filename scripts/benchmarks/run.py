"""Run an opt-in Gradle validation/benchmark and keep its outputs outside Git."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import uuid


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Reuse this directory when adding index comparison rounds")
    parser.add_argument("gradle_args", nargs=argparse.REMAINDER, help="Task names and Gradle flags")
    options = parser.parse_args()
    arguments = options.gradle_args
    if arguments[:1] == ["--"]:
        arguments = arguments[1:]
    if not arguments:
        parser.error("Specify at least one Gradle task")
    if any(arg.startswith(("-PbenchmarkOutput", "-PpackageIndexOutput")) for arg in arguments):
        parser.error("Use --output to keep metadata, logs and samples in the same directory")
    root = Path(__file__).resolve().parents[2]
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    directory = (root / (options.output or Path("build/benchmarks") / run_id)).resolve()
    if not directory.is_relative_to(root / "build/benchmarks"):
        parser.error("Output must be inside build/benchmarks")
    directory.mkdir(parents=True, exist_ok=True)
    wrapper = root / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    command = [str(wrapper), "-I", "scripts/particles/validation.init.gradle",
               "--no-configuration-cache", "--console=plain", f"-PbenchmarkOutput={directory}", *arguments]
    record = {"command": command, "startedAt": datetime.now(timezone.utc).isoformat(), "exitCode": None}
    record_path = directory / f"run-{run_id}.json"
    record_path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Results: {directory}", flush=True)
    # Each invocation has its own log, including appended comparison rounds.
    with (directory / f"run-{run_id}.log").open("w", encoding="utf-8") as log:
        with subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                log.write(line)
                print(line, end="", flush=True)
            result = process.wait()
    record.update(exitCode=result, finishedAt=datetime.now(timezone.utc).isoformat())
    record_path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
