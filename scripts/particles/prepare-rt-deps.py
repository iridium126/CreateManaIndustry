"""Project-local, checksum-locked NVIDIA headers/NVRTC. No driver or global install."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.gradle/neighbor-rt'
PACKAGES = {
    'optix': ('https://github.com/NVIDIA/optix-dev/archive/a1280c1863ff19d87f8e827468a4cc906ba9032a.zip',
              'e3b234db6cf9e0b01c4b29af73b14aa9a43f908ddf89917f5f5ff5bbc4a7cbc5'),
}
METADATA_SHA256='249e28a83008d711d5f72880541c8be6253f6d61608461de4fcb715554a6cf17'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def prepare(name, url, expected):
    archive = CACHE / (name+'.zip')
    if not archive.exists() or digest(archive) != expected:
        subprocess.run(['curl.exe', '--http1.1', '-fL', '--retry', '2', '--connect-timeout', '20', '--max-time', '900', '-o', str(archive), url], check=True)
    if digest(archive) != expected:
        raise ValueError('Dependency checksum mismatch: '+name)
    target = CACHE/name
    marker = target/'.distribution-sha256'
    if not marker.exists() or marker.read_text().strip() != expected:
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                path = (target/entry.filename).resolve()
                if not path.is_relative_to(target.resolve()):
                    raise ValueError('Invalid archive path')
            bundle.extractall(target)
        marker.write_text(expected+'\n')
    children = [p for p in target.iterdir() if p.is_dir()]
    return str(children[0] if len(children) == 1 else target)


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    metadata_path=CACHE/'redistrib_12.8.1.json'
    if not metadata_path.exists():
        subprocess.run(['curl.exe','--http1.1','-fL','--retry','2','--connect-timeout','20','--max-time','60','-o',str(metadata_path),'https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.8.1.json'],check=True)
    if digest(metadata_path)!=METADATA_SHA256:
        raise ValueError('CUDA redistribution manifest checksum mismatch')
    metadata = json.loads(metadata_path.read_text())
    for key in ('cuda_nvrtc', 'cuda_cudart', 'cuda_nvcc'):
        item = metadata[key]['windows-x86_64']
        PACKAGES[key] = ('https://developer.download.nvidia.com/compute/cuda/redist/'+item['relative_path'], item['sha256'])
    result = {}
    for name, (url, expected) in PACKAGES.items():
        print('Preparing '+name, flush=True)
        result[name] = dict(root=prepare(name, url, expected), url=url, sha256=expected)
    (CACHE/'dependencies.json').write_text(json.dumps(result, indent=2)+'\n')
    print('Locked dependencies ready:', CACHE/'dependencies.json', flush=True)


if __name__ == '__main__':
    main()
