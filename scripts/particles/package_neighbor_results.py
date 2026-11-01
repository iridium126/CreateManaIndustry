"""Package R5 evidence without overwriting historical measurements or vendor SDKs."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'build/benchmarks/neighbor-r5'
OUT=ROOT/'build/distributions'


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    jar=OUT/'createmanaindustry-0.2.7-neighbor-diagnostics-r5-20261031.jar'
    result=OUT/'neighbor-r5-results-20261031.zip';provenance=OUT/'neighbor-r5-provenance-20261031.json'
    if any(p.exists() for p in (jar,result,provenance)):raise FileExistsError('R5 output already exists; use a new generation')
    for log in ('release-verification.log','final-gpu-regression.log'):
        if 'BUILD SUCCESSFUL' not in (DATA/log).read_text():raise ValueError('Required verification incomplete: '+log)
    for round in range(1,4):
        for comparison in ('aa','ab'):
            if not (DATA/f'complete-grid-{comparison}-{round}/preliminary-only.txt').exists():raise ValueError('Incomplete grid-inclusive cost round')
    baseline=json.loads((DATA/'baseline/manifest.json').read_text())
    count=0
    for name,expected in baseline['files'].items():
        if name.startswith('src/main/resources/assets/createmanaindustry/shaders/'):
            if sha(ROOT/name)!=expected:raise ValueError('Production shader changed: '+name)
            count+=1
    if subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)!=(DATA/'baseline/initial-staged.patch').read_bytes():raise ValueError('Original staged contents changed')
    tests=[ET.parse(path).getroot() for path in (ROOT/'build/test-results/test').glob('*.xml')]
    failures=sum(int(t.get('failures',0))+int(t.get('errors',0)) for t in tests)
    if not tests or failures:raise ValueError('JUnit suite incomplete/failing')
    sourceJar=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(sourceJar) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
            for name in ('client/particles/engine/ClientFrameCapture.class','client/particles/engine/NeighborInputCapture.class','mixin/ParticleClientFrameMixin.class'):
                full='com/iridium126/createmanaindustry/'+name
                if inner.read(full)!=(ROOT/'build/classes/java/main'/full).read_bytes():raise ValueError('JAR contains stale diagnostic bytecode')
    shutil.copyfile(sourceJar,jar)
    sources={}
    for directory in ('src/main','src/test','scripts/particles','native/neighbor','docs/benchmarks'):
        for path in sorted((ROOT/directory).rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:sources[path.relative_to(ROOT).as_posix()]=sha(path)
    historical=OUT/'shader-efficiency-r4-results-20261031.zip'
    sdk=json.loads((ROOT/'.gradle/neighbor-rt/dependencies.json').read_text())
    record=dict(schema=1,production_backend='OpenGL',production_replacement=False,client_fps_improvement='unmeasured',unchanged_production_shader_files=count,
                junit_tests=sum(int(t.get('tests',0)) for t in tests),junit_failures=failures,jar_sha256=sha(jar),sources=sources,sdk=sdk,
                baseline_manifest_sha256=sha(DATA/'baseline/manifest.json'),historical_r4_sha256=sha(historical),
                complete_cost_summary_sha256=sha(DATA/'complete-grid-summary.json'),query_summary_sha256=sha(DATA/'query-motion-summary.json'))
    snapshots=DATA/'delivery-source';snapshots.mkdir(exist_ok=False)
    for name in sources:
        source=ROOT/name;target=snapshots/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    testTarget=DATA/'junit';testTarget.mkdir(exist_ok=False)
    for path in (ROOT/'build/test-results/test').glob('*.xml'):shutil.copyfile(path,testTarget/path.name)
    with zipfile.ZipFile(result,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as bundle:
        for path in sorted(DATA.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:bundle.write(path,'neighbor-r5/'+path.relative_to(DATA).as_posix())
        bundle.write(jar,jar.name);bundle.write(historical,'history/'+historical.name)
        native=ROOT/'.gradle/neighbor-rt/build/CmiNeighborExperiment.dll';bundle.write(native,'native/'+native.name)
        bundle.writestr('provenance.json',json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    record['results_sha256']=sha(result);provenance.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(dict(jar=str(jar),results=str(result),provenance=str(provenance),jar_sha256=sha(jar),results_sha256=sha(result)),indent=2))


if __name__=='__main__':main()
