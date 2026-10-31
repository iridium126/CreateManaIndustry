"""Compare every feather input cell with its matching A/A measurement, without filtering rounds."""
import argparse, csv, json, statistics as st
from collections import defaultdict
from pathlib import Path

def read(path):
    with path.open(encoding='utf8') as f: return list(csv.DictReader(f))

def grouped(path, fields):
    out=defaultdict(list)
    for r in read(path): out[tuple(r[f] for f in fields)].append(r)
    return out

def measure(rows):
    changes=[]; absolute=[]
    for i in range(3):
        rr=[r for r in rows if int(r['round'])==i]
        changes.append(st.median((float(r['candidate_ms'])-float(r['baseline_ms']))/max(.001,float(r['baseline_ms'])) for r in rr))
        absolute.append(st.median(float(r['candidate_ms'])-float(r['baseline_ms']) for r in rr))
    return dict(baseline_ms=st.median(float(r['baseline_ms']) for r in rows),candidate_ms=st.median(float(r['candidate_ms']) for r in rows),changes=changes,changes_ms=absolute)

def compare(directory, aa, filename, fields, extra=None, repeat=None):
    groups=grouped(directory/filename,fields); calibration=grouped(aa/filename,fields)
    assert groups.keys()==calibration.keys(), 'A/A input mismatch'
    more=grouped(extra/filename,fields) if extra and (extra/filename).exists() else {}
    again=grouped(repeat/filename,fields) if repeat and (repeat/filename).exists() else {}
    result=[]
    for k,rows in groups.items():
        expected=12 if filename=='production.csv' else 16
        assert all(sum(int(r['round'])==i for r in rows)==expected and sum(int(r['round'])==i for r in calibration[k])==expected for i in range(3)), ('missing pairs',k)
        m=measure(rows); a=measure(calibration[k]); nr=max(.005,*map(abs,a['changes'])); na=max(.002,*map(abs,a['changes_ms']))
        m['initial_regression']=all(x>nr and y>na for x,y in zip(m['changes'],m['changes_ms']))
        if k in more:
            ea=measure(more[k]);nr=max(nr,*map(abs,ea['changes']));na=max(na,*map(abs,ea['changes_ms']))
        m.update(zip(fields,k));m.update(noise_relative=nr,noise_ms=na)
        m['improved']=all(x< -nr and y< -na for x,y in zip(m['changes'],m['changes_ms']))
        m['regressed']=all(x>nr and y>na for x,y in zip(m['changes'],m['changes_ms']))
        if k in again:
            r=measure(again[k]);m['repeat']=r
            m['repeat_regressed']=all(x>nr and y>na for x,y in zip(r['changes'],r['changes_ms']))
        result.append(m)
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--aa',type=Path,required=True);p.add_argument('--aa-extra',type=Path);p.add_argument('--repeat',type=Path);a=p.parse_args()
    feathers=compare(a.directory,a.aa,'feather.csv',['pack','width','height','pose','scene','count','distance'],a.aa_extra,a.repeat)
    packs={m['pack'] for m in feathers};assert len(packs)==8,'Incomplete pack matrix'
    expected={(p,str(w),str(h),str(pose),str(s),str(n),str(d)) for p in packs for w,h in [(1920,1080),(2560,1506)] for pose in range(3) for s in range(3) for n in [0,1000,10000,50000,131072] for d in [6.0,64.0,256.0]}
    fields=['pack','width','height','pose','scene','count','distance']
    assert {tuple(m[f] for f in fields) for m in feathers}==expected,'Incomplete input matrix'
    models=compare(a.directory,a.aa,'production.csv',['pack','width','height','workload','scene','count']) if (a.directory/'production.csv').exists() else []
    coverage=[]
    for directory in [a.directory,a.aa]:
        rows=read(directory/'feather-coverage.csv')
        assert len(rows)==len(feathers)
        assert {tuple(r[f] for f in fields) for r in rows}==expected,'Incomplete coverage matrix'
        for r in rows:
            expected_covered=str(int(r['count'])>0 and int(r['scene'])!=2).lower()
            assert r['baseline_covered']==r['candidate_covered']==expected_covered,r
        coverage.append(len(rows))
    loads=[]
    for f in a.directory.glob('*/feather-load.csv'):
        rows=read(f);cal=read(a.aa/f.relative_to(a.directory))
        assert len(rows)==len(cal)==3,'Incomplete warm-load pairs'
        assert all(r['baseline_programs']==r['candidate_programs'] for r in rows),'Changed program count'
        noise=max(2.,*(abs(float(r['candidate_ms'])-float(r['baseline_ms'])) for r in cal))
        delta=[float(r['candidate_ms'])-float(r['baseline_ms']) for r in rows]
        loads.append(dict(source=f.parent.name,baseline_ms=[float(r['baseline_ms']) for r in rows],candidate_ms=[float(r['candidate_ms']) for r in rows],programs=[(r['baseline_programs'],r['candidate_programs']) for r in rows],noise_ms=noise,changes_ms=delta,improved=all(x< -noise for x in delta),regressed=all(x>noise for x in delta)))
    assert len(loads)==8,'Incomplete warm-load packs'
    identical={}
    for f in a.directory.glob('options-*.json'):
        info=json.loads(f.read_text());root=a.directory/f.stem.removeprefix('options-')
        identical[info['pack']]=all((root/('baseline-0-'+s+'.glsl')).read_bytes()==(root/('candidate-0-'+s+'.glsl')).read_bytes() for s in ['VERTEX','FRAGMENT'])
    heavy=[m for m in feathers if not identical[m['pack']] and int(m['count'])>=50000 and m['scene']!='2' and m['improved']]
    # Retain every initial flag. A targeted independent repeat must confirm its
    # direction before calling it a stable regression across runs.
    regressions=[m for m in feathers+models if m.get('repeat_regressed',m['regressed'])]
    out=dict(feathers=feathers,models=models,loads=loads,coverage=coverage,source_identical=identical,initial_regressions=sum(m['initial_regression'] for m in feathers+models),heavy_improvements=len(heavy),stable_regressions=len(regressions),passed=bool(heavy) and not regressions and not any(m['regressed'] for m in loads))
    (a.directory/'feather-summary.json').write_text(json.dumps(out,indent=2)+'\n',encoding='utf8')
    print('PASS' if out['passed'] else 'NOT MET', 'groups',len(feathers),'model groups',len(models),'heavy improvements',len(heavy),'regressions',len(regressions),'load regressions',sum(m['regressed'] for m in loads))
    for kind,rows in [('heavy',heavy),('regression',regressions)]:
        for m in rows[:20]:print(kind,{k:v for k,v in m.items() if k not in ('noise_relative','noise_ms')})
    for m in loads:print('load',m)

if __name__=='__main__':main()
