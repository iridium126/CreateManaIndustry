"""Screen isolated query costs. This tool cannot grant a production/FPS replacement gate."""
import argparse
import csv
import hashlib
import json
import statistics
from collections import defaultdict
from pathlib import Path


def percentile(values, p):
    return sorted(values)[max(0, __import__('math').ceil(len(values)*p)-1)]


def evaluate(paths):
    runs=[]
    for path in paths:
        cells=defaultdict(dict)
        with path.open(newline='') as stream:
            for row in csv.DictReader(stream):
                key=(int(row['n']),row['layout'],row['comparison'])
                cells[key].setdefault(int(row['pair']),{})[int(row['side'])]=row
        runs.append(cells)
    output=[]
    if not runs:
        return dict(isolated_query_screen_pass=False,production_replacement=False,reason='no samples',cells=[])
    for n,layout,comparison in sorted(runs[0]):
        if not comparison.startswith('AB_'):
            continue
        labels={'AB_GL_CUDA':('AA_GL','AA_CUDA'),'AB_CUDA_RT_BUILD':('AA_CUDA','AA_RT_BUILD'),'AB_CUDA_RT_UPDATE':('AA_CUDA','AA_RT_UPDATE'),'AB_GL_RT_UPDATE':('AA_GL','AA_RT_UPDATE')}[comparison]
        summaries=[]
        for run in runs:
            cell=run.get((n,layout,comparison),{})
            complete=len(cell)>=64 and all(set(pair)=={0,1} for pair in cell.values())
            item=dict(complete=complete)
            for metric in ('gpu_ms','completed_wall_ms'):
                def measurements(name):
                    values=run.get((n,layout,name),{})
                    return [[float(pair[side][metric]) for _,pair in sorted(values.items()) if set(pair)=={0,1}] for side in (0,1)]
                a,b=measurements(comparison)
                if not a:
                    item[metric]=dict(passed=False,reason='missing paired samples');continue
                noise=0
                tail_noise=0
                for label in labels:
                    aa,ab=measurements(label)
                    if len(aa)<64:
                        noise=float('inf');break
                    noise=max(noise,abs(statistics.median([x-y for x,y in zip(aa,ab)])))
                    tail_noise=max(tail_noise,abs(percentile(aa,.95)-percentile(ab,.95)))
                median_a=statistics.median(a);gain=statistics.median([x-y for x,y in zip(a,b)])
                threshold=max(noise,median_a*.005,.002)
                item[metric]=dict(baseline_p50=median_a,candidate_p50=statistics.median(b),paired_gain_ms=gain,aa_noise_ms=noise,threshold_ms=threshold,
                                  baseline_p95=percentile(a,.95),candidate_p95=percentile(b,.95),passed=complete and gain>threshold and median_a-statistics.median(b)>threshold and percentile(b,.95)-percentile(a,.95)<=tail_noise)
            summaries.append(item)
        output.append(dict(n=n,layout=layout,comparison=comparison,rounds=summaries,
                           query_screen_pass=len(runs)>=3 and all(r['complete'] and r['gpu_ms']['passed'] and r['completed_wall_ms']['passed'] for r in summaries)))
    return dict(independent_processes=len(runs),production_replacement=False,client_fps_improvement='unmeasured',
                reason='Query-only experiments exclude continuation, full physics, real shaderpack drawing and client frame competition.',
                sources=[dict(path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in paths],cells=output)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('samples',nargs='+',type=Path);parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();args.out.write_text(json.dumps(evaluate(args.samples),indent=2)+'\n')
