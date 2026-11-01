"""Reduce R7 pairs within each identical 64-frame sequence; never convert to FPS."""
import argparse,csv,json,statistics,math
from collections import defaultdict
from pathlib import Path

def percentile(values,p):
    a=sorted(values)
    return a[max(0,math.ceil(len(a)*p)-1)]

def summarize(root):
    records={}
    for directory in sorted(Path(root).glob('*-r*')):
        if not (directory/'metadata.json').exists():continue
        meta=json.loads((directory/'metadata.json').read_text())
        if not meta['passed'] or meta['samples']<64:raise ValueError('incomplete/non-formal process: '+str(directory))
        pairs=defaultdict(lambda:defaultdict(list))
        for row in csv.DictReader((directory/'samples.csv').open()):pairs[row['comparison']][int(row['pair']),int(row['side'])].append(row)
        values={}
        for label,groups in pairs.items():
            sample=[]
            for pair in range(meta['samples']):
                a,b=groups[pair,0],groups[pair,1]
                if len(a)!=64 or len(b)!=64 or {int(r['frame']) for r in a}!={*range(64)} or {int(r['frame']) for r in b}!={*range(64)}:raise ValueError('incomplete pair')
                mean=lambda rows,key:sum(float(r[key]) for r in rows)/64
                sample.append(dict(a=mean(a,'gpu_ms'),b=mean(b,'gpu_ms'),wall_a=mean(a,'completed_wall_ms'),wall_b=mean(b,'completed_wall_ms')))
            values[label]=dict(a_path=groups[0,0][0]['path'],b_path=groups[0,1][0]['path'],a_gpu_ms=statistics.median(s['a'] for s in sample),b_gpu_ms=statistics.median(s['b'] for s in sample),paired_gain_ms=statistics.median(s['a']-s['b'] for s in sample),aa_abs_p95_ms=percentile([abs(s['a']-s['b']) for s in sample],.95),a_wall_ms=statistics.median(s['wall_a'] for s in sample),b_wall_ms=statistics.median(s['wall_b'] for s in sample),stage_p50_ms={side:{key:statistics.median(float(r[key]) for (p,s),rows in groups.items() if s==side for r in rows) for key in ('prepare_ms','bvh_ms','count_ms','scan_ms','write_ms')} for side in (0,1)})
        for label,row in values.items():
            groups=pairs[label]
            for side in (0,1):
                frame_values=[float(r['gpu_ms']) for (pair,s),rows in groups.items() if s==side for r in rows]
                row[('a' if side==0 else 'b')+'_frame_gpu_p95_ms']=percentile(frame_values,.95)
                row[('a' if side==0 else 'b')+'_frame_gpu_p99_ms']=percentile(frame_values,.99)
            if not label.startswith('AB_'):continue
            noise=max(values['AA_'+p+'__'+p]['aa_abs_p95_ms'] for p in (row['a_path'],row['b_path']))
            row['matching_aa_noise_ms']=noise;row['local_gain_above_floor_and_noise']=row['paired_gain_ms']>max(noise,.002,.005*row['a_gpu_ms'])
        records[directory.name]=dict(process_id=meta['process_id'],query_scope=meta['scope'],points=meta['points_per_frame'],queries=meta['queries_per_frame'],comparisons=values)
    report=dict(scope='per-frame mean of a fixed 64-frame read-pool geometric sequence, 128 queried identities; NOT full chain, concurrency or client FPS',tail_scope='Raw per-frame GPU p95/p99 are also reported; no complete-chain or other-load tail gate is claimed.',completed_wall_scope='Native completed wall includes map/unmap ownership synchronization and can include outstanding fixture GL uploads; GPU event time starts after map. Fixture CPU upload call itself is outside both. The original metadata upload wording is not a guarantee that residual upload waits were excluded.',processes=records,production_replacement=False,client_backend_qualification=False)
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    if args.output.exists():raise ValueError('do not overwrite analysis')
    args.output.write_text(json.dumps(summarize(args.root),indent=2)+'\n')
