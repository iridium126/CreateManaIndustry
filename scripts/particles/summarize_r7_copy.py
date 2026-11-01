"""COPY/raster probe results; API scheduling evidence, never a client FPS claim."""
import csv,json,statistics,math,argparse
from collections import defaultdict
from pathlib import Path

def p95(v):return sorted(v)[math.ceil(len(v)*.95)-1]
def main(root,output):
    result=dict(scope='COPY of a 25,234,080-byte frozen particle state plus four independent 2560x1506 raster draws; not the production state-copy ranges or shaderpack terrain',modes={'0':'GL copy','1':'D3D12 COPY with immediate GL GPU wait','2':'D3D12 COPY with wait at first dependent consumer'},processes={},client_fps_claim=False,physical_engine_utilization='unavailable: explicit COPY API queue, no hardware counter/time-line classification')
    for round in range(1,4):
        directory=root/f'copy-concurrency-r{round}';meta=json.loads((directory/'metadata.json').read_text())
        if not meta['passed']:raise ValueError('COPY correctness failure')
        pairs=defaultdict(dict)
        for r in csv.DictReader((directory/'samples.csv').open()):pairs[r['comparison']][int(r['pair']),int(r['side'])]=r
        rows={}
        for label,groups in pairs.items():
            if len(groups)!=128:raise ValueError('incomplete COPY pairs')
            a=[float(groups[i,0]['gl_interval_ms']) for i in range(64)];b=[float(groups[i,1]['gl_interval_ms']) for i in range(64)]
            rows[label]=dict(a_gpu_ms=statistics.median(a),b_gpu_ms=statistics.median(b),paired_gain_ms=statistics.median(x-y for x,y in zip(a,b)),aa_abs_p95_ms=p95([abs(x-y) for x,y in zip(a,b)]),a_p95_ms=p95(a),b_p95_ms=p95(b))
        for label,row in rows.items():
            if label.startswith('AB_'):
                _,a,b=label.split('_');noise=max(rows[f'AA_{i}_{i}']['aa_abs_p95_ms'] for i in (a,b));row['matching_aa_noise_ms']=noise;row['gain_above_probe_noise_and_floors']=row['paired_gain_ms']>max(noise,.002,row['a_gpu_ms']*.005)
        result['processes'][f'r{round}']=dict(process_id=meta['process_id'],bridge=meta['bridge'],comparisons=rows)
    if output.exists():raise ValueError('do not overwrite results')
    output.write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('output',type=Path);a=p.parse_args();main(a.root,a.output)
