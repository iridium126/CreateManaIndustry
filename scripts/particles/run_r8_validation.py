import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/benchmarks/neighbor-r8-20261101/final';OUT.mkdir(exist_ok=False);jobs=[]
def run(name,task,**options):
    args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in options.items()]
    with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    jobs.append(dict(name=name,task=task,options=options,exit_code=r.returncode));(OUT/'validation-jobs.json').write_text(json.dumps(jobs,indent=2)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
run('safety-final','validateR7AsyncGpu',r8Policy=3)
run('geometry-final','validateR7HardwareGpu',r7Counts='0,1,63,64,65,1000,10000,65535,65536,131072')
for policy in (1,2,4,16,7,8):run('multiframe-p'+str(policy),'validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65',r7Scenes='ordinary,starfall,helix,column,recovery,corpse,wrap,missing_collision,dome_gather,dome_attack',r7Modes='2',r7Frames=8,r8Policy=policy)
run('unsorted-long','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65',r7Scenes='ordinary,crowded,dome_attack',r7Modes='2',r7Frames=96,r8Policy=8)
run('page-capacity','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65,1000',r7Scenes='crowded,dome_gather,dome_attack',r7Modes='1,2',r8Policy=7,r8PageBytes=144)
for backend in ('rt-aabb','rt-triangle','cuda-control'):run('large-'+backend,'validateR7ContinuationGpu',r7Backend='shared-'+backend,r7Counts='10000,50000,65535,65536,131072',r7Scenes='ordinary,dome_attack',r7Modes='1',r8Policy=7)
run('real-notify','validateR7RealContinuationGpu',r7ClientBindings='true',r8Policy=3)
run('real-paged-final','validateR7RealContinuationGpu',r7ClientBindings='true',r7Backends='0,1,2',r8Policy=7)
run('real-unsorted','validateR7RealContinuationGpu',r7ClientBindings='true',r7Backends='1',r8Policy=8)
run('journal-final','validateR7FullChainJournalGpu',r8Policy=7,r8JournalBackends='cuda-control,rt-aabb,rt-triangle')
run('trace-diagnostic','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='1000',r7Scenes='ordinary,dome_gather,dome_attack',r7Modes='1,2',r8Policy=35)
