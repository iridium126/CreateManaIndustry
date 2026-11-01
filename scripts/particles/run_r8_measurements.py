"""Serial independent processes; 64 paired AA/AB samples per invocation. No FPS conversion."""
import json,subprocess,hashlib,datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';OUT=DATA/'measurements-final-v2';OUT.mkdir(exist_ok=False);jobs=[]
def context():
 hashes={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'native/offload').iterdir()) if p.is_file()}
 hashes['native_dll']=hashlib.sha256((ROOT/'.gradle/hardware-offload/build/CmiHardwareOffload.dll').read_bytes()).hexdigest()
 gpu=subprocess.run(['nvidia-smi','--query-gpu=timestamp,name,driver_version,pstate,clocks.current.graphics,clocks.current.sm,temperature.gpu,power.draw,power.limit,utilization.gpu,memory.used','--format=csv'],capture_output=True,text=True)
 return dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),native_sha256=hashes,telemetry=gpu.stdout,telemetry_error=gpu.stderr)
def run(name,task,**opts):
 args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in opts.items()]
 before=context()
 with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 jobs.append(dict(name=name,task=task,options=opts,exit_code=r.returncode,context_before=before,context_after=context()));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2,default=str)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
for round in (1,2,3):
 for seq in ('motion-all','dome-ordinary-all','dome-all','dome-apply-all','dome-24'):
  run(f'sequence-{seq}-r{round}','validateR8SequenceGpu',r8Sequence=DATA/('sequence-'+seq))
 for policy in (1,2,4,8,16,7):
  run(f'chain-p{policy}-r{round}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='1000,65536',r7Scenes='ordinary,dome_attack',r7Modes=1,r7Frames=1,r7Measure='true',r7SlotBytes=16<<20,r7Layout='paired-islands',r8Policy=policy)
