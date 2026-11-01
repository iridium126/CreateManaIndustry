"""Conservative three-process AA-noise gate. Scopes remain separate, no FPS conversion."""
import csv,json,statistics,math
from collections import defaultdict
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101'
def percentile(xs,q):
 xs=sorted(xs);i=(len(xs)-1)*q;return xs[int(i)]+(xs[min(int(i)+1,len(xs)-1)]-xs[int(i)])*(i-int(i))
def metrics(rows,key,labels):
 groups=defaultdict(lambda:defaultdict(dict))
 for r in rows:
  pair,side,value=int(r['pair']),int(r['side']),float(r[key])
  if not 0<=pair<64 or side not in (0,1) or not math.isfinite(value) or value<0 or side in groups[r['comparison']][pair]:raise ValueError('Invalid/duplicate paired sample')
  groups[r['comparison']][pair][side]=value
 if set(groups)!=set(labels):raise ValueError('Unexpected/missing comparisons')
 summary={}
 for label in labels:
  pairs=groups[label]
  if len(pairs)!=64 or any(set(x)!={0,1} for x in pairs.values()):raise ValueError('Incomplete 64 paired samples '+label)
  a=[x[0] for x in pairs.values()];b=[x[1] for x in pairs.values()];d=[x[0]-x[1] for x in pairs.values()]
  summary[label]=dict(base_p50=statistics.median(a),candidate_p50=statistics.median(b),base_p95=percentile(a,.95),candidate_p95=percentile(b,.95),paired_gain_ms=statistics.median(d),paired_gain_percent=100*statistics.median(d)/statistics.median(a),pair_abs_p95=percentile([abs(x) for x in d],.95))
 aa,ac,ab=(summary[x] for x in labels);noise=max(aa['pair_abs_p95'],ac['pair_abs_p95']);tail_noise=max(abs(aa['candidate_p95']-aa['base_p95']),abs(ac['candidate_p95']-ac['base_p95']))
 summary['gate']=dict(noise_ms=noise,p95_noise_ms=tail_noise,passes=ab['paired_gain_ms']>max(noise,.002,.005*ab['base_p50']) and ab['candidate_p95']-ab['base_p95']<=tail_noise)
 return summary
def main():
 result={'schema':1,'scope':'offline all-query geometry/shared handoff and separate synthetic complete physics; no client FPS acceptance','groups':{}}
 collected=defaultdict(list)
 for run in sorted([p for root in ('measurements-final-v2','combinations-final','notification-final') if (DATA/root).exists() for p in (DATA/root).iterdir()]):
  if not run.is_dir() or not (run.name.startswith('sequence-') or run.name.startswith('chain-')):continue
  # The initial motion round used the pre-fix CUDA far-coordinate fallback. Keep as failed provenance, replace with an immutable rerun.
  manifest=json.loads((run/('sequence.json' if run.name.startswith('sequence-') else 'continuation.json')).read_text())
  if not manifest['passed'] or any(manifest.get(k) for k in ('failures','reference_aa_differences','update_boundary_mismatches')):raise ValueError('Failed correctness '+run.name)
  paths=list(run.glob('samples*.csv'))
  for path in paths:
   with path.open() as stream:rows=list(csv.DictReader(stream))
   if not rows:continue
   is_sequence='candidate' in rows[0];labels=('AA_base','AA_candidate','AB') if is_sequence else ('AA_production','AA_native','AB_production_native')
   candidates=sorted({r['candidate'] for r in rows}) if is_sequence else ['chain']
   for candidate in candidates:
    data=[r for r in rows if not is_sequence or r['candidate']==candidate];name=run.name.rsplit('-r',1)[0].replace('-fixed','')+'/'+candidate+'/'+path.name
    if is_sequence:name=run.name.rsplit('-r',1)[0]+'/'+candidate
    entry=dict(process_id=manifest['process_id'],run=run.name,path=str(path.relative_to(DATA)),gpu=metrics(data,'gpu_ms',labels),cpu=metrics(data,'cpu_ms' if is_sequence else 'cpu_submit_ms',labels))
    if not is_sequence:entry['reference_fallbacks']=sum(int(r['reference_fallbacks']) for r in data);entry['native_results']=sum(int(r['native_results']) for r in data)
    if run.parent.name=='notification-final':name='notification/'+name
    collected[name].append(entry)
 for name,rounds in collected.items():result['groups'][name]=dict(rounds=rounds,three_process_gate=len(rounds)==3 and len({r['process_id'] for r in rounds})==3 and all(r['gpu']['gate']['passes'] and not r.get('reference_fallbacks',0) for r in rounds))
 (DATA/'measurement-summary.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'groups':len(result['groups']),'three_round_passes':[k for k,v in result['groups'].items() if v['three_process_gate']]}))
if __name__=='__main__':main()
