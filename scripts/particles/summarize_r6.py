"""Scope-preserving three-process R6 paired update screen; never infer client FPS."""
import argparse,csv,json
from pathlib import Path
import numpy as np

def summarize(root):
    data={}
    for file in root.glob('*/samples.csv'):
        variant,round_id=file.parent.name.rsplit('-',1)
        if not round_id.isdigit():continue
        rows=list(csv.DictReader(file.open()))
        full=bool(rows and 'baseline_ms' in rows[0])
        if full:
            expanded=[]
            for row in rows:
                for side,key in enumerate(('baseline_ms','candidate_ms')):expanded.append(dict(capture=f"n{row['count']}-m{row['mode']}-{row['scene']}",pair=row['pair'],side=str(side),gpu_ms=row[key]))
            rows=expanded
        for capture in sorted({r['capture'] for r in rows}):
            selected=[r for r in rows if r['capture']==capture];sides={}
            for row in selected:sides.setdefault(int(row['pair']),{})[int(row['side'])]=float(row['gpu_ms'])
            pairs=np.array([list(v[s] for s in (0,1)) for k,v in sorted(sides.items()) if set(v)=={0,1}])
            if len(pairs)<64:continue
            delta=pairs[:,0]-pairs[:,1]
            data[variant,int(round_id),capture]=dict(pairs=len(pairs),baseline_p50_ms=float(np.median(pairs[:,0])),candidate_p50_ms=float(np.median(pairs[:,1])),baseline_p95_ms=float(np.quantile(pairs[:,0],.95)),candidate_p95_ms=float(np.quantile(pairs[:,1],.95)),paired_gain_ms=float(np.median(delta)),paired_gain_percent=float(100*np.median(delta/pairs[:,0])),paired_abs_p95_ms=float(np.quantile(abs(delta),.95)))
    report=[]
    for (variant,round_id,capture),result in sorted(data.items()):
        aa=data.get(('AA',round_id,capture))
        gate=None if aa is None or variant=='AA' else result['paired_gain_ms']>max(.002,aa['paired_abs_p95_ms']) and result['paired_gain_percent']>.5 and result['candidate_p95_ms']<=result['baseline_p95_ms']+aa['paired_abs_p95_ms']
        report.append(dict(variant=variant,round=round_id,capture=capture,**result,aa_noise_p95_ms=aa['paired_abs_p95_ms'] if aa else None,performance_screen_gate=gate))
    synthetic=any(r['capture'].startswith('n1000-') for r in report)
    output=dict(scope='synthetic full chain including candidate preparation; not real captured full chain, multi-frame physical or client FPS qualification' if synthetic else 'real captures: GENERIC+MODEL dispatch and candidate preparation ONLY; not full chain, physical or client FPS qualification',noise='matching A/A 95th percentile absolute paired delta (conservative screen)',results=report)
    (root/'summary.json').write_text(json.dumps(output,indent=2)+'\n')
    lines=['# R6 scoped performance screen','',output['scope'],'','|variant|input/scenario|rounds|paired gain % (each round)|gate all rounds|','|---|---|---|---|---|']
    for variant in sorted({r['variant'] for r in report}):
        for capture in sorted({r['capture'] for r in report}):
            group=[r for r in report if r['variant']==variant and r['capture']==capture]
            lines.append('|'+variant+'|'+(capture if synthetic else capture.split('-')[0])+'|'+str(len(group))+'|'+', '.join(f"{r['paired_gain_percent']:.2f}" for r in group)+'|'+str(len(group)==3 and all(r['performance_screen_gate'] is True for r in group))+'|')
    (root/'summary.md').write_text('\n'.join(lines)+'\n')
    return output

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();result=summarize(a.root);print('Summarized',len(result['results']),'input/process results:',result['scope'])
