"""Optional native-development headers, pinned by commit and SHA-256. No driver changes."""
import argparse,hashlib,json,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def prepare(download=False):
    records=json.loads((ROOT/'native/offload/media-headers.lock.json').read_text())
    cache=ROOT/'.gradle/hardware-offload/media-headers';cache.mkdir(parents=True,exist_ok=True)
    for record in records:
        target=cache/Path(record['path']).name
        if not target.exists():
            if not download:raise ValueError('Missing optional header; run with --download: '+str(target))
            value=urllib.request.urlopen(record['url'],timeout=60).read()
            if hashlib.sha256(value).hexdigest()!=record['sha256']:raise ValueError('Downloaded header SHA mismatch')
            target.write_bytes(value)
        if hashlib.sha256(target.read_bytes()).hexdigest()!=record['sha256']:raise ValueError('Cached header SHA mismatch: '+str(target))
    lock=cache/'lock.json'
    if lock.exists() and json.loads(lock.read_text())!=records:raise ValueError('Cached header lock mismatch')
    if not lock.exists():lock.write_text(json.dumps(records,indent=2)+'\n')
    return records
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--download',action='store_true');a=p.parse_args();print(json.dumps(dict(verified=len(prepare(a.download)),scope='optional native build only')))
