"""Existing actual capture, recovered original program binaries, no source substitution."""
import json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';wait=DATA/'post-final/jobs.json'
while not wait.exists() or len(json.loads(wait.read_text()))<12:time.sleep(2)
OUT=DATA/'actual-chain-final-v4';OUT.mkdir(exist_ok=False)
args=['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/validation.init.gradle','validateR8RealChainGpu','-PbenchmarkOutput='+str(OUT),'-Pr7Capture='+str(DATA/'baseline/client/cmi-neighbor-inputs/R7_comp_horizontal_full-ee9bd778-7a19-42da-92c8-a258a7b35cb4'),'--no-configuration-cache','--console=plain']
with (DATA/'actual-chain-final-v4.log').open('wb') as log:r=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
(OUT/'job.json').write_text(json.dumps(dict(exit_code=r.returncode,arguments=args),indent=2)+'\n');print('ACTUAL CHAIN','PASS' if r.returncode==0 else 'FAIL',flush=True)
