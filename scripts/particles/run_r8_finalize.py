"""Wait for the current controlled batch, then finish remaining serial work."""
import json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'build/benchmarks/neighbor-r8-20261101';manifest=DATA/'measurements-final-v2/jobs.json'
while True:
 if manifest.exists():
  jobs=json.loads(manifest.read_text())
  if len(jobs)==33:
   if any(j['exit_code'] for j in jobs):raise SystemExit('Regular batch has failures; review before finalization')
   break
 time.sleep(2)
for script in ('run_r8_completion.py','run_r8_notifications.py','run_r8_combinations.py'):
 print('START',script,flush=True)
 code=subprocess.call(['python',str(ROOT/'scripts/particles'/script)],cwd=ROOT)
 print('END',script,code,flush=True)
 if code:raise SystemExit(code)
print('FINAL BATCHES COMPLETE',flush=True)
