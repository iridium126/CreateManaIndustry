"""Prepare the unmodified HEAD GPU implementation under distinct names in build/, for same-driver comparisons."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/package-environment-baseline"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


def main():
    prefix = "src/main/java/com/iridium126/createmanaindustry/client/particles/packages/"
    names = ("PackageCollisionGpu", "PackagePhysicsGpu", "PackageEnvironmentGpu")
    for name in names:
        source = git("show", "HEAD:" + prefix + name + ".java").decode("utf-8")
        for candidate in names:
            source = source.replace(candidate, candidate + "Baseline")
        # The benchmark has no moving sources; the production view's typed ownership token
        # cannot accept the renamed historical physics object.
        source = source.replace("for(var view:moving)view.claimStep(this);", "if(!moving.isEmpty())throw new IllegalArgumentException(\"Static-world benchmark only\");")
        destination = OUT / "java" / (name + "Baseline.java")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(source, encoding="utf-8")
    resources = "src/main/resources/assets/createmanaindustry/"
    paths = git("ls-tree", "-r", "--name-only", "HEAD", resources + "shaders/particles/packages").decode().splitlines()
    for path in paths:
        destination = OUT / "assets/createmanaindustry" / path.removeprefix(resources)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(git("show", "HEAD:" + path))
    commit = git("rev-parse", "HEAD").decode().strip()
    (OUT / "commit.txt").write_text(commit + "\n", encoding="utf-8")
    print(f"Prepared package GPU baseline {commit}; {len(paths)} original shader resources")


if __name__ == "__main__":
    main()
