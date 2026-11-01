"""Freeze R7 source/index and the complete first Complementary client round."""
import hashlib,json,shutil,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/benchmarks/neighbor-r7/baseline'
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda:f.read(1<<20),b''):h.update(block)
    return h.hexdigest()
def main():
    OUT.mkdir(parents=True,exist_ok=False);files={}
    def copy(p,target):
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target);files[target.relative_to(OUT).as_posix()]=dict(bytes=target.stat().st_size,sha256=sha(target))
    for directory in ('src/main','src/test','scripts/particles','native/neighbor','docs/benchmarks','gradle'):
        for p in sorted((ROOT/directory).rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts:copy(p,OUT/'source'/p.relative_to(ROOT))
    for name in ('build.gradle','settings.gradle','gradle.properties'):copy(ROOT/name,OUT/'source'/name)
    logs=ROOT/'run/logs';copy(logs/'debug.log',OUT/'client/debug.log')
    for directory,pattern in (('cmi-particle-frames','*'),('cmi-neighbor-inputs','comp_*/*'),('cmi-particle-query-sequences','comp_*/*'),('cmi-particle-profiles','session-2000007-*.json')):
        for p in sorted((logs/directory).glob(pattern)):
            if p.is_file() and (directory!='cmi-particle-frames' or any(s in p.name for s in ('35a8985d','88e91d87','fafa1f79','336ab7a9'))):copy(p,OUT/'client'/p.relative_to(logs))
    for args,name in ((['git','diff','--cached','--binary'],'initial-staged.patch'),(['git','status','--short'],'initial-status.txt')):
        (OUT/name).write_bytes(subprocess.check_output(args,cwd=ROOT))
    record=dict(schema=1,commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),files=files,initial_index_sha256=sha(OUT/'initial-staged.patch'),camera_scenarios=dict(low='horizontal, high terrain load',high='vertical upward, low terrain load',zero='vertical downward, low terrain load'),production_replacement=False)
    (OUT/'manifest.json').write_text(json.dumps(record,indent=2)+'\n');print('R7 immutable baseline:',len(files),'files',OUT)
if __name__=='__main__':main()
