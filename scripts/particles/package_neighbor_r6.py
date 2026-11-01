"""Immutable R6 diagnostic delivery. Never replace history or qualify an experimental backend."""
import hashlib,io,json,shutil,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'build/benchmarks/neighbor-r6'
OUT=ROOT/'build/distributions'

def sha(path):
    result=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):result.update(block)
    return result.hexdigest()

def main():
    jar=OUT/'createmanaindustry-0.2.7-neighbor-diagnostics-r6-20261031.jar';bundle=OUT/'neighbor-r6-results-20261031.zip';provenance=OUT/'neighbor-r6-provenance-20261031.json'
    if any(p.exists() for p in (jar,bundle,provenance)):raise ValueError('Never overwrite an earlier delivery')
    baseline=json.loads((DATA/'baseline/manifest.json').read_text());unchanged=0
    for name,expected in baseline['source'].items():
        if name.startswith('src/main/resources/assets/createmanaindustry/shaders/'):
            if sha(ROOT/name)!=expected:raise ValueError('Production shader changed: '+name)
            unchanged+=1
    if subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)!=(DATA/'baseline/initial-staged.patch').read_bytes():raise ValueError('Initial staging changed')
    suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')];tests=sum(int(s.get('tests',0)) for s in suites);failures=sum(int(s.get('failures',0))+int(s.get('errors',0)) for s in suites)
    if tests<1005 or failures:raise ValueError('Incomplete/failing Java suite')
    sourceJar=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(sourceJar) as outer,zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
        for name in ('client/particles/engine/ParticleQuerySequence','client/particles/engine/ClientFrameCapture','client/particles/engine/ParticleFrameStages','client/gpu/profile/GpuTimingObserver','client/render/shaderpack/WorldEffectRenderer'):
            path='com/iridium126/createmanaindustry/'+name+'.class'
            if inner.read(path)!=(ROOT/'build/classes/java/main'/path).read_bytes():raise ValueError('Stale diagnostic bytecode: '+path)
        if 'assets/createmanaindustry/shaders/particles/diagnostics/query_sequence.comp' not in inner.namelist():raise ValueError('Missing lazy diagnostic kernel')
    shutil.copyfile(sourceJar,jar)
    snapshots=DATA/'delivery-source';snapshots.mkdir(exist_ok=False);sources={}
    for directory in ('src/main','src/test','scripts/particles','native/neighbor','docs/benchmarks'):
        for p in sorted((ROOT/directory).rglob('*')):
            if not p.is_file() or '__pycache__' in p.parts:continue
            name=p.relative_to(ROOT).as_posix();sources[name]=sha(p);target=snapshots/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    junit=DATA/'junit';junit.mkdir(exist_ok=False)
    for p in (ROOT/'build/test-results/test').glob('TEST-*.xml'):shutil.copy2(p,junit/p.name)
    history=DATA/'historical-r5';history.mkdir(exist_ok=False)
    previous=ROOT/'build/benchmarks/neighbor-r5'
    for p in previous.glob('query-*.csv'):shutil.copy2(p,history/p.name)
    for name in ('query-motion-summary.json','query-summary.json','complete-grid-summary.json'):shutil.copy2(previous/name,history/name)
    for command,file in ((['git','status','--short'],'delivery-status.txt'),(['git','diff','--binary'],'delivery-working.patch')):
        (DATA/file).write_bytes(subprocess.check_output(command,cwd=ROOT,stderr=subprocess.DEVNULL))
    sdk=json.loads((ROOT/'.gradle/neighbor-rt/dependencies.json').read_text());native=ROOT/'.gradle/neighbor-rt/build/CmiNeighborExperiment.dll'
    record=dict(schema=1,production_backend='production-opengl',production_replacement=False,experimental_client_backend_exposed=False,client_fps_improvement='unmeasured',
        status='offline screening and diagnostics delivered; real full-chain capture and physical/visual/client qualification remain incomplete',unchanged_production_shader_files=unchanged,junit_tests=tests,junit_failures=failures,
        jar_sha256=sha(jar),baseline_manifest_sha256=sha(DATA/'baseline/manifest.json'),initial_staging_sha256=sha(DATA/'baseline/initial-staged.patch'),sources=sources,sdk=sdk,native_dll_sha256=sha(native),
        historical={p.name:sha(p) for p in (OUT/'neighbor-r5-results-20261031.zip',OUT/'shader-efficiency-r4-results-20261031.zip')})
    files=[p for p in sorted(DATA.rglob('*')) if p.is_file() and '__pycache__' not in p.parts]
    record['evidence_files']={p.relative_to(DATA).as_posix():dict(bytes=p.stat().st_size,sha256=sha(p)) for p in files}
    with zipfile.ZipFile(bundle,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as archive:
        for p in files:archive.write(p,'neighbor-r6/'+p.relative_to(DATA).as_posix())
        archive.write(jar,jar.name);archive.write(native,'native/'+native.name);archive.writestr('provenance.json',json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(bundle) as archive:
        bad=archive.testzip()
        if bad:raise ValueError('ZIP CRC failed: '+bad)
    record['results_zip_sha256']=sha(bundle);provenance.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(dict(jar=str(jar),results=str(bundle),result_bytes=bundle.stat().st_size,tests=tests,production_shaders_unchanged=unchanged),indent=2))

if __name__=='__main__':main()
