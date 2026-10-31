"""Package the built outer JAR and measurements, excluding third-party pack source."""
import hashlib,io,json,shutil,subprocess,zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    frozen=ROOT/'.local/particle-baselines/reference/feather-merge-20261031'
    manifest=json.loads((frozen/'manifest.json').read_text())
    for name,digest in manifest['files'].items():
        assert sha(frozen/(name+'.txt'))==digest,('Changed frozen baseline',name)
    resources=[name for name in manifest['files'] if '/resources/' in name]
    assert all(sha(ROOT/name)==manifest['files'][name] for name in resources),'Production GLSL changed'
    assert sha(ROOT/'src/main/java/com/iridium126/createmanaindustry/client/particles/engine/AllayFeatherGpu.java')==manifest['files']['src/main/java/com/iridium126/createmanaindustry/client/particles/engine/AllayFeatherGpu.java']
    totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in (ROOT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        for k in totals:totals[k]+=int(suite.attrib.get(k,0))
    assert totals['tests']>=996 and not any(totals[k] for k in ['failures','errors','skipped']),totals
    assert 'BUILD SUCCESSFUL' in (ROOT/'build/feather-r3-final.log').read_text(encoding='utf8',errors='replace'),'Final build failed'
    summary=json.loads((ROOT/'build/benchmarks/feather-shared-final/feather-summary.json').read_text())
    assert summary['passed'],'Replacement gate not met'
    original=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
    with zipfile.ZipFile(original) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
            names=inner.namelist()
            assert not any(any(s in n for s in ['FeatherBaseline','FrozenFeather','FeatherExperiment','FeatherMergeProductionFixture','FeatherExperimentalSources','GpuValidation']) for n in names),'Validation classes in distribution'
            optimizer=inner.read('com/iridium126/createmanaindustry/client/particles/shaderpack/ModelShaderOptimization.class')
            assert b'prepareFeatherBranches' in optimizer and b'uniform material branches' in optimizer
    dist=ROOT/'build/distributions';dist.mkdir(exist_ok=True)
    jar=dist/'createmanaindustry-0.2.7-feather-r3-20261031.jar';shutil.copy2(original,jar)
    files=set()
    for folder in (ROOT/'build/benchmarks').glob('feather-*'):
        if folder.is_dir():
            files.update(p for p in folder.rglob('*') if p.is_file() and p.suffix in ('.csv','.json'))
        elif folder.suffix=='.json':files.add(folder)
    files.update((ROOT/'build').glob('feather-*.log'))
    files.update((ROOT/'build/test-results/test').glob('TEST-*.xml'))
    report=ROOT/'docs/benchmarks/feather-merge-client-log-2026-10-31.md'
    files.update([report,frozen/'manifest.json'])
    sources=[p for base in ['src/main/java','src/main/resources/assets/createmanaindustry/shaders','src/test/java','scripts/particles'] for p in (ROOT/base).rglob('*') if p.is_file() and p.suffix in ('.java','.comp','.vsh','.fsh','.gsh','.glsl','.py','.gradle')]
    provenance=dict(jar=jar.name,jar_sha256=sha(jar),unit_tests=totals,frozen_files=len(manifest['files']),unchanged_shader_resources=len(resources),staged_entries=len(subprocess.check_output(['git','diff','--cached','--name-only'],cwd=ROOT,text=True).splitlines()),gate=dict(passed=summary['passed'],heavy_improvements=summary['heavy_improvements'],initial_regressions=summary['initial_regressions'],stable_regressions=summary['stable_regressions']),source_sha256={str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in sorted(sources)},archived_sha256={str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in sorted(files)},client_acceptance='pending')
    record=dist/'feather-merge-r3-provenance-20261031.json';record.write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf8');files.add(record)
    archive=dist/'feather-merge-r3-results-20261031.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(files):z.write(path,str(path.relative_to(ROOT)).replace('\\','/'))
    print(json.dumps(dict(jar=str(jar),sha256=sha(jar),archive=str(archive),archive_bytes=archive.stat().st_size,unit_tests=totals,frozen_files=len(manifest['files']),staged_entries=provenance['staged_entries']),indent=2))

if __name__=='__main__':main()
