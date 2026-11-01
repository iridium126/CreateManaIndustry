"""Freeze workspace inputs before experiments; never replace an existing baseline."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    target = args.directory.resolve()
    target.mkdir(parents=True, exist_ok=False)
    files = {}
    for directory in ('src/main', 'scripts/particles', 'gradle'):
        for source in sorted((root / directory).rglob('*')):
            if not source.is_file() or '__pycache__' in source.parts:
                continue
            relative = source.relative_to(root)
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            files[relative.as_posix()] = hashlib.sha256(destination.read_bytes()).hexdigest()
    log = root / 'run/logs/debug.log'
    shutil.copyfile(log, target / 'client-debug.log')
    manifest = dict(commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                    status=subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True),
                    log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(), files=files)
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    print(f'Frozen {len(files)} files: {target}')


if __name__ == '__main__':
    main()
