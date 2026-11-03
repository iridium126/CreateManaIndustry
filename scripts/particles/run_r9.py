"""Serial GPU checks and three independent process measurements. No live client launch."""
import argparse, hashlib, json, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
DEFAULT=ROOT/'build/benchmarks/hardware-r9-20261103'
CAPTURE=ROOT/'build/benchmarks/neighbor-r8-20261101/baseline/client/cmi-neighbor-inputs/R7_comp_horizontal_full-ee9bd778-7a19-42da-92c8-a258a7b35cb4'

def context():
    paths=[p for p in (ROOT/'native/offload').iterdir() if p.suffix in ('.cu','.cpp','.h','.json')]
    paths += list((ROOT/'src/main/java/com/iridium126/createmanaindustry/client/particles/engine/hardware').glob('*.java'))
    paths += list((ROOT/'scripts/particles').glob('*.java'))
    paths += [ROOT/'scripts/particles/validation.init.gradle']
    paths += [p for p in (ROOT/'src/main/resources/assets/createmanaindustry/shaders/particles').rglob('*') if p.is_file()]
    paths += [ROOT/'.gradle/hardware-offload/build/CmiHardwareOffload.dll']
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=DEFAULT);parser.add_argument('--suite',choices=('measure','rules','components'),default='measure');parser.add_argument('--name');args=parser.parse_args()
    out=args.output/(args.name or args.suite);out.mkdir(parents=True,exist_ok=False);jobs=[]
    def run(name,task,**opts):
        command=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle',task,'--offline','--no-configuration-cache','--console=plain','-PbenchmarkOutput='+str(out/name),'-Pr7Capture='+str(CAPTURE)]+['-P'+k+'='+str(v) for k,v in opts.items()]
        before=context()
        with (out/(name+'.log')).open('wb') as log:result=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        after=context();jobs.append(dict(name=name,arguments=command,exit_code=result.returncode,before=before,after=after));(out/'jobs.json').write_text(json.dumps(jobs,indent=2)+'\n')
        print(name,'PASS' if result.returncode==0 and before==after else 'FAIL',flush=True)
        if result.returncode or before!=after:raise SystemExit('Failed job retained: '+name)
    if args.suite=='measure':
        for i in (1,2,3):run('actual-r'+str(i),'validateR9RealChainGpu',r9Measure='true')
    elif args.suite=='components':
        run('async','validateR7AsyncGpu',r8Policy=2)
        run('forces','validateR9HardwareGpu')
        run('media','validateR9MediaGpu')
    else:
        for policy in (83,211,339):run('rules-p'+str(policy),'validateR7ContinuationGpu',r7Backend='shared-rt-aabb',r7Counts='0,1,63,64,65,1000',r7Scenes='ordinary,starfall,column,dome_attack,corpse,recovery,missing_collision',r7Modes='1,2',r7Frames=10,r8Policy=policy)
if __name__=='__main__':main()
