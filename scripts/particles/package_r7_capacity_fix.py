"""Immutable CPU binding hotfix over the qualified R7 native/shader bundle."""
import hashlib,io,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from package_r7_delivery import ROOT,OUT,sha,read,copy,zip_tree
DATA=ROOT/'build/benchmarks/r7-backend-disappearance-20261101'
SUFFIX='r7-20261101-capacity-fix'

def main():
    jar=OUT/f'createmanaindustry-0.2.7-hardware-diagnostics-{SUFFIX}.jar'
    client=OUT/'cmi-r7-client-20261101-capacity-fix.zip'
    results=OUT/'neighbor-r7-capacity-fix-results-20261101.zip'
    provenance=OUT/'neighbor-r7-capacity-fix-provenance-20261101.json'
    if any(p.exists() for p in (jar,client,results,provenance)):raise ValueError('Refusing to overwrite a release')
    baseline=read(DATA/'baseline.json')
    if hashlib.sha256(subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)).hexdigest()!=baseline['initial_staged_sha256']:raise ValueError('User index changed')
    evidence=DATA/'real-client-bindings-final/real-update.json';gpu=read(evidence)
    if not gpu['passed'] or gpu['failures'] or not gpu['client_capacity_binding'] or len(gpu['cases'])!=20 or len(gpu['production_returns'])!=20 or len(gpu['missing_capacity_reproductions'])!=4:raise ValueError('Incomplete binding regression')
    if any(c['output_live']!=0 or c['input_live']<=0 for c in gpu['missing_capacity_reproductions']):raise ValueError('Failure was not reproduced')
    if any(any(c['outputs']['particle_types'].get(k,0)<=0 for k in ('model','feather','gold')) for c in gpu['cases']):raise ValueError('Missing material coverage')
    if 'BUILD SUCCESSFUL' not in (DATA/'final-validation.log').read_text(encoding='utf-8',errors='replace'):raise ValueError('Final build missing')
    suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
    tests=sum(int(x.get('tests',0)) for x in suites)
    if tests<1013 or any(int(x.get('failures',0))+int(x.get('errors',0)) for x in suites):raise ValueError('Java regression missing')
    old=OUT/'cmi-r7-client-20261101.zip';prior=read(OUT/'neighbor-r7-provenance-20261101.json')
    if sha(old)!=prior['client_zip_sha256']:raise ValueError('Previous qualified bundle changed')
    stage=DATA/'client-delivery';stage.mkdir(exist_ok=False)
    with zipfile.ZipFile(old) as z:
        files=read_manifest=json.loads(z.read('SHA256SUMS.json'))['files']
        for name,digest in files.items():
            data=z.read(name)
            if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Previous bundle checksum '+name)
            if name.startswith('hardware/'):
                target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    receipt=dict(prior['receipt']);classes=ROOT/'build/classes/java/main';names=set(receipt['classes_sha256'])
    for p in (classes/'com/iridium126/createmanaindustry/client/particles/engine').glob('ParticleBuffers*.class'):names.add(p.relative_to(classes).as_posix())
    # Native code, SDK and generated shaders retain their previous qualification.
    for name,digest in receipt['native_sha256'].items():
        if sha(stage/'hardware'/('native' if name.endswith('.dll') else 'source')/name)!=digest:raise ValueError('Native bundle drift')
    for name,digest in receipt['classes_sha256'].items():
        if not name.startswith(('com/iridium126/createmanaindustry/client/particles/engine/ParticleRuntime','com/iridium126/createmanaindustry/client/particles/engine/ParticleBuffers')) and sha(classes/name)!=digest:raise ValueError('Unqualified class drift '+name)
    receipt['classes_sha256']={name:sha(classes/name) for name in sorted(names)}
    receipt['qualification']='R7 qualified kernels plus actual client capacity binding regression; real-client visual/FPS acceptance remains pending'
    receipt['capacity_fix_evidence_sha256']=sha(evidence)
    encoded=(json.dumps(receipt,indent=2)+'\n').encode()
    normal=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(normal) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
            if 'cmi-r7-admission.json' in inner.namelist():raise ValueError('Ordinary build has admission')
            for name in names:
                if hashlib.sha256(inner.read(name)).hexdigest()!=receipt['classes_sha256'][name]:raise ValueError('Stale build '+name)
            output=io.BytesIO()
            with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
                for entry in inner.infolist():target.writestr(entry,inner.read(entry.filename))
                target.writestr('cmi-r7-admission.json',encoded)
        with zipfile.ZipFile(stage/jar.name,'x',zipfile.ZIP_DEFLATED) as target:
            for entry in outer.infolist():target.writestr(entry,output.getvalue() if entry.filename=='META-INF/jars/createmanaindustry.jar' else outer.read(entry.filename))
    guide=(ROOT/'docs/benchmarks/gpu-neighbor-r7-client-20261101.md').read_text().replace('r7-20261101.jar',SUFFIX+'.jar').replace('cmi-r7-client-20261101.zip',client.name)
    guide='**容量绑定修复版：关闭并重启客户端后使用本包。旧进程丢失的模型不能仅靠切回生产后端恢复。原生运行时不变；不要再使用旧诊断 JAR 的回执。**\n\n'+guide
    (stage/'R7-client-guide.md').write_text(guide,encoding='utf-8');copy(ROOT/'docs/benchmarks/gpu-neighbor-r7-capacity-fix-20261101.md',stage/'R7-capacity-fix-report.md');copy(ROOT/'scripts/particles/Install-R7.ps1',stage/'Install-R7.ps1')
    manifest=dict(jar=jar.name,runtime_id=sha(stage/'hardware/native/CmiHardwareOffload.dll')[:12],files={p.relative_to(stage).as_posix():sha(p) for p in sorted(stage.rglob('*')) if p.is_file()})
    (stage/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2)+'\n')
    smoke=DATA/'delivery-smoke';game=smoke/'game';game.mkdir(parents=True,exist_ok=False)
    for attempt in (1,2):
        with (smoke/f'installer-{attempt}.log').open('wb') as log:subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(stage/'Install-R7.ps1'),'-GameDirectory',str(game),'-InstallMod'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    with (smoke/'development-overlay.log').open('wb') as log:subprocess.run(['cmd','/d','/c',str(ROOT/'gradlew.bat'),'-I','scripts/particles/r7-client.init.gradle','prepareR7QualifiedClientResources','-Pr7DiagnosticJar='+str(stage/jar.name),'--no-configuration-cache','--console=plain'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    if (ROOT/'build/r7-qualified-client-resources/cmi-r7-admission.json').read_bytes()!=encoded or sha(game/'mods'/jar.name)!=sha(stage/jar.name):raise ValueError('Installation mismatch')
    (smoke/'result.json').write_text(json.dumps(dict(passed=True,installer_idempotent=True,client_launched=False,real_game_directory_modified=False),indent=2)+'\n')
    copy(stage/jar.name,jar);zip_tree(stage,client)
    snapshot=DATA/'source';snapshot.mkdir(exist_ok=False)
    tracked=['scripts/particles/R7RealContinuationGpu.java','scripts/particles/package_r7_capacity_fix.py','scripts/particles/package_r7_delivery.py','scripts/particles/validation.init.gradle','scripts/particles/README.md','src/main/java/com/iridium126/createmanaindustry/client/particles/engine/ParticleBuffers.java','src/main/java/com/iridium126/createmanaindustry/client/particles/engine/ParticleRuntime.java','src/test/java/com/iridium126/createmanaindustry/client/particles/engine/ParticleRenderLifecycleContractTest.java','docs/benchmarks/gpu-neighbor-r7-capacity-fix-20261101.md']
    for name in tracked:copy(ROOT/name,snapshot/name)
    for p in (ROOT/'build/test-results/test').glob('TEST-*.xml'):copy(p,DATA/'junit'/p.name)
    record=dict(passed=True,scope='client capacity-binding hotfix; no new FPS claim',junit_tests=tests,actual_update_replays=20,production_return_checks=20,missing_capacity_reproductions=4,initial_staged_sha256=baseline['initial_staged_sha256'],previous_provenance_sha256=sha(OUT/'neighbor-r7-provenance-20261101.json'),receipt=receipt,source_sha256={name:sha(ROOT/name) for name in tracked},jar_sha256=sha(jar),client_zip_sha256=sha(client))
    record['evidence_files']={p.relative_to(DATA).as_posix():dict(bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(DATA.rglob('*')) if p.is_file()}
    (DATA/'provenance.json').write_text(json.dumps(record,indent=2)+'\n');zip_tree(DATA,results);record['results_zip_sha256']=sha(results);provenance.write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(dict(passed=True,jar=str(jar),client=str(client),results=str(results),tests=tests),indent=2))
if __name__=='__main__':main()
