"""Full-chain synthetic AA/AB results. Slower candidates can still enter client
experiments after correctness; this tool never turns GPU timings into FPS."""
import argparse,csv,json,math,statistics
from collections import defaultdict
from pathlib import Path
def percentile(values,q):
    values=sorted(values);at=(len(values)-1)*q;lo=int(at);return values[lo]+(values[min(lo+1,len(values)-1)]-values[lo])*(at-lo)
def summarize(root):
    root=Path(root);groups=defaultdict(list);processes=set()
    for directory in sorted(root.glob('r*-*')):
        if not directory.is_dir():continue
        m=json.loads((directory/'continuation.json').read_text())
        if not m['passed'] or m['reference_aa_differences'] or m['update_boundary_mismatches'] or not m['measured']:raise ValueError('invalid measurement '+str(directory))
        processes.add(m['process_id'])
        for file in directory.glob('samples-*.csv'):
            rows=list(csv.DictReader(file.open()));cells=defaultdict(dict)
            for r in rows:
                key=(int(r['pair']),int(r['side']));g=cells[r['comparison']]
                if key in g or not 0<=key[0]<64 or key[1] not in (0,1):raise ValueError('duplicate/out-of-range paired sample')
                if not all(math.isfinite(float(r[x])) and float(r[x])>=0 for x in ('gpu_ms','cpu_submit_ms')):raise ValueError('invalid time')
                g[key]=r
            if set(cells)!={'AA_production','AA_native','AB_production_native'} or any(len(g)!=128 for g in cells.values()):raise ValueError('missing 64-pair comparison')
            for metric in ('gpu_ms','cpu_submit_ms'):
                def side(c,s):return [float(cells[c][i,s][metric]) for i in range(64)]
                aa=[abs(float(g[i,0][metric])-float(g[i,1][metric])) for g in (cells['AA_production'],cells['AA_native']) for i in range(64)]
                a,b=side('AB_production_native',0),side('AB_production_native',1);base=statistics.median(a);candidate=statistics.median(b);gain=base-candidate;noise=percentile(aa,.95);tail_noise=max(abs(percentile(side(c,0),.95)-percentile(side(c,1),.95)) for c in ('AA_production','AA_native'))
                native=[r for r in rows if r['path']!='production'];result=dict(round=directory.name.split('-')[0],process_id=m['process_id'],metric=metric,baseline_p50=base,candidate_p50=candidate,gain_ms=gain,gain_percent=100*gain/base if base else 0,aa_pair_abs_p95_ms=noise,baseline_p95=percentile(a,.95),candidate_p95=percentile(b,.95),baseline_p99=percentile(a,.99),candidate_p99=percentile(b,.99),native_results=sum(int(r.get('native_results',0)) for r in native),whole_reference_fallbacks=sum(int(r.get('reference_fallbacks',0)) for r in native),gate=gain>max(noise,base*.005,.002) and percentile(b,.95)-percentile(a,.95)<=tail_noise)
                groups[(m['backend'],file.stem.removeprefix('samples-'),metric)].append(result)
    if len(processes)!=15:raise ValueError('expected three independent processes per each of five query routes')
    results=[]
    for (backend,cell,metric),rounds in sorted(groups.items()):
        if {r['round'] for r in rounds}!={'r1','r2','r3'}:raise ValueError('missing process round')
        results.append(dict(backend=backend,cell=cell,metric=metric,three_round_gain_gate=all(r['gate'] for r in rounds),rounds=rounds))
    out=dict(scope='complete synthetic update chain; paired-islands input; all prepare, native, wait, fallback and consume work included; no terrain/render competition or FPS inference',client_admission='correctness and drawing only; this timing gate is descriptive',results=results)
    (root/'summary.json').write_text(json.dumps(out,indent=2)+'\n');return out
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();r=summarize(a.root);print(json.dumps(dict(cells=len(r['results']),gpu_three_round_gains=sum(x['three_round_gain_gate'] for x in r['results'] if x['metric']=='gpu_ms'))))
