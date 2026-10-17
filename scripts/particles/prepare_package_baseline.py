"""Snapshot a Git ref, the preserved index or worktree in build/ for same-driver comparisons."""
from pathlib import Path
import argparse
import hashlib
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--ref',default='HEAD',help='Immutable Git ref, INDEX or WORKTREE (snapshot before editing)')
    parser.add_argument('--output',default='build/package-environment-baseline',help='Snapshot directory inside build/')
    args=parser.parse_args()
    out=(ROOT/args.output).resolve()
    if (ROOT/'build').resolve() not in out.parents:
        parser.error('Output must be a directory inside build/')

    def source(path):
        if args.ref=='WORKTREE':
            return (ROOT/path).read_bytes()
        return git('show',(':' if args.ref=='INDEX' else args.ref+':')+path)

    digest=hashlib.sha256()
    def snapshot_source(path):
        data=source(path)
        digest.update(path.encode());digest.update(b'\0');digest.update(data)
        return data
    prefix = "src/main/java/com/iridium126/createmanaindustry/client/particles/packages/"
    names = ("PackageCollisionGpu", "PackagePhysicsGpu", "PackageEnvironmentGpu", "PackageReadbackRing")
    if b'PackageEnvironmentLayout' in source(prefix+'PackageEnvironmentGpu.java'):
        names += ('PackageEnvironmentLayout',)
    for name in names:
        text = snapshot_source(prefix + name + ".java").decode("utf-8")
        for candidate in names:
            text = text.replace(candidate, candidate + "Baseline")
        # The benchmark has no moving sources; the production view's typed ownership token
        # cannot accept the renamed historical physics object.
        text = text.replace("for(var view:moving)view.claimStep(this);", "if(!moving.isEmpty())throw new IllegalArgumentException(\"Static-world benchmark only\");")
        destination = out / "java" / (name + "Baseline.java")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")
    resources = "src/main/resources/assets/createmanaindustry/"
    if args.ref=='WORKTREE':
        paths = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT/resources/'shaders/particles/packages').rglob('*') if p.is_file())
    elif args.ref=='INDEX':
        paths = git('ls-files','--cached',resources+'shaders/particles/packages').decode().splitlines()
    else:
        paths = git('ls-tree','-r','--name-only',args.ref,resources+'shaders/particles/packages').decode().splitlines()
    for path in paths:
        destination = out / "assets/createmanaindustry" / path.removeprefix(resources)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(snapshot_source(path))
    if args.ref in ('WORKTREE','INDEX'):
        summary = digest.hexdigest() if args.ref=='WORKTREE' else hashlib.sha256(git('diff','--cached','--binary')).hexdigest()
        commit = args.ref+'@'+git('rev-parse','HEAD').decode().strip()+'+'+summary
    else:
        commit = git('rev-parse',args.ref).decode().strip()
    (out / "commit.txt").write_text(commit + "\n", encoding="utf-8")
    print(f"Prepared package GPU baseline {commit}; {len(paths)} original shader resources")


if __name__ == "__main__":
    main()
