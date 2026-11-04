"""Pinned development dependencies. Runtime distributions contain no SDK or NVRTC."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
CACHE=ROOT/'.gradle/particle-rt'
BASE='https://developer.download.nvidia.com/compute/cuda/redist/'
MANIFEST_SHA='249e28a83008d711d5f72880541c8be6253f6d61608461de4fcb715554a6cf17'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def download(path,url,digest):
    if not path.is_file() or sha(path)!=digest:
        subprocess.run(['curl.exe','--http1.1','-fL','--retry','2','--connect-timeout','20','--max-time','900','-o',str(path),url],check=True)
    if sha(path)!=digest:raise ValueError('Dependency checksum mismatch: '+str(path))

def main():
    p=argparse.ArgumentParser();p.add_argument('--reuse',type=Path);a=p.parse_args();CACHE.mkdir(parents=True,exist_ok=True)
    metadata=CACHE/'redistrib_12.8.1.json';download(metadata,BASE+metadata.name,MANIFEST_SHA);data=json.loads(metadata.read_text())
    reused=json.loads(a.reuse.read_text()) if a.reuse else {}
    packages={'optix':('https://github.com/NVIDIA/optix-dev/archive/a1280c1863ff19d87f8e827468a4cc906ba9032a.zip','e3b234db6cf9e0b01c4b29af73b14aa9a43f908ddf89917f5f5ff5bbc4a7cbc5')}
    for key in ('cuda_cudart','cuda_nvcc','cuda_cccl'):
        item=data[key]['windows-x86_64'];packages[key]=(BASE+item['relative_path'],item['sha256'])
    result={}
    for key,(url,digest) in packages.items():
        previous=reused.get(key,{})
        if previous.get('sha256')==digest and Path(previous['root']).is_dir():result[key]=previous;continue
        archive=CACHE/(key+'.zip');download(archive,url,digest);target=CACHE/key;marker=target/'.sha256'
        if not marker.is_file() or marker.read_text().strip()!=digest:
            target.mkdir(exist_ok=True)
            with zipfile.ZipFile(archive) as z:
                if any(not (target/n).resolve().is_relative_to(target.resolve()) for n in z.namelist()):raise ValueError('Archive path escaped cache')
                z.extractall(target)
            marker.write_text(digest+'\n')
        children=[x for x in target.iterdir() if x.is_dir()];root=children[0] if len(children)==1 else target
        result[key]=dict(root=str(root),url=url,sha256=digest)
    (CACHE/'dependencies.json').write_text(json.dumps(result,indent=2)+'\n');print('Verified RT development dependencies; SDK-free runtime build enabled')
if __name__=='__main__':main()
