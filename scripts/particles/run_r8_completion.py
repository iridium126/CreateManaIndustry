"""Remaining independent regressions. Run serially after regular timing batch."""
import json,subprocess,shutil,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';OUT=DATA/'completion-final';OUT.mkdir(exist_ok=False);jobs=[]
old=ROOT/'build/benchmarks/neighbor-r7/baseline';prior=json.loads((old/'manifest.json').read_text());target=DATA/'baseline/historical-updates';target.mkdir(exist_ok=False);entries={}
for name,desc in prior['files'].items():
 if name.startswith('client/cmi-neighbor-inputs/'):
  source=old/name;digest=hashlib.sha256(source.read_bytes()).hexdigest()
  if digest!=desc['sha256']:raise ValueError('Historical frozen input drift '+name)
  dest=target/Path(name).relative_to('client/cmi-neighbor-inputs');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest);entries[dest.relative_to(DATA).as_posix()]=desc
(DATA/'baseline/historical-updates-manifest.json').write_text(json.dumps(dict(source_manifest_sha256=hashlib.sha256((old/'manifest.json').read_bytes()).hexdigest(),scope='copy of already immutable R7 historical input baseline, verified against its prior SHA-256',files=entries),indent=2)+'\n')
def run(name,task,**opts):
 args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in opts.items()]
 with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 jobs.append(dict(name=name,exit_code=r.returncode,task=task,options=opts));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2,default=str)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
run('semantic','validateR8SemanticGpu')
run('real-notify','validateR7RealContinuationGpu',r7Captures=target,r7ClientBindings='true',r8Policy=3)
run('real-paged','validateR7RealContinuationGpu',r7Captures=target,r7ClientBindings='true',r7Backends='0,1,2',r8Policy=7)
run('real-unsorted','validateR7RealContinuationGpu',r7Captures=target,r7ClientBindings='true',r7Backends='1',r8Policy=8)

run('draw-complementary-options','validateShaderEfficiencyGpu',r7Draw='true',modelDrawPack='complementary',drawSnapshots=DATA/'baseline/client/cmi-particle-profiles')

run('empty-boundaries','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='0,1,63,64,65',r7Scenes='ordinary,corpse,dome_attack',r7Modes='1,2',r8Policy=7)

run('journal-source-fallback','validateR7FullChainJournalGpu',r8Policy=7,r8JournalBackends='cuda-control,rt-aabb,rt-triangle',r8ForceSource='true')

run('admission-sources','validateR7AdmissionSources')
