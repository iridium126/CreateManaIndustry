"""Freeze client evidence and join per-frame GPU ranges without summing parents."""
import argparse,csv,hashlib,json,math,shutil,statistics,subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
IDS=('a21c891c-c460-4c24-ad96-6656bfa5dad6','675c0ad7-8b69-4ef9-a4d4-cbd016b99a8a','bc4728fd-d4c3-4951-b468-12504b857971','4424b8ae-cd80-4d1c-8dfa-936cc1562610','9f2e4fa9-3422-4b2c-b713-514c18d6fd59')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def rows(p):
    with p.open(newline='') as f:return list(csv.DictReader(f))
def main():
    a=argparse.ArgumentParser();a.add_argument('--output',type=Path,default=ROOT/'build/benchmarks/rt-avoidance-20261103/baseline');out=a.parse_args().output;out.mkdir(parents=True,exist_ok=False)
    files={}
    def copy(p,relative):
        dst=out/relative;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst);files[str(relative).replace('\\','/')]=sha(p)
    for name in subprocess.check_output(['git','ls-files'],cwd=ROOT,text=True).splitlines():
        p=ROOT/name
        if p.is_file() and (name.startswith(('native/offload/','scripts/particles/','src/main/','src/test/'))):copy(p,Path('source')/name)
    copy(ROOT/'.gradle/hardware-offload/build/CmiHardwareOffload.dll',Path('native/CmiHardwareOffload.dll'))
    for name in ('latest.log','debug.log'):copy(ROOT/'run/logs'/name,Path('client')/name)
    summaries=[]
    for id in IDS:
        prefix=ROOT/'run/logs/cmi-particle-frames'/('frames-'+id)
        for p in prefix.parent.glob(prefix.name+'*'):copy(p,Path('client/frames')/p.name)
        m=json.loads(prefix.with_suffix('.json').read_text());joined={int(x['scene_frame']):dict(x) for x in rows(prefix.with_suffix('.csv'))}
        for r in rows(prefix.with_name(prefix.name+'-stages.csv')):
            if r['stage'] in ('update.dome_contacts','scene.compute.continuation','particle.hardware.separation.handoff'):
                j=joined.get(int(r['scene_frame']))
                if j is not None:j[r['stage']+'.gpu_ms']=r['gpu_ms'];j[r['stage']+'.cpu_ms']=r['cpu_submit_ms']
        hardware=rows(prefix.with_name(prefix.name+'-hardware.csv'))
        for r in hardware:
            j=joined.get(int(r['scene_frame']))
            if j is not None:j.update({k:r[k] for k in ('consumed','reason','queued_input_validated_ms','queued_output_ms')})
        dest=out/'client'/('joined-'+id+'.csv');keys=sorted(set().union(*(x.keys() for x in joined.values())))
        with dest.open('w',newline='') as f:w=csv.DictWriter(f,keys);w.writeheader();w.writerows(joined.values())
        groups={}
        for name,pred in (('dome_assemble',lambda r:'dome/assemble' in r['attack_contract_stage']),('no_dome',lambda r:'dome/' not in r['attack_contract_stage'])):
            selected=[r for r in joined.values() if pred(r)];values=[float(r['interval_ms']) for r in selected];contact=[float(r['update.dome_contacts.gpu_ms']) for r in selected if 'update.dome_contacts.gpu_ms' in r and math.isfinite(float(r['update.dome_contacts.gpu_ms']))]
            groups[name]=dict(frames=len(values),interval_p50=statistics.median(values) if values else None,contact_p50=statistics.median(contact) if contact else None)
        summaries.append(dict(id=id,backend=m['backend'],process_id=m['process_id'],average_fps=m['average_fps'],frames=len(joined),measured_seconds=m['measured_seconds'],timeouts=sum(r.get('reason','').startswith('native query not ready') for r in hardware),groups=groups))
    manifest=dict(files=files,index_sha256=hashlib.sha256(subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)).hexdigest(),windows=summaries,qualification='one client process; stage distributions differ; diagnostic evidence, not FPS admission')
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(summaries,indent=2))
if __name__=='__main__':main()
