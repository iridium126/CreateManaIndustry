"""Gate complete production-model A/B results against an independent A/A capture."""
import argparse,csv,json,statistics as stats
from collections import defaultdict
from pathlib import Path

def percentile(values,p):
    v=sorted(values);return v[max(0,min(len(v)-1,int((len(v)-1)*p+.5)))]
def read(path):
    with path.open(encoding='utf8') as f:return list(csv.DictReader(f))
def key(r):return (r['pack'],int(r['width']),int(r['height']),int(r['workload']),int(r['scene']),int(r['count']))
def groups(rows):
    result=defaultdict(list)
    for r in rows:result[key(r)].append(r)
    return result
def metric(k,rows):
    rounds=[];absolute=[]
    for i in range(3):
        batch=[r for r in rows if int(r['round'])==i]
        rounds.append(stats.median(float(r['candidate_ms'])/float(r['baseline_ms'])-1 for r in batch))
        absolute.append(stats.median(float(r['candidate_ms'])-float(r['baseline_ms']) for r in batch))
    a=[float(r['baseline_ms']) for r in rows];b=[float(r['candidate_ms']) for r in rows]
    return dict(zip(('pack','width','height','workload','scene','count'),k),baseline_p50=stats.median(a),baseline_p95=percentile(a,.95),candidate_p50=stats.median(b),candidate_p95=percentile(b,.95),round_changes=rounds,round_changes_ms=absolute,paired_change=stats.median(rounds))
def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--aa',type=Path,required=True);parser.add_argument('--require-gate',action='store_true');args=parser.parse_args()
    data=groups(read(args.directory/'production.csv'));aa=groups(read(args.aa/'production.csv'))
    packs=sorted({k[0] for k in data});errors=[];noise={}
    for pack in packs:
        calibration=[metric(k,v) for k,v in aa.items() if k[0]==pack]
        if len(calibration)!=6:errors.append('Incomplete A/A: '+pack)
        if not calibration:continue
        noise[pack]={'relative':max(.005,max(abs(d) for m in calibration for d in m['round_changes'])),
                     'absolute_ms':.002,'observed_aa_absolute_ms':max(abs(d) for m in calibration for d in m['round_changes_ms'])}
    metrics=[];improvements=[];regressions=[]
    for k,rows in data.items():
        if {int(r['round']) for r in rows}!={0,1,2}:errors.append('Missing round: '+str(k));continue
        counts={i:len([r for r in rows if int(r['round'])==i]) for i in range(3)}
        if any(c!=16 for c in counts.values()):errors.append('Incomplete samples: '+str(k))
        m=metric(k,rows);metrics.append(m)
        if k[0] not in noise:continue
        n=noise[k[0]]
        if all(d>n['relative'] and t>n['absolute_ms'] for d,t in zip(m['round_changes'],m['round_changes_ms'])):regressions.append(m)
        if k[-1]>=50000 and all(d< -n['relative'] and t< -n['absolute_ms'] for d,t in zip(m['round_changes'],m['round_changes_ms'])):improvements.append(m)
    if len(packs)!=8:errors.append('Expected eight packs')
    expected={(p,w,h,m,s,c) for p in packs for w,h in [(1920,1080),(2560,1506)] for m in range(6) for s in range(3) for c in [0,1000,10000,50000,131072]}
    if set(data)!=expected:errors.append('Incomplete full input matrix')
    for row in read(args.directory/'startup.csv'):
        if row['baseline_programs']!=row['candidate_programs']:errors.append('Program count changed: '+row['pack'])
    passed=not errors and not regressions and any('photon' in m['pack'].lower() and m['scene']==1 for m in improvements)
    result={'passed':passed,'errors':errors,'noise':noise,'metrics':metrics,'improvements':improvements,'regressions':regressions}
    (args.directory/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
    lines=['# MODEL draw benchmark','',f"Whole-model replacement gate: {'PASS' if passed else 'NOT MET'}",'',
           'Relative noise is calibrated per pack using the largest absolute three-round A/A ratio median (minimum 0.5%). A change must also exceed 0.002 ms in every round. Absolute A/A drift is reported separately, rather than extrapolated from 50,000 models to small loads.','',
           '| Pack | Baseline p50/p95 ms | Candidate p50/p95 ms | Paired change | Three rounds |','|---|---:|---:|---:|---|']
    for m in metrics:
        if (m['width'],m['height'],m['workload'],m['scene'],m['count'])!=(2560,1506,5,1,50000):continue
        rounds=', '.join(f"{d*100:+.2f}%" for d in m['round_changes'])
        lines.append(f"| {m['pack']} | {m['baseline_p50']:.4f}/{m['baseline_p95']:.4f} | {m['candidate_p50']:.4f}/{m['candidate_p95']:.4f} | {m['paired_change']*100:+.2f}% | {rounds} |")
    lines+=['',f'Reproducible heavy-load improvements: {len(improvements)}. Stable regressions: {len(regressions)}.','',*errors]
    for m in regressions:lines.append('Regression: '+str(m))
    (args.directory/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
    print('PASS' if passed else 'NOT MET',len(metrics),'groups;',len(improvements),'improvements;',len(regressions),'regressions')
    if args.require_gate and not passed:raise SystemExit(1)
if __name__=='__main__':main()
