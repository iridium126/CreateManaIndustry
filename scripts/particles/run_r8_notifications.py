"""Native notification AA/AB with actual legacy versus event consumer behavior."""
import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';OUT=DATA/'notification-final';OUT.mkdir(exist_ok=False);jobs=[]
for round in (1,2,3):
 for seq in ('motion-all','dome-all','dome-24'):
  name=f'sequence-{seq}-r{round}';opts=dict(r8Sequence=DATA/('sequence-'+seq),r8Paths='merged,event,combined',r8ConsumerStrategyWait='true')
  args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle','validateR8SequenceGpu','-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in opts.items()]
  with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
  jobs.append(dict(name=name,exit_code=r.returncode,options=opts));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2,default=str)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
