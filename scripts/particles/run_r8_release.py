import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/benchmarks/neighbor-r8-20261101/release-final';OUT.mkdir(exist_ok=False);jobs=[]
def run(name,task,**options):
 args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in options.items()]
 with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 jobs.append(dict(name=name,exit_code=r.returncode,task=task,options=options));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
run('edges','validateR8EdgeGpu')
run('old-module','validateR8NativeMismatch')
run('copy-state','validateR7CopyStateGpu')
run('semantic','validateR8SemanticGpu')
run('journal','validateR7FullChainJournalGpu',r8Policy=7,r8JournalBackends='cuda-control,rt-aabb,rt-triangle')
run('page-fallback','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65,1000',r7Scenes='crowded,dome_attack',r7Modes='1,2',r8Policy=7,r8PageBytes=144)
run('build','build')
run('tests','test')
run('draw-native','validateNativeAttachmentReplayGpu')
run('draw-eight-packs','validateShaderEfficiencyGpu',r7Draw='true')
