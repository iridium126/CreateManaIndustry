"""Gate covered R2 draw measurements using a matching full-matrix A/A capture.
No old measurements are replaced or filtered. Each input cell retains all rounds.
"""
import argparse,json
from pathlib import Path
import summarize_model_draw as common

def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--aa',type=Path,required=True);parser.add_argument('--require-gate',action='store_true');parser.add_argument('--aa-extra',type=Path);parser.add_argument('--load-aa-extra',type=Path);parser.add_argument('--repeat',type=Path);args=parser.parse_args()
    data=common.groups(common.read(args.directory/'production.csv'));aa=common.groups(common.read(args.aa/'production.csv'))
    extra=common.groups(common.read(args.aa_extra/'production.csv')) if args.aa_extra else {}
    repeats=common.groups(common.read(args.repeat/'production.csv')) if args.repeat else {}
    initial_regressions=[];followups=[];initial_load_regressions=[];repeat_regressions=[]
    errors=[];packs=sorted({k[0] for k in data});metrics=[];improvements=[];regressions=[];loads=[];source_equal={}
    expected={(p,w,h,m,s,c) for p in packs for w,h in [(1920,1080),(2560,1506)] for m in range(6) for s in range(3) for c in [0,1000,10000,50000,131072]}
    if len(packs)!=8 or set(data)!=expected or set(aa)!=expected:errors.append('Incomplete eight-pack input matrix or matching A/A matrix')
    for directory in [args.directory,args.aa]+([args.aa_extra] if args.aa_extra else [])+([args.repeat] if args.repeat else []):
        cov=common.read(directory/'coverage.csv')
        subset={k for k in expected if k[0] in {r['pack'] for r in cov}}
        if {common.key(r) for r in cov}!=subset or len(cov)!=len(subset):errors.append('Incomplete coverage queries: '+str(directory))
        for r in cov:
            correct=str(int(r['count'])>0 and int(r['scene'])!=2).lower()
            if r['baseline_covered']!=correct or r['candidate_covered']!=correct:errors.append('Unexpected coverage: '+str(r))
        for rows in common.groups(common.read(directory/'production.csv')).values():
            if any(sum(int(r['round'])==i for r in rows)!=16 for i in range(3)):errors.append('Incomplete timed pairs: '+str(directory))
    for k,rows in data.items():
        if k not in aa:continue
        m=common.metric(k,rows);a=common.metric(k,aa[k]);initial_nr=max(.005,max(abs(x) for x in a['round_changes']));initial_na=max(.002,max(abs(x) for x in a['round_changes_ms']))
        if all(x>initial_nr and y>initial_na for x,y in zip(m['round_changes'],m['round_changes_ms'])):initial_regressions.append(dict(m))
        calibration=[a]+([common.metric(k,extra[k])] if k in extra else [])
        nr=max(.005,max(abs(x) for c in calibration for x in c['round_changes']));na=max(.002,max(abs(x) for c in calibration for x in c['round_changes_ms']))
        if k in repeats:
            repeat=common.metric(k,repeats[k]);repeat.update(noise_relative=nr,noise_ms=na);followups.append(repeat)
            if all(x>nr and y>na for x,y in zip(repeat['round_changes'],repeat['round_changes_ms'])):repeat_regressions.append(repeat)
        m.update(noise_relative=nr,noise_ms=na);metrics.append(m)
        if all(x>nr and y>na for x,y in zip(m['round_changes'],m['round_changes_ms'])):regressions.append(m)
        if k[-1]>=50000 and all(x< -nr and y< -na for x,y in zip(m['round_changes'],m['round_changes_ms'])):improvements.append(m)
    options=[json.loads(p.read_text()) for p in args.directory.glob('options-*.json')]
    for option in options:
        pack=option['pack'];digest=next(p.stem[8:] for p in args.directory.glob('options-*.json') if json.loads(p.read_text())['pack']==pack)
        root=args.directory/digest
        source_equal[pack]=all((root/f'baseline-{i}-{stage}.glsl').read_bytes()==(root/f'candidate-{i}-{stage}.glsl').read_bytes() for i in range(2) for stage in ['VERTEX','FRAGMENT'])
        rows=common.read(args.directory/f'load-{digest}.csv');cal=common.read(args.aa/f'load-{digest}.csv')
        original_cal=list(cal)
        if args.load_aa_extra and (args.load_aa_extra/f'load-{digest}.csv').exists():cal+=common.read(args.load_aa_extra/f'load-{digest}.csv')
        noise=max(.005,max(abs(float(r['candidate_ms'])/float(r['baseline_ms'])-1) for r in cal));absolute=max(2.,max(abs(float(r['candidate_ms'])-float(r['baseline_ms'])) for r in cal))
        delta=[float(r['candidate_ms'])/float(r['baseline_ms'])-1 for r in rows];diff=[float(r['candidate_ms'])-float(r['baseline_ms']) for r in rows]
        load=dict(pack=pack,relative_noise=noise,absolute_noise_ms=absolute,changes=delta,changes_ms=diff,stable_regression=all(x>noise and y>absolute for x,y in zip(delta,diff)))
        loads.append(load)
        original_noise=max(.005,max(abs(float(r['candidate_ms'])/float(r['baseline_ms'])-1) for r in original_cal));original_absolute=max(2.,max(abs(float(r['candidate_ms'])-float(r['baseline_ms'])) for r in original_cal))
        if all(x>original_noise and y>original_absolute for x,y in zip(delta,diff)):initial_load_regressions.append(dict(load,relative_noise=original_noise,absolute_noise_ms=original_absolute))
    for r in common.read(args.directory/'startup.csv'):
        if r['baseline_programs']!=r['candidate_programs']:errors.append('Program count changed: '+r['pack'])
    passed=not errors and not regressions and not repeat_regressions and not any(r['stable_regression'] for r in loads) and any('complementary' in m['pack'].lower() and m['scene']==1 and m['workload']==5 for m in improvements)
    result=dict(passed=passed,errors=errors,metrics=metrics,improvements=improvements,regressions=regressions,loads=loads,source_identical=source_equal,options=options,initial_regressions=initial_regressions,initial_load_regressions=initial_load_regressions,repeat_regressions=repeat_regressions,followups=followups,additional_aa=str(args.aa_extra),additional_load_aa=str(args.load_aa_extra),repeat_directory=str(args.repeat))
    (args.directory/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
    lines=['# Covered R2 MODEL draw benchmark','',f"Replacement gate: {'PASS' if passed else 'NOT MET'}",'',
      'All original cells and three rounds are retained. Each cell uses matching A/A noise: maximum absolute round ratio median (floor 0.5%) and maximum absolute round paired difference median (floor 0.002 ms). A direction must exceed both thresholds in every round. Warm loading uses per-pack A/A deviations with a 2 ms absolute floor. Cached first compile records are reported separately and are not cold-cache comparisons.','',
      '| Pack | Baseline p50/p95 ms | Candidate p50/p95 ms | Three paired rounds |','|---|---:|---:|---|']
    for m in metrics:
        if (m['width'],m['height'],m['workload'],m['scene'],m['count'])==(2560,1506,5,1,50000):
            rounds=', '.join(f'{v*100:+.2f}%' for v in m['round_changes']);lines.append(f"| {m['pack']} | {m['baseline_p50']:.4f}/{m['baseline_p95']:.4f} | {m['candidate_p50']:.4f}/{m['candidate_p95']:.4f} | {rounds} |")
    lines+=['',f'Groups: {len(metrics)}; heavy improvements: {len(improvements)}; stable draw regressions: {len(regressions)}; stable warm-load regressions: {sum(r["stable_regression"] for r in loads)}.','',*errors]
    lines+=['',f'Initial flags before expanded A/A: {len(initial_regressions)}. Additional same-source calibration and targeted repeats are retained in summary.json.']
    for m in regressions:lines.append('Draw regression: '+str(m))
    for m in loads:
        if m['stable_regression']:lines.append('Warm-load regression: '+str(m))
    (args.directory/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf8');print(lines[2]);print(lines[-1])
    if args.require_gate and not passed:raise SystemExit(1)
if __name__=='__main__':main()
