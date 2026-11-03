"""Immutable diagnostic distribution. Correctness admits testing; FPS is user-owned."""
import argparse,hashlib,io,json,shutil,subprocess,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'build/benchmarks/rt-avoidance-20261103'
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def copy(source,target):target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
def passed(path):
    data=read(path)
    if not data.get('passed') or any(data.get(k) for k in ('failures','reference_aa_differences','update_boundary_mismatches')):raise ValueError('Failed gate '+str(path))
    return data

def main():
    args=argparse.ArgumentParser();args.add_argument('--data',type=Path,default=DATA);opts=args.parse_args();data=opts.data
    checks=data/'checks-final'
    full=passed(checks/'real-faults/real-chain.json');passed(checks/'forces/hardware.json');passed(checks/'async/async.json')
    passed(checks/'stall/dome-stall.json');passed(checks/'avoidance/avoidance.json');passed(checks/'local/local.json')
    check_jobs=read(checks/'jobs.json')
    if len(check_jobs)!=7 or any(x['exit_code'] or x['before']!=x['after'] for x in check_jobs):raise ValueError('Final checks incomplete')
    if {x['policy'] for x in full['cases'] if x.get('passed') and 'policy' in x}!={83,211,339}:raise ValueError('Missing force policies')
    if {x.get('mutation') for x in full['cases'] if x.get('passed') and 'mutation' in x}!={'nonce','epoch','incomplete','overflow','offset'}:raise ValueError('GPU publication error coverage missing')
    jobs=read(data/'rules-final/jobs.json')
    if len(jobs)!=3 or any(x['exit_code'] or x['before']!=x['after'] for x in jobs):raise ValueError('Rule matrix incomplete')
    oracle_limitations={}
    for policy in (83,211,339):
        report=read(data/'rules-final'/('rules-p'+str(policy))/'continuation.json')
        if not report.get('passed') or report.get('failures') or report.get('update_boundary_mismatches'):raise ValueError('Rule failure')
        if report.get('dome_path')!='production-contact-control-apply-audit':raise ValueError('Production Dome not exercised')
        oracle_limitations[str(policy)]=report.get('reference_aa_differences',[])
    measured=read(data/'measure-final/jobs.json')
    if len(measured)!=3 or any(x['exit_code'] or x['before']!=x['after'] or x['before']!=measured[0]['before'] for x in measured):raise ValueError('Final measurement rounds incomplete')
    for name,digest in measured[-1]['after'].items():
        if sha(ROOT/name)!=digest:raise ValueError('Source changed after final measurements: '+name)
    suites=list((ROOT/'build/test-results/test').glob('TEST-*.xml'))
    import xml.etree.ElementTree as ET
    reports=[ET.parse(p).getroot() for p in suites]
    if not reports or any(int(s.get('failures',0))+int(s.get('errors',0)) for s in reports):raise ValueError('Java suite failed')
    baseline=read(data/'baseline/manifest.json')
    if hashlib.sha256(subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)).hexdigest()!=baseline['index_sha256']:raise ValueError('Index changed')
    for name,digest in baseline['files'].items():
        relative=name.removeprefix('source/')
        if relative.startswith('src/main/resources/assets/createmanaindustry/shaders/particles/') and '/diagnostics/' not in relative and sha(ROOT/relative)!=digest:raise ValueError('Unexpected production shader change '+relative)
    sources=read(checks/'admission/admission-sources.json');normal=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    documentation=hashlib.sha256((ROOT/'docs/gpu-hardware-offload.md').read_bytes()+(ROOT/'docs/benchmarks/hardware-r9-2026-11-03.md').read_bytes()).hexdigest()[:8]
    version='dome-repair-abi10-'+sha(ROOT/'.gradle/hardware-offload/build/CmiHardwareOffload.dll')[:10]+'-'+documentation
    stage=data/('delivery-'+version);stage.mkdir(exist_ok=False);hardware=stage/'hardware';native={};sdk={}
    for path in sorted((ROOT/'native/offload').iterdir()):
        if path.is_file() and path.suffix in ('.cu','.cpp','.h','.json'):copy(path,hardware/'source'/path.name);native[path.name]=sha(path)
    for name in ('CmiHardwareOffload.dll','nvrtc64_120_0.dll','nvrtc-builtins64_128.dll'):
        path=ROOT/'.gradle/hardware-offload/build'/name;copy(path,hardware/'native'/name);native[name]=sha(path)
    for key,value in read(ROOT/'.gradle/neighbor-rt/dependencies.json').items():
        base=Path(value['root'])
        if key!='cuda_nvrtc':
            for path in (base/'include').rglob('*'):
                if path.is_file():copy(path,hardware/'sdk'/key/'include'/path.relative_to(base/'include'))
        for path in base.rglob('*'):
            if path.is_file() and ('license' in path.name.lower() or 'eula' in path.name.lower()) and path.suffix.lower() not in ('.h','.hpp'):copy(path,hardware/'sdk'/key/'notices'/path.relative_to(base))
    copy(ROOT/'.gradle/neighbor-rt/dependencies.json',hardware/'sdk/dependencies-template.json')
    for path in (hardware/'sdk').rglob('*'):
        if path.is_file() and path.name!='dependencies-template.json':sdk[path.relative_to(hardware/'sdk').as_posix()]=sha(path)
    for path in (ROOT/'.gradle/hardware-offload/media-headers').glob('*.h'):copy(path,hardware/'notices'/path.name)
    copy(ROOT/'native/offload/media-headers.lock.json',hardware/'notices/media-headers.lock.json')
    classes={p.relative_to(ROOT/'build/classes/java/main').as_posix():sha(p) for p in (ROOT/'build/classes/java/main').rglob('*.class')}
    # Reuse combination remains an offline experiment: its independent prerequisites failed.
    backends=['rt-aabb-r9-force','rt-aabb-r9-force-gpu-result'];policies=dict(zip(backends,(83,339)))
    from run_r9 import CAPTURE
    capture=read(CAPTURE/'manifest.json')
    receipt=dict(schema=1,native_abi=10,backends=backends,policies=policies,renderer=capture['gpu'],driver=capture['driver'],classes_sha256=classes,native_sha256=native,sdk_sha256=sdk,qualification='explicit repaired R9 diagnostics only; offline performance rejected; client stall recovery pending; approximate avoidance NOT admitted',rule_reference_aa_differences=oracle_limitations,**sources)
    name='createmanaindustry-0.2.7-hardware-diagnostics-'+version+'.jar'
    with zipfile.ZipFile(normal) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
            if 'cmi-r7-admission.json' in inner.namelist():raise ValueError('Ordinary JAR contains admission')
            for path,digest in classes.items():
                if hashlib.sha256(inner.read(path)).hexdigest()!=digest:raise ValueError('Stale compiled class '+path)
            stream=io.BytesIO()
            with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as target:
                for entry in inner.infolist():target.writestr(entry,inner.read(entry.filename))
                target.writestr('cmi-r7-admission.json',json.dumps(receipt,indent=2)+'\n')
        with zipfile.ZipFile(stage/name,'x',zipfile.ZIP_DEFLATED) as target:
            for entry in outer.infolist():target.writestr(entry,stream.getvalue() if entry.filename=='META-INF/jars/createmanaindustry.jar' else outer.read(entry.filename))
    installer=(ROOT/'scripts/particles/Install-R8.ps1').read_text().replace('R8','DomeRepair').replace('r8-20261101','dome-repair-abi10')
    (stage/'Install-DomeRepair.ps1').write_text(installer,encoding='utf-8-sig')
    guide=(ROOT/'docs/gpu-hardware-offload.md').read_text(encoding='utf-8').replace('DOME_REPAIR_DIAGNOSTIC_JAR',name)
    (stage/'DomeRepair-client-guide.md').write_text(guide,encoding='utf-8')
    copy(ROOT/'docs/benchmarks/hardware-r9-2026-11-03.md',stage/'R9-results.md')
    copy(data/'measurement-summary.json',stage/'measurement-summary.json')
    copy(normal,stage/'production'/normal.name)
    copy(data/'baseline/manifest.json',stage/'frozen-baseline-manifest.json')
    (stage/'rule-oracle-limitations.json').write_text(json.dumps(oracle_limitations,indent=2)+'\n')
    for script in ('compare_r9_client.py','compare_client_frames.py','analyze_rt_dome.py'):
        copy(ROOT/'scripts/particles'/script,stage/'analysis'/script)
    example={'rounds':[{'aa':[f'captures/r{i}/production-aa1.json',f'captures/r{i}/production-aa2.json'],'candidate_aa':[f'captures/r{i}/candidate-aa1.json',f'captures/r{i}/candidate-aa2.json'],'ab':[f'captures/r{i}/production-ab.json',f'captures/r{i}/candidate-ab.json']} for i in (1,2,3)]}
    (stage/'client-manifest.example.json').write_text(json.dumps(example,indent=2)+'\n')
    # Freeze the complete reviewable implementation alongside the original baseline.
    changed=subprocess.check_output(['git','ls-files'],cwd=ROOT,text=True).splitlines()
    untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    for relative in sorted(set(changed+untracked)):
        path=ROOT/relative
        if path.is_file():copy(path,data/'final-source'/relative)
    manifest=dict(jar=name,runtime_id=sha(hardware/'native/CmiHardwareOffload.dll')[:12],files={p.relative_to(stage).as_posix():sha(p) for p in stage.rglob('*') if p.is_file()});(stage/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2)+'\n')
    distributions=ROOT/'build/distributions';distributions.mkdir(exist_ok=True)
    bundle=distributions/('cmi-'+version+'-client.zip')
    with zipfile.ZipFile(bundle,'x',zipfile.ZIP_DEFLATED) as target:
        for path in stage.rglob('*'):
            if path.is_file():target.write(path,path.relative_to(stage))
    copy(stage/name,distributions/name)
    evidence=distributions/('cmi-'+version+'-results.zip')
    with zipfile.ZipFile(evidence,'x',zipfile.ZIP_DEFLATED,allowZip64=True) as target:
        for path in data.rglob('*'):
            if path.is_file() and not path.relative_to(data).parts[0].startswith(('delivery-','install-smoke')) and (path.relative_to(data).parts[0] in ('baseline','final-source') or path.suffix in ('.json','.csv','.log','.txt','.glsl','.cu','.h')):target.write(path,path.relative_to(data))
    experiment=distributions/('cmi-'+version+'-experiment-source.zip')
    with zipfile.ZipFile(experiment,'x',zipfile.ZIP_DEFLATED) as target:
        for path in (data/'final-source').rglob('*'):
            if path.is_file():target.write(path,path.relative_to(data/'final-source'))
        target.writestr('EXPERIMENT-STATUS.txt','Approximate avoidance is NOT client-admitted. ModelRenderPublication, same-algorithm GL probes, full quality/overlap/render contention and integrated timing remain incomplete. See docs/gpu-hardware-offload.md.\n')
    record=dict(jar=str(distributions/name),bundle=str(bundle),evidence=str(evidence),experiment=str(experiment),jar_sha256=sha(distributions/name),bundle_sha256=sha(bundle),evidence_sha256=sha(evidence),experiment_sha256=sha(experiment),receipt=receipt,junit_tests=sum(int(s.get('tests',0)) for s in reports));(data/'delivery.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({k:v for k,v in record.items() if k!='receipt'},indent=2))
if __name__=='__main__':main()
