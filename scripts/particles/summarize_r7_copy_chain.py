"""Complete COPY-chain results; not a client FPS or physical-equivalence gate."""
import argparse,csv,json,statistics
from pathlib import Path
from summarize_r7_chain import percentile

def summarize(root):
    root=Path(root);rounds=[];processes=set();scenes=None
    for index in (1,2,3):
        directory=root/f'copy-chain-r{index}'
        meta=json.loads((directory/'metadata.json').read_text())
        if not meta['passed'] or meta['samples']<64 or meta['process_id'] in processes:raise ValueError('invalid independent process')
        processes.add(meta['process_id']);rows=list(csv.DictReader((directory/'samples.csv').open()))
        current={r['scene'] for r in rows}
        if scenes is None:scenes=current
        if current!=scenes or not current:raise ValueError('missing scene')
        for scene in sorted(scenes):
            groups={}
            for row in rows:
                if row['scene']!=scene:continue
                group=groups.setdefault(row['comparison'],{});key=int(row['pair']),int(row['side'])
                if key in group or not 0<=key[0]<64 or key[1] not in (0,1):raise ValueError('invalid paired sample')
                group[key]=float(row['gpu_ms'])
            if set(groups)!={'AA_production','AA_copy','AB_production_copy'} or any(len(g)!=128 for g in groups.values()):raise ValueError('incomplete comparison')
            def side(comparison,side):return [groups[comparison][pair,side] for pair in range(64)]
            a,b=side('AB_production_copy',0),side('AB_production_copy',1)
            aa=[abs(x-y) for c in ('AA_production','AA_copy') for x,y in zip(side(c,0),side(c,1))]
            noise=percentile(aa,.95);base=statistics.median(a);candidate=statistics.median(b);gain=base-candidate;threshold=max(noise,.002,.005*base)
            tail_noise=max(abs(percentile(side(c,0),.95)-percentile(side(c,1),.95)) for c in ('AA_production','AA_copy'))
            rounds.append(dict(round=index,process_id=meta['process_id'],scene=scene,baseline_p50=base,candidate_p50=candidate,gain_ms=gain,noise_p95_ms=noise,threshold_ms=threshold,baseline_p95=percentile(a,.95),candidate_p95=percentile(b,.95),gain_gate=gain>threshold and percentile(b,.95)-percentile(a,.95)<=tail_noise))
    result=dict(scope='complete synthetic simulation including all COPY staging/wait/consume costs; original production AA order differences retained separately; no real terrain competition or FPS',rounds=rounds,three_round_gain={scene:all(r['gain_gate'] for r in rounds if r['scene']==scene) for scene in sorted(scenes)})
    (root/'copy-chain-summary.json').write_text(json.dumps(result,indent=2)+'\n');return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();print(json.dumps(summarize(a.root)['three_round_gain']))
