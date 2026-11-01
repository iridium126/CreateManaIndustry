"""Immutable R7 continuation delivery. Correctness gates admit diagnostic
backends; measured isolated speed is recorded, never used as an FPS claim."""
import hashlib,io,json,shutil,subprocess,sys,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'build/benchmarks/neighbor-r7-continuation-20261101'
OUT=ROOT/'build/distributions'
BACKENDS=('cuda-control','rt-aabb','rt-triangle','tensor','tensor-control')
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1<<20),b''):h.update(block)
    return h.hexdigest()
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def passed(path):
    d=read(path)
    if not d.get('passed') or d.get('failures') or d.get('reference_aa_differences') or d.get('update_boundary_mismatches'):raise ValueError('Gate rejected: '+str(path))
    return d
def copy(source,target):target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
def zip_tree(source,target):
    with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
        for p in sorted(source.rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts:z.write(p,p.relative_to(source).as_posix())
    with zipfile.ZipFile(target) as z:
        if z.testzip():raise ValueError('ZIP CRC failure')
def main():
    jar=OUT/'createmanaindustry-0.2.7-hardware-diagnostics-r7-20261101.jar';client=OUT/'cmi-r7-client-20261101.zip';results=OUT/'neighbor-r7-results-20261101.zip';provenance=OUT/'neighbor-r7-provenance-20261101.json'
    if any(p.exists() for p in (jar,client,results,provenance)):raise ValueError('Refusing to overwrite a delivery')
    baseline=read(DATA/'baseline/manifest.json')
    current_index=subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)
    if hashlib.sha256(current_index).hexdigest()!=baseline['initial_index_sha256']:raise ValueError('Initial user index changed; do not reset it')
    unchanged=0
    for name,digest in baseline['source'].items():
        if name.startswith('src/main/resources/assets/createmanaindustry/shaders/'):
            if sha(ROOT/name)!=digest:raise ValueError('Production shader drift: '+name)
            unchanged+=1
    gates={}
    for backend in BACKENDS:
        multi=DATA/'multiframe-final-v1/rt-aabb/continuation.json' if backend=='rt-aabb' else DATA/f'qualification-v1/multiframe-{backend}/continuation.json'
        gates[backend]={'multiframe':passed(multi),'large':passed(DATA/f'qualification-v1/large-{backend}/continuation.json'),'capacity':passed(DATA/f'qualification-v1/capacity-{backend}/continuation.json')}
        matrix=read(DATA/f'qualification-v1/matrix-{backend}/continuation.json')
        if backend in ('cuda-control','rt-aabb'):
            # First run's generic-only recovery fixture incorrectly required a
            # MODEL restoration entry. Retain it and require exact rechecks.
            if not matrix['failures'] or any('Recovery fixture must contain a real captured column state' not in e for e in matrix['failures']):raise ValueError('Unexpected matrix failure')
            passed(DATA/f'fixture-recheck/{backend}/continuation.json')
        else:passed(DATA/f'qualification-v1/matrix-{backend}/continuation.json')
        if matrix['reference_aa_differences'] or matrix['update_boundary_mismatches']:raise ValueError('Matrix query/AA mismatch')
    passed(DATA/'real-shader-consumption/real-update.json');passed(DATA/'journal-final-v3/full-chain-journal.json');passed(DATA/'release-build/chain-capture.json');passed(DATA/'safety-final-v2/async.json');passed(DATA/'safety-final-v2/copy-state.json');passed(DATA/'geometry-final/hardware.json')
    for backend in BACKENDS:
        proof=passed(DATA/f'consumer-proof/{backend}/continuation.json')
        if proof.get('dome_native_rows_verified',0)<=0:raise ValueError('No verified native Dome consumption')
    for mutation in ('generation','overflow','incomplete','offset','id'):passed(DATA/f'result-rejection/{mutation}/continuation.json')
    passed(DATA/'actual-sequence-adapter/gpu-ordinary/metadata.json')
    if 'BUILD SUCCESSFUL' not in (DATA/'release-build.log').read_text():raise ValueError('Final build missing')
    measurements=read(DATA/'chain-measurements-v2/summary.json')
    for i in (1,2,3):passed(DATA/f'copy-chain-r{i}/metadata.json')
    if 'OK' not in (DATA/'python-final.log').read_text():raise ValueError('Python regression missing')
    suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')];tests=sum(int(s.get('tests',0)) for s in suites)
    if tests<1006 or any(int(s.get('failures',0))+int(s.get('errors',0)) for s in suites):raise ValueError('Incomplete Java suite')
    for log,marker in (('draw-eight-packs.log','MODEL merge GPU:'),('draw-complementary-options.log','MODEL merge GPU:'),('draw-native.log','BUILD SUCCESSFUL')):
        if marker not in (DATA/log).read_text(encoding='utf-8',errors='replace') or 'BUILD SUCCESSFUL' not in (DATA/log).read_text(encoding='utf-8',errors='replace'):raise ValueError('Draw gate missing '+log)
    if '--check' in sys.argv:
        print(json.dumps(dict(passed=True,junit_tests=tests,unchanged_production_shaders=unchanged,backends=list(BACKENDS)+['copy'])));return
    delivery=DATA/'client-delivery';delivery.mkdir(exist_ok=False)
    runtime=delivery/'hardware';native_hashes={};sdk_hashes={}
    for p in sorted((ROOT/'native/offload').iterdir()):
        if p.is_file() and (p.suffix in ('.h','.cpp','.cu','.json') or p.name=='CMakeLists.txt'):copy(p,runtime/'source'/p.name);native_hashes[p.name]=sha(p)
    for name in ('CmiHardwareOffload.dll','nvrtc-builtins64_128.dll','nvrtc64_120_0.dll'):
        p=ROOT/'.gradle/hardware-offload/build'/name;copy(p,runtime/'native'/name);native_hashes[name]=sha(p)
    deps=read(ROOT/'.gradle/neighbor-rt/dependencies.json')
    for key,value in deps.items():
        source=Path(value['root']);target=runtime/'sdk'/key
        if key!='cuda_nvrtc':
            for p in sorted((source/'include').rglob('*')):
                if p.is_file():copy(p,target/'include'/p.relative_to(source/'include'))
        for p in sorted(source.rglob('*')):
            if p.is_file() and ('license' in p.name.lower() or 'eula' in p.name.lower()) and p.suffix.lower() not in ('.h','.hpp'):copy(p,target/'notices'/p.relative_to(source))
    copy(ROOT/'.gradle/neighbor-rt/dependencies.json',runtime/'sdk/dependencies-template.json')
    for p in (runtime/'sdk').rglob('*'):
        if p.is_file() and p.name!='dependencies-template.json':sdk_hashes[p.relative_to(runtime/'sdk').as_posix()]=sha(p)
    for p in (ROOT/'.gradle/hardware-offload/media-headers').glob('*.h'):copy(p,runtime/'notices'/p.name)
    copy(ROOT/'native/offload/media-headers.lock.json',runtime/'notices/media-headers.lock.json')
    sources=read(DATA/'admission-sources/admission-sources.json');device=read(DATA/'geometry-final/hardware.json')
    classroot=ROOT/'build/classes/java/main';class_names=set()
    for p in (classroot/'com/iridium126/createmanaindustry/client/particles/engine/hardware').glob('*.class'):class_names.add(p.relative_to(classroot).as_posix())
    for prefix in ('com/iridium126/createmanaindustry/client/particles/engine/ParticleRuntime','com/iridium126/createmanaindustry/client/particles/engine/ParticleBuffers','com/iridium126/createmanaindustry/client/particles/engine/ParticlePrograms','com/iridium126/createmanaindustry/client/particles/engine/DomeBodyContacts','com/iridium126/createmanaindustry/client/allaystorm/StarfallGpuBuffers','com/iridium126/createmanaindustry/client/gpu/state/GpuComputeState','com/iridium126/createmanaindustry/client/render/scene/ClientGpuScene'):
        matches=list((classroot/prefix).parent.glob(Path(prefix).name+'*.class'))
        if not matches:raise ValueError('Missing receipt class '+prefix)
        for p in matches:class_names.add(p.relative_to(classroot).as_posix())
    receipt=dict(schema=1,qualification='correctness and basic drawing; experimental only; real-client FPS/quality acceptance pending',backends=list(BACKENDS)+['copy'],renderer=device['gpu'],driver=device['driver'],native_sha256=native_hashes,sdk_sha256=sdk_hashes,classes_sha256={name:sha(classroot/name) for name in sorted(class_names)},**sources)
    ordinary=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(ordinary) as outer:
        inner_bytes=outer.read('META-INF/jars/createmanaindustry.jar')
        with zipfile.ZipFile(io.BytesIO(inner_bytes)) as inner:
            if 'cmi-r7-admission.json' in inner.namelist():raise ValueError('Normal build accidentally admits experimental backends')
            for name in class_names:
                if inner.read(name)!=(classroot/name).read_bytes():raise ValueError('Stale distribution class '+name)
            packed=io.BytesIO()
            with zipfile.ZipFile(packed,'w',zipfile.ZIP_DEFLATED) as target:
                for entry in inner.infolist():target.writestr(entry,inner.read(entry.filename))
                target.writestr('cmi-r7-admission.json',json.dumps(receipt,indent=2)+'\n')
        with zipfile.ZipFile(delivery/jar.name,'x',zipfile.ZIP_DEFLATED) as target:
            for entry in outer.infolist():target.writestr(entry,packed.getvalue() if entry.filename=='META-INF/jars/createmanaindustry.jar' else outer.read(entry.filename))
    copy(ROOT/'scripts/particles/Install-R7.ps1',delivery/'Install-R7.ps1');copy(ROOT/'docs/benchmarks/gpu-neighbor-r7-client-20261101.md',delivery/'R7-client-guide.md')
    manifest=dict(jar=jar.name,runtime_id=sha(runtime/'native/CmiHardwareOffload.dll')[:12],files={p.relative_to(delivery).as_posix():sha(p) for p in sorted(delivery.rglob('*')) if p.is_file()})
    (delivery/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Exercise installation only in an isolated fake game. Never launch a client
    # or edit the user's real run/config/mods directory during delivery.
    smoke=DATA/'delivery-smoke';game=smoke/'game';game.mkdir(parents=True,exist_ok=False)
    for attempt in (1,2):
        with (smoke/f'installer-{attempt}.log').open('wb') as log:
            subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(delivery/'Install-R7.ps1'),'-GameDirectory',str(game),'-InstallMod'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    config=read(game/'cmi-hardware/config.json');installed=read(config['dependencies'])
    for key,value in installed.items():
        if not Path(value['root']).is_dir():raise ValueError('Invalid installed SDK '+key)
    if sha(game/'mods'/jar.name)!=sha(delivery/jar.name):raise ValueError('Installer JAR mismatch')
    with (smoke/'development-overlay.log').open('wb') as log:
        subprocess.run(['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/r7-client.init.gradle','prepareR7QualifiedClientResources','-Pr7DiagnosticJar='+str(delivery/jar.name),'--no-configuration-cache','--console=plain'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    expected=(json.dumps(receipt,indent=2)+'\n').encode()
    if (ROOT/'build/r7-qualified-client-resources/cmi-r7-admission.json').read_bytes()!=expected:raise ValueError('Development overlay mismatch')
    (smoke/'result.json').write_text(json.dumps(dict(passed=True,installer_idempotent=True,installed_jar_sha256=sha(game/'mods'/jar.name),development_overlay=True,client_launched=False,real_game_directory_modified=False),indent=2)+'\n')
    copy(delivery/jar.name,jar);zip_tree(delivery,client)
    snapshot=DATA/'delivery-source';snapshot.mkdir(exist_ok=False);source_hashes={}
    for directory in ('src/main','src/test','scripts/particles','native/offload','docs/benchmarks','gradle'):
        for p in sorted((ROOT/directory).rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.ptx':copy(p,snapshot/p.relative_to(ROOT));source_hashes[p.relative_to(ROOT).as_posix()]=sha(p)
    junit=DATA/'junit';junit.mkdir(exist_ok=False)
    for p in (ROOT/'build/test-results/test').glob('TEST-*.xml'):copy(p,junit/p.name)
    for args,name in ((['git','status','--short'],'delivery-status.txt'),(['git','diff','--binary'],'delivery-working.patch')):(DATA/name).write_bytes(subprocess.check_output(args,cwd=ROOT,stderr=subprocess.DEVNULL))
    persistent=ROOT/'.local/particle-baselines/reference/hardware-offload-r7-20261101'
    if persistent.exists():raise ValueError('Do not overwrite persistent baseline')
    shutil.copytree(DATA/'baseline',persistent)
    record=dict(schema=2,status='R7 non-client implementation/validation delivered; user executes actual-client acceptance',production_backend='production-opengl',production_replacement=False,client_fps_improvement='unmeasured',experimental_client_backends=receipt['backends'],junit_tests=tests,unchanged_production_shaders=unchanged,initial_index_sha256=baseline['initial_index_sha256'],baseline_manifest_sha256=sha(DATA/'baseline/manifest.json'),previous_r7_provenance_sha256=sha(OUT/'neighbor-r7-provenance-20261031.json'),sources=source_hashes,receipt=receipt,jar_sha256=sha(jar),client_zip_sha256=sha(client))
    record['evidence_files']={p.relative_to(DATA).as_posix():dict(bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(DATA.rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
    (DATA/'delivery-provenance.json').write_text(json.dumps(record,indent=2)+'\n');zip_tree(DATA,results);record['results_zip_sha256']=sha(results);provenance.write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(dict(jar=str(jar),client=str(client),results=str(results),tests=tests,qualified=receipt['backends']),indent=2))
if __name__=='__main__':main()
