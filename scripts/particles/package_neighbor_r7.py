"""Immutable R7 prototype/diagnostic delivery, with explicit unqualified client gates."""
import hashlib,io,json,shutil,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'build/benchmarks/neighbor-r7'
OUT=ROOT/'build/distributions'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
    return h.hexdigest()

def main():
    jar=OUT/'createmanaindustry-0.2.7-hardware-diagnostics-r7-20261031.jar'
    bundle=OUT/'neighbor-r7-results-20261031.zip'
    provenance=OUT/'neighbor-r7-provenance-20261031.json'
    if any(p.exists() for p in (jar,bundle,provenance)):raise ValueError('Refusing to overwrite earlier R7 delivery')
    baseline=json.loads((DATA/'baseline/manifest.json').read_text());unchanged=0
    for name,record in baseline['files'].items():
        prefix='source/src/main/resources/assets/createmanaindustry/shaders/'
        if name.startswith(prefix):
            if sha(ROOT/name.removeprefix('source/'))!=record['sha256']:raise ValueError('Production shader drift: '+name)
            unchanged+=1
    if subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)!=(DATA/'baseline/initial-staged.patch').read_bytes():raise ValueError('User initial index changed')
    suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
    tests=sum(int(s.get('tests',0)) for s in suites);failures=sum(int(s.get('failures',0))+int(s.get('errors',0)) for s in suites)
    if tests<1006 or failures:raise ValueError('Incomplete/failing Java suite')
    for scene in ('motion','dome'):
        for round in range(1,4):
            r=json.loads((DATA/f'sequences/{scene}-r{round}/metadata.json').read_text())
            if not r['passed'] or r['samples']<64:raise ValueError('Incomplete geometric measurement')
    for round in range(1,4):
        if not json.loads((DATA/f'copy-concurrency-r{round}/metadata.json').read_text())['passed']:raise ValueError('COPY probe failure')
    for case in ('final-regression','full-nonempty-query-r1'):
        r=json.loads((DATA/case/'hardware.json').read_text())
        if not r['passed'] or not r['copy_probe']:raise ValueError('Hardware geometric/copy regression failed')
    source_jar=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(source_jar) as outer,zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
        for name in ('engine/hardware/HardwareNative','engine/hardware/HardwareExperiments','engine/ParticleQuerySequence','engine/ClientFrameCapture','shaderpack/CMIPackEntityMergeHook'):
            path='com/iridium126/createmanaindustry/client/particles/'+name+'.class'
            if inner.read(path)!=(ROOT/'build/classes/java/main'/path).read_bytes():raise ValueError('Stale jar class '+path)
    shutil.copyfile(source_jar,jar)
    persistent=ROOT/'.local/particle-baselines/reference/hardware-offload-r7-20261031'
    if persistent.exists():raise ValueError('Do not overwrite persistent R7 baseline')
    shutil.copytree(DATA/'baseline',persistent)
    snapshot=DATA/'delivery-source';snapshot.mkdir(exist_ok=False);sources={}
    for directory in ('src/main','src/test','scripts/particles','native/neighbor','native/offload','docs/benchmarks'):
        for p in sorted((ROOT/directory).rglob('*')):
            if not p.is_file() or '__pycache__' in p.parts:continue
            name=p.relative_to(ROOT).as_posix();sources[name]=sha(p);target=snapshot/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    junit=DATA/'junit';junit.mkdir(exist_ok=False)
    for p in (ROOT/'build/test-results/test').glob('TEST-*.xml'):shutil.copy2(p,junit/p.name)
    failure_dir=DATA/'failures';failure_dir.mkdir(exist_ok=False)
    if (ROOT/'hs_err_pid9564.log').exists():shutil.copy2(ROOT/'hs_err_pid9564.log',failure_dir)
    native=DATA/'native-runtime';native.mkdir(exist_ok=False)
    for name in ('CmiHardwareOffload.dll','nvrtc-builtins64_128.dll','nvrtc64_120_0.dll'):shutil.copy2(ROOT/'.gradle/hardware-offload/build'/name,native/name)
    (native/'README.txt').write_text('Optional R7 diagnostic runtime. Extract these DLLs into <client game directory>/cmi-hardware/native, or set -Dcmi.hardware.nativeDirectory=<absolute path>. Only explicit hardware status loads them. No experimental simulation backend is qualified. Ordinary builds do not need CUDA/OptiX.\n')
    shutil.copy2(ROOT/'.gradle/neighbor-rt/dependencies.json',native/'sdk-lock.json')
    for args,name in ((['git','status','--short'],'delivery-status.txt'),(['git','diff','--binary'],'delivery-working.patch')):(DATA/name).write_bytes(subprocess.check_output(args,cwd=ROOT,stderr=subprocess.DEVNULL))
    record=dict(schema=1,status='R7 isolated hardware prototypes and diagnostics; production continuation and client admission incomplete',production_backend='production-opengl',experimental_client_backend_exposed=False,client_fps_improvement='unmeasured',production_replacement=False,unchanged_production_shader_files=unchanged,junit_tests=tests,junit_failures=failures,jar_sha256=sha(jar),initial_index_sha256=sha(DATA/'baseline/initial-staged.patch'),baseline_manifest_sha256=sha(DATA/'baseline/manifest.json'),sources=sources,native_runtime={p.name:sha(p) for p in native.iterdir()},historical={p.name:sha(p) for p in OUT.glob('*r[456]*results*.zip')})
    files=[p for p in sorted(DATA.rglob('*')) if p.is_file() and '__pycache__' not in p.parts]
    record['evidence_files']={p.relative_to(DATA).as_posix():dict(bytes=p.stat().st_size,sha256=sha(p)) for p in files}
    with zipfile.ZipFile(bundle,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as archive:
        for p in files:archive.write(p,'neighbor-r7/'+p.relative_to(DATA).as_posix())
        archive.write(jar,jar.name);archive.writestr('provenance.json',json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(bundle) as archive:
        if archive.testzip():raise ValueError('ZIP CRC verification failed')
    record['results_zip_sha256']=sha(bundle);provenance.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(dict(jar=str(jar),results=str(bundle),bytes=bundle.stat().st_size,tests=tests,production_shaders_unchanged=unchanged),indent=2))

if __name__=='__main__':main()
