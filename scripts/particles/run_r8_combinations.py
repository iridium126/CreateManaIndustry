"""Additional independent combination validation; same fixed R8 native module."""
import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';OUT=DATA/'combinations-final';OUT.mkdir(exist_ok=False);jobs=[]
def run(name,task,**opts):
 args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in opts.items()]
 with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 jobs.append(dict(name=name,exit_code=r.returncode,task=task,options=opts));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2,default=str)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
for policy in (12,15):
 run(f'boundaries-p{policy}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='0,1,63,64,65',r7Scenes='ordinary,corpse,dome_attack',r7Modes='1,2',r8Policy=policy)
 run(f'multi-p{policy}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65',r7Scenes='ordinary,starfall,helix,column,recovery,corpse,wrap,missing_collision,dome_gather,dome_attack,crowded',r7Modes='2',r7Frames=32,r8Policy=policy)
 run(f'real-p{policy}','validateR7RealContinuationGpu',r7ClientBindings='true',r7Backends='1',r8Policy=policy)
 run(f'long-p{policy}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65',r7Scenes='ordinary,crowded,dome_attack',r7Modes='2',r7Frames=96,r8Policy=policy)
 run(f'overflow-p{policy}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='1000',r7Scenes='crowded,dome_attack',r7Modes='1',r8Policy=policy,r8PageBytes=144)
for round in (1,2,3):
 for seq in ('motion-all','dome-ordinary-all','dome-all','dome-apply-all','dome-24'):
  run(f'sequence-{seq}-r{round}','validateR8SequenceGpu',r8Sequence=DATA/('sequence-'+seq),r8Paths='paged-unsorted,combined-unsorted')
 for policy in (12,15):run(f'chain-p{policy}-r{round}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='1000,65536',r7Scenes='ordinary,dome_attack',r7Modes=1,r7Frames=1,r7Measure='true',r7SlotBytes=16<<20,r7Layout='paired-islands',r8Policy=policy)
run('semantic-final','validateR8SemanticGpu')
run('build-final','build')
