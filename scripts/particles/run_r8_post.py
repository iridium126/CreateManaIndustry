"""Recheck the diagnosed option graph mismatch and final class/CSV changes, serially."""
import json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';wait=DATA/'combinations-final/jobs.json'
while not wait.exists() or len(json.loads(wait.read_text()))<33:time.sleep(2)
OUT=DATA/'post-final';OUT.mkdir(exist_ok=False);jobs=[]
def run(name,task,**opts):
 args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'-PbenchmarkOutput='+str(OUT/name),'--no-configuration-cache','--console=plain']+['-P'+k+'='+str(v) for k,v in opts.items()]
 with (OUT/(name+'.log')).open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 jobs.append(dict(name=name,exit_code=r.returncode,task=task,options=opts));(OUT/'jobs.json').write_text(json.dumps(jobs,indent=2,default=str)+'\n');print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
run('binary-contexts','validateR8BinaryProbe',r7Capture=DATA/'baseline/client/cmi-neighbor-inputs/R7_comp_horizontal_full-ee9bd778-7a19-42da-92c8-a258a7b35cb4')
run('draw-native','validateNativeAttachmentReplayGpu')
run('draw-complementary-options','validateShaderEfficiencyGpu',r7Draw='true',modelDrawPack='complementary',drawSnapshots=DATA/'baseline/client/cmi-particle-profiles')
run('draw-eight-packs','validateShaderEfficiencyGpu',r7Draw='true')
run('semantic','validateR8SemanticGpu')
run('journal-source','validateR7FullChainJournalGpu',r8Policy=7,r8JournalBackends='cuda-control,rt-aabb,rt-triangle',r8ForceSource='true')
for policy in (7,8,12,15):run(f'coincident-p{policy}','validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='65,1000',r7Scenes='ordinary,dome_attack',r7Modes='1,2',r7Frames=2,r7Layout='coincident',r8Policy=policy)
run('build','build')
run('admission-sources','validateR7AdmissionSources')
