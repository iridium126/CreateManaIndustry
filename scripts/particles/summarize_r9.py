"""Whole captured update-chain gate; media round trips are reported separately."""
import argparse,csv,json,statistics
from pathlib import Path
from summarize_r8 import metrics,percentile

LABELS=('AA_production','AA_native','AB_production_native')

def evaluate(rows):
    if any(int(r['reference_fallbacks']) for r in rows):raise ValueError('Fallbacks invalidate comparison')
    result=metrics(rows,'gpu_ms',LABELS)
    aa,ac,ab=(result[x] for x in LABELS)
    tails={}
    for label in LABELS:
        sides=[[float(r['gpu_ms']) for r in rows if r['comparison']==label and int(r['side'])==side] for side in (0,1)]
        tails[label]=[percentile(side,.99) for side in sides]
    noise=max(aa['pair_abs_p95'],ac['pair_abs_p95'])
    p95_noise=max(abs(aa['candidate_p95']-aa['base_p95']),abs(ac['candidate_p95']-ac['base_p95']))
    p99_noise=max(abs(a-b) for a,b in (tails[LABELS[0]],tails[LABELS[1]]))
    result['p99']=tails
    result['gate']=dict(noise_ms=noise,minimum_gain_ms=max(.1,.01*ab['base_p50'],noise),passes=ab['paired_gain_ms']>max(.1,.01*ab['base_p50'],noise) and ab['candidate_p95']<=ab['base_p95']+p95_noise and tails[LABELS[2]][1]<=tails[LABELS[2]][0]+p99_noise)
    return result

def summarize(root,measurement='measure-final'):
    jobs=json.loads((root/measurement/'jobs.json').read_text());
    if len(jobs)!=3 or any(j['exit_code'] or j['before']!=j['after'] for j in jobs) or any(j['before']!=jobs[0]['before'] for j in jobs):raise ValueError('Three frozen process rounds required')
    result={'scope':'captured complete update commands including hardware handoff; excludes fixture reset, world rendering and client FPS','policies':{}}
    for policy in (83,211,339):
        rounds=[]
        for i in (1,2,3):
            path=root/measurement/('actual-r'+str(i));meta=json.loads((path/'real-chain.json').read_text())
            if not meta['passed'] or not meta['actual_capture'] or not meta['measured']:raise ValueError('Full chain qualification missing')
            with (path/('samples-policy-'+str(policy)+'.csv')).open() as f:rows=list(csv.DictReader(f))
            rounds.append(dict(process_id=meta['process_id'],gpu=evaluate(rows),cpu=metrics(rows,'cpu_submit_ms',LABELS)))
        result['policies'][str(policy)]=dict(rounds=rounds,passes=len({r['process_id'] for r in rounds})==3 and all(r['gpu']['gate']['passes'] for r in rounds))
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('root',type=Path,nargs='?',default=Path('build/benchmarks/hardware-r9-20261103'));args=p.parse_args();result=summarize(args.root);(args.root/'measurement-summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':main()
