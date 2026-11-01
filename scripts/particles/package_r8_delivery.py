"""Immutable R8 tools/capture package. Actual full-chain gate required for new client admission."""
import argparse,hashlib,io,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from package_r7_delivery import ROOT,OUT,sha,read,copy,zip_tree
DATA=ROOT/'build/benchmarks/neighbor-r8-20261101'
def passed(p):
 d=read(p)
 if not d.get('passed') or any(d.get(k) for k in ('failures','reference_aa_differences','update_boundary_mismatches')):raise ValueError('Gate failed '+str(p))
 return d
def main():
 a=argparse.ArgumentParser();a.add_argument('--real-chain',type=Path);args=a.parse_args();admitted=[]
 if args.real_chain:
  full=passed(args.real_chain)
  required={("rt-aabb",p) for p in (1,2,4,16,7)}|{("rt-triangle",4),("cuda-control",4)}
  valid=[x for x in full['cases'] if x.get('passed')]
  if not full.get('actual_capture') or not full.get('reference_passed') or not required<={(x['backend'],x['policy']) for x in valid} or any(x['fallbacks'] or x['ordinary_queries']+x['dome_queries']<=0 for x in valid):raise ValueError('Actual full-chain policy coverage missing')
  if any(x['policy']&8 and x.get('force_queries_verified')!=x['ordinary_queries'] for x in valid):raise ValueError('Unsorted full force proof missing')
  for name,digest in full['file_sha256'].items():
   if sha(ROOT/name)!=digest:raise ValueError('Stale full-chain qualification '+name)
  names={('rt-aabb',p):'rt-aabb-r8-'+name for name,p in [('merged',1),('event',2),('paged',4),('unsorted',8),('gl-dome',16),('combined',7),('paged-unsorted',12),('combined-unsorted',15)]}
  names.update({('rt-triangle',4):'rt-triangle-r8-paged',('cuda-control',4):'cuda-control-r8-paged'})
  admitted=[names[(x['backend'],x['policy'])] for x in valid]
 suffix='r8-20261101'+('-admitted-'+sha(args.real_chain)[:8] if args.real_chain else '')
 jar=OUT/('createmanaindustry-0.2.7-hardware-diagnostics-'+suffix+'.jar');client=OUT/('cmi-'+suffix.replace('r8-','r8-client-',1)+'.zip');raw=OUT/('neighbor-'+suffix+'-results.zip');provenance=OUT/('neighbor-'+suffix+'-provenance.json')
 if any(p.exists() for p in (jar,client,raw,provenance)):raise ValueError('Refusing to overwrite an R8 release')
 baseline=read(DATA/'baseline/manifest.json')
 if hashlib.sha256(subprocess.check_output(['git','diff','--cached','--binary'],cwd=ROOT)).hexdigest()!=baseline['initial_index_sha256']:raise ValueError('User index changed')
 unchanged=0
 for name,digest in baseline['source'].items():
  if name.startswith('source/src/main/resources/assets/createmanaindustry/shaders/'):
   if sha(ROOT/name.removeprefix('source/'))!=digest:raise ValueError('Production shader drift '+name)
   unchanged+=1
 for root in ('release-final','measurements-final-v2','combinations-final','notification-final','completion-final','post-final'):
  jobs=read(DATA/root/'jobs.json');expected={'release-final':10,'measurements-final-v2':33,'combinations-final':33,'notification-final':9,'completion-final':8,'post-final':12}[root]
  failures=[x for x in jobs if x['exit_code']]
  if root=='completion-final' and len(failures)==1 and failures[0]['name']=='draw-complementary-options':
   if 'No active snapshot matches pack/source/effective options/overworld/macros' not in (DATA/root/'draw-complementary-options.log').read_text(errors='replace'):raise ValueError('Unrecognized historical draw failure')
   failures=[] # retained failure; exact diagnosed context/root discovery mismatch rechecked in post-final
  if len(jobs)!=expected or failures:raise ValueError('Incomplete/failed '+root)
  if root=='measurements-final-v2':
   hashes=[j['context_before']['native_sha256'] for j in jobs]
   if any(j['context_before']['native_sha256']!=j['context_after']['native_sha256'] for j in jobs) or any(h!=hashes[0] for h in hashes):raise ValueError('Native source/DLL drift during matched process rounds')
 final_jobs=read(DATA/'final-recheck/jobs.json')
 if len(final_jobs)!=3 or any(x['exit_code'] for x in final_jobs):raise ValueError('Final oracle/semantic/build check incomplete')
 if 'Ran 25 tests' not in (DATA/'final-recheck/python-tests.log').read_text(errors='replace') or 'OK' not in (DATA/'final-recheck/python-tests.log').read_text(errors='replace'):raise ValueError('Python tests incomplete')
 passed(DATA/'release-safety/async.json');passed(DATA/'release-final/edges/edges.json');passed(DATA/'release-final/copy-state/copy-state.json');passed(DATA/'release-final/semantic/semantic.json');passed(DATA/'release-final/journal/full-chain-journal.json')
 for name in ('real-notify','real-paged-final','real-unsorted'):passed(DATA/'completion-final'/name.replace('real-paged-final','real-paged')/'real-update.json')
 suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')];tests=sum(int(s.get('tests',0)) for s in suites)
 if tests<1015 or any(int(s.get('failures',0))+int(s.get('errors',0)) for s in suites):raise ValueError('Java suite incomplete')
 summary=read(DATA/'measurement-summary.json')
 if len(summary['groups'])!=116 or any(len(x['rounds'])!=3 for x in summary['groups'].values()):raise ValueError('Three-process paired results incomplete')
 stage=DATA/('client-delivery-'+suffix);stage.mkdir(exist_ok=False);runtime=stage/'hardware';native={};sdk={}
 for p in sorted((ROOT/'native/offload').iterdir()):
  if p.is_file() and (p.suffix in ('.h','.cpp','.cu','.json') or p.name=='CMakeLists.txt'):copy(p,runtime/'source'/p.name);native[p.name]=sha(p)
 for name in ('CmiHardwareOffload.dll','nvrtc64_120_0.dll','nvrtc-builtins64_128.dll'):
  p=ROOT/'.gradle/hardware-offload/build'/name;copy(p,runtime/'native'/name);native[name]=sha(p)
 for key,value in read(ROOT/'.gradle/neighbor-rt/dependencies.json').items():
  root=Path(value['root'])
  if key!='cuda_nvrtc':
   for p in sorted((root/'include').rglob('*')):
    if p.is_file():copy(p,runtime/'sdk'/key/'include'/p.relative_to(root/'include'))
  for p in root.rglob('*'):
   if p.is_file() and ('license' in p.name.lower() or 'eula' in p.name.lower()) and p.suffix.lower() not in ('.h','.hpp'):copy(p,runtime/'sdk'/key/'notices'/p.relative_to(root))
 copy(ROOT/'.gradle/neighbor-rt/dependencies.json',runtime/'sdk/dependencies-template.json')
 for p in (runtime/'sdk').rglob('*'):
  if p.is_file() and p.name!='dependencies-template.json':sdk[p.relative_to(runtime/'sdk').as_posix()]=sha(p)
 for p in (ROOT/'.gradle/hardware-offload/media-headers').glob('*.h'):copy(p,runtime/'notices'/p.name)
 copy(ROOT/'native/offload/media-headers.lock.json',runtime/'notices/media-headers.lock.json')
 classroot=ROOT/'build/classes/java/main';classes={p.relative_to(classroot).as_posix():sha(p) for p in sorted(classroot.rglob('*.class'))};device=read(DATA/'final/geometry-final/hardware.json')
 sources=read(DATA/'post-final/admission-sources/admission-sources.json')
 # Production shaders are frozen and variant builders retain their R7 source contract. All changed builders/classes/native files are bound below.
 policies={'rt-aabb-r8-'+k:v for k,v in [('merged',1),('event',2),('paged',4),('unsorted',8),('gl-dome',16),('combined',7),('paged-unsorted',12),('combined-unsorted',15)]};policies.update({'rt-triangle-r8-paged':4,'cuda-control-r8-paged':4})
 receipt=dict(schema=1,native_abi=8,policies=policies,backends=admitted,qualification='actual full-chain and drawing passed; client FPS/quality pending' if admitted else 'production capture only; no supplied actual full-chain qualification; offline candidates only',renderer=device['gpu'],driver=device['driver'],native_sha256=native,sdk_sha256=sdk,classes_sha256=classes,**sources)
 normal=ROOT/'build/libs/createmanaindustry-0.2.7.jar'
 with zipfile.ZipFile(normal) as outer:
  with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
   if 'cmi-r7-admission.json' in inner.namelist():raise ValueError('Normal build must not admit experiments')
   for name,digest in classes.items():
    if hashlib.sha256(inner.read(name)).hexdigest()!=digest:raise ValueError('Stale JAR class '+name)
   b=io.BytesIO()
   with zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as z:
    for e in inner.infolist():z.writestr(e,inner.read(e.filename))
    z.writestr('cmi-r7-admission.json',json.dumps(receipt,indent=2)+'\n')
  with zipfile.ZipFile(stage/jar.name,'x',zipfile.ZIP_DEFLATED) as z:
   for e in outer.infolist():z.writestr(e,b.getvalue() if e.filename=='META-INF/jars/createmanaindustry.jar' else outer.read(e.filename))
 copy(ROOT/'scripts/particles/Install-R8.ps1',stage/'Install-R8.ps1');guide=(ROOT/'docs/benchmarks/gpu-neighbor-r8-client-20261101.md').read_text(encoding='utf-8').replace('createmanaindustry-0.2.7-hardware-diagnostics-r8-20261101.jar',jar.name).replace('cmi-r8-client-20261101.zip',client.name)
 if admitted:guide='本包准入回执：'+sha(args.real_chain)+'；实际可用实验后端：'+', '.join(admitted)+'。默认仍为 production，客户端 FPS 和画质待用户验收。\n\n'+guide
 (stage/'R8-client-guide.md').write_text(guide,encoding='utf-8');copy(ROOT/'docs/benchmarks/gpu-neighbor-r8-20261101.md',stage/'R8-report.md')
 (stage/'R8-candidate-policies.json').write_text(json.dumps(dict(native_abi=8,policies=policies,admitted=admitted,offline_only=[x for x in policies if x not in admitted]),indent=2)+'\n')
 manifest=dict(jar=jar.name,runtime_id=sha(runtime/'native/CmiHardwareOffload.dll')[:12],files={p.relative_to(stage).as_posix():sha(p) for p in stage.rglob('*') if p.is_file()});(stage/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2)+'\n')
 smoke=DATA/('delivery-smoke-'+suffix);game=smoke/'game';game.mkdir(parents=True,exist_ok=False)
 for n in (1,2):
  with (smoke/f'install-{n}.log').open('wb') as log:subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(stage/'Install-R8.ps1'),'-GameDirectory',str(game),'-InstallMod'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
 if sha(game/'mods'/jar.name)!=sha(stage/jar.name):raise ValueError('Installer JAR mismatch')
 copy(stage/jar.name,jar);zip_tree(stage,client)
 snapshot=DATA/('final-source-'+suffix);snapshot.mkdir(exist_ok=False)
 names=subprocess.check_output(['git','diff','--name-only'],cwd=ROOT,text=True).splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
 for name in names:
  if (ROOT/name).is_file():copy(ROOT/name,snapshot/name)
 for p in (ROOT/'build/test-results/test').glob('TEST-*.xml'):copy(p,DATA/'junit-final'/p.name)
 record=dict(schema=1,scope=receipt['qualification'],native_abi=8,admitted=admitted,receipt=receipt,junit_tests=tests,unchanged_production_shaders=unchanged,jar_sha256=sha(jar),client_sha256=sha(client),initial_index_sha256=baseline['initial_index_sha256'],evidence_files={p.relative_to(DATA).as_posix():dict(bytes=p.stat().st_size,sha256=sha(p)) for p in DATA.rglob('*') if p.is_file() and not any(x.startswith(('client-delivery-','delivery-smoke-')) for x in p.relative_to(DATA).parts)})
 (DATA/('provenance-'+suffix+'.json')).write_text(json.dumps(record,indent=2)+'\n')
 with zipfile.ZipFile(raw,'x',zipfile.ZIP_DEFLATED,compresslevel=4,allowZip64=True) as z:
  for p in DATA.rglob('*'):
   if p.is_file() and not any(x.startswith(('client-delivery-','delivery-smoke-')) for x in p.relative_to(DATA).parts):z.write(p,p.relative_to(DATA).as_posix())
 with zipfile.ZipFile(raw) as z:
  if z.testzip():raise ValueError('Evidence ZIP CRC failure')
 record['results_zip_sha256']=sha(raw);provenance.write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(dict(jar=str(jar),client=str(client),results=str(raw),admitted=admitted),indent=2))
if __name__=='__main__':main()
