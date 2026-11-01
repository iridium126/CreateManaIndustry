"""Verify a frozen input set and export MODEL points plus a bounded swept-query oracle stream."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from neighbor_reference import load_capture,candidate_batches


def main():
    p=argparse.ArgumentParser();p.add_argument('capture',type=Path);p.add_argument('output',type=Path);p.add_argument('--radius',type=float,required=True);p.add_argument('--horizon',type=float,default=0);p.add_argument('--queries',type=int,default=64);p.add_argument('--batch',type=int,default=1024);p.add_argument('--contact',action='store_true',help='exclude corpses for contact queries; default separation retains them');a=p.parse_args()
    if a.queries<0:raise ValueError('negative query count')
    manifest,members=load_capture(a.capture,include_corpses=not a.contact);a.output.mkdir(parents=True,exist_ok=False)
    with (a.output/'points.float4.bin').open('wb') as out:
        for m in members:out.write(struct.pack('<3fI',*m.position,m.identity))
    count=0
    with (a.output/'reference.jsonl').open('w') as out:
        for identity,complete,ids in candidate_batches(members,members[:a.queries],a.radius,a.horizon,a.batch):
            count+=len(ids);out.write(json.dumps(dict(query=identity,complete=complete,candidates=ids))+'\n')
    meta=dict(capture_sha256=hashlib.sha256((a.capture/'manifest.json').read_bytes()).hexdigest(),members=len(members),queries=min(a.queries,len(members)),radius=a.radius,horizon=a.horizon,candidates=count,
              scope='Complete conservative radius/linear sweep oracle only. Exact attack/contact rules and multi-frame replay remain separate gates.',source_context=manifest['context'])
    (a.output/'replay.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta))


if __name__=='__main__':main()
