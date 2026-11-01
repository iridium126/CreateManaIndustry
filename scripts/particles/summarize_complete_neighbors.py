"""Full-chain cost screen. Preliminary invariants never grant a physics/visual replacement gate."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
from collections import defaultdict


def load(directory):
    cells=defaultdict(list)
    with (directory/'samples.csv').open(newline='') as f:
        for r in csv.DictReader(f):cells[(int(r['count']),r['scene'])].append((float(r['baseline_ms']),float(r['candidate_ms'])))
    return cells


def main():
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--prefix',default='complete');a=p.parse_args();root=a.directory
    aa=[load(root/f'{a.prefix}-aa-{i}') for i in range(1,4)];ab=[load(root/f'{a.prefix}-ab-{i}') for i in range(1,4)]
    cells=[]
    def tail(v):return sorted(v)[math.ceil(len(v)*.95)-1]
    for key in sorted(ab[0]):
        rounds=[]
        for r in range(3):
            control=aa[r].get(key,[]);trial=ab[r].get(key,[])
            complete=len(control)>=64 and len(trial)>=64
            if not complete:rounds.append(dict(passed=False,reason='missing samples'));continue
            base=statistics.median(x[0] for x in trial);candidate=statistics.median(x[1] for x in trial)
            gain=statistics.median(x-y for x,y in trial);noise=abs(statistics.median(x-y for x,y in control));threshold=max(.002,base*.005,noise)
            tail_noise=abs(tail([x[0] for x in control])-tail([x[1] for x in control]))
            rounds.append(dict(baseline_p50=base,reference_p50=candidate,gain_ms=gain,aa_noise_ms=noise,threshold_ms=threshold,
                               baseline_p95=tail([x[0] for x in trial]),reference_p95=tail([x[1] for x in trial]),passed=gain>threshold and base-candidate>threshold and tail([x[1] for x in trial])-tail([x[0] for x in trial])<=tail_noise))
        cells.append(dict(count=key[0],scene=key[1],rounds=rounds,cost_screen_pass=all(r['passed'] for r in rounds)))
    result=dict(production_replacement=False,physics_visual_qualification='not passed; preliminary one-frame invariants only',
                cost_screen_pass=bool(cells) and all(c['cost_screen_pass'] for c in cells),cells=cells,
                raw_sources={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob('complete-*/samples.csv')},
                production_aa_differences={str(p.relative_to(root)):p.read_text().splitlines() for p in root.glob('complete-*/production-aa-differences.txt')})
    (root/(a.prefix+'-summary.json')).write_text(json.dumps(result,indent=2)+'\n')
    print('Cost screen pass:',result['cost_screen_pass'],'; production replacement: false')


if __name__=='__main__':main()
