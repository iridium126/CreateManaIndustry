"""Whole-client gate for explicitly paired controlled captures; never infer FPS from query timings.

Manifest: {"rounds":[{"aa":[a.json,b.json],"ab":[a.json,b.json]}, ...]}.
Each process round requires fresh independent client processes; each window >=30s/1000 frames.
"""
import argparse
import csv
import json
import math
from pathlib import Path
import re
import statistics
from collections import Counter


def load(path):
    path=Path(path);meta=json.loads(path.read_text())
    with path.with_suffix('.csv').open(newline='') as stream:rows=list(csv.DictReader(stream))
    for key in ('dimension','size','fps_limit','vsync','gpu','driver','context','engine_source_sha256','diagnostic_receipt_sha256','auto_throttle'):
        if key not in meta:raise ValueError('missing capture context: '+key)
    if meta['engine_source_sha256']=='unavailable' or meta['diagnostic_receipt_sha256'] in ('none','unavailable'):raise ValueError('unqualified/missing diagnostic source receipt')
    if meta.get('invalid_reason') or len(rows)<1000 or meta.get('measured_seconds',0)<30:
        raise ValueError(f'{path}: invalid/short capture; requires 30 seconds and 1000 frames')
    values=[float(row['interval_ms']) for row in rows]
    if not all(math.isfinite(v) and v>0 for v in values):raise ValueError('nonfinite/nonpositive frame interval')
    if abs(sum(values)/1000-meta['measured_seconds'])>1.e-5:raise ValueError('frame CSV duration does not match committed metadata')
    values.sort();times={f'p{p}':values[math.ceil(len(values)*p/100)-1] for p in (50,95,99)}
    camera=path.with_name(path.stem+'-camera.csv')
    with camera.open(newline='') as f:poses=list(csv.DictReader(f))
    if len(poses)!=len(rows):raise ValueError('camera samples missing')
    signature=[]
    for key in ('x','y','z','yaw_degrees','pitch_degrees','vertical_fov_degrees'):
        axis=[float(p[key]) for p in poses]
        if not all(math.isfinite(x) for x in axis) or max(axis)-min(axis)>.001:raise ValueError('camera moved or camera/FOV unavailable')
        signature.append(statistics.median(axis))
    for a,b in zip(rows,poses):
        if a['scene_frame']!=b['scene_frame']:raise ValueError('camera frame mismatch')
    counts={}
    for key in ('live','model_input'):
        known=[int(r[key]) for r in rows if int(r[key])>=0]
        if len(known)<len(rows)*.95:raise ValueError('same-generation particle counts unavailable')
        counts[key]=statistics.median(known)
    attacks=Counter(re.sub(r'\b\d+:(?=helix/|dome/|starfall/)','',r['attack_contract_stage']) for r in rows)
    return dict(path=str(path),metadata=meta,times=times,average_fps=1000/statistics.mean(values),frames=len(rows),camera=signature,counts=counts,attack_distribution={k:v/len(rows) for k,v in attacks.items()})


def compare(manifest):
    rounds=manifest.get('rounds',[]);out=[];processes=set();fixed_context=None;fixed_camera=None
    for index,round in enumerate(rounds):
        aa=[load(p) for p in round['aa']];ab=[load(p) for p in round['ab']];candidate_aa=[load(p) for p in round.get('candidate_aa',[])]
        if len(aa)!=2 or len(ab)!=2 or len(candidate_aa)!=2:raise ValueError('each process round requires production AA, candidate AA and AB')
        captures=aa+ab+candidate_aa
        context=lambda c:tuple(str(c['metadata'].get(k,'unavailable')) for k in ('dimension','size','fps_limit','vsync','gpu','driver','engine_source_sha256','diagnostic_receipt_sha256','auto_throttle'))+(re.sub(r'; (?:session|backend)=[^;]*','',c['metadata'].get('context','unavailable')),)
        if any(c['metadata'].get('auto_throttle') is not False for c in captures):raise ValueError('fixed-count measurements require auto throttle off')
        contexts={context(c) for c in captures}
        if len(contexts)!=1:raise ValueError('unmatched shader/options/dimension/settings/GPU/source/receipt context')
        current_context=next(iter(contexts))
        if fixed_context is not None and current_context!=fixed_context:raise ValueError('three process rounds must use the same fixed scene context')
        fixed_context=current_context
        if fixed_camera is None:fixed_camera=aa[0]['camera']
        if any(any(abs(a-b)>.001 for a,b in zip(c['camera'],fixed_camera)) for c in captures):raise ValueError('camera poses differ; never compare orientations')
        for c in captures:
            if any(abs(c['counts'][k]-aa[0]['counts'][k])>max(1,aa[0]['counts'][k]*.001) for k in ('live','model_input')):raise ValueError('unmatched simulated population')
            if any(abs(c['attack_distribution'].get(k,0)-aa[0]['attack_distribution'].get(k,0))>.05 for k in set(c['attack_distribution'])|set(aa[0]['attack_distribution'])):raise ValueError('unmatched attack stages')
        ids={(c['metadata'].get('process_id'),c['metadata'].get('process_started')) for c in captures}
        if any(None in identity or 'unavailable' in identity for identity in ids):raise ValueError('process identity unavailable')
        if processes&ids:raise ValueError('rounds must use independent processes')
        processes.update(ids)
        if aa[0]['metadata'].get('backend')!=aa[1]['metadata'].get('backend'):raise ValueError('A/A has different backends')
        if aa[0]['metadata'].get('backend')!='production-opengl':raise ValueError('production AA required')
        if ab[0]['metadata'].get('backend')!='production-opengl':raise ValueError('A/B baseline must be production OpenGL')
        if ab[1]['metadata'].get('backend')==ab[0]['metadata'].get('backend'):raise ValueError('A/B has the same backend; no candidate FPS measurement')
        if any(c['metadata']['backend']!=ab[1]['metadata']['backend'] for c in candidate_aa):raise ValueError('candidate AA backend mismatch')
        gain=ab[0]['times']['p50']-ab[1]['times']['p50'];noise=max(abs(aa[0]['times']['p50']-aa[1]['times']['p50']),abs(candidate_aa[0]['times']['p50']-candidate_aa[1]['times']['p50']));threshold=max(noise,.1,ab[0]['times']['p50']*.01)
        tails=all(ab[1]['times'][p]-ab[0]['times'][p]<=max(abs(aa[1]['times'][p]-aa[0]['times'][p]),abs(candidate_aa[1]['times'][p]-candidate_aa[0]['times'][p])) for p in ('p95','p99'))
        out.append(dict(round=index+1,aa=aa,candidate_aa=candidate_aa,ab=ab,p50_gain_ms=gain,threshold_ms=threshold,passed=gain>threshold and tails))
    return dict(rounds=out,frame_gate_pass=len(out)>=3 and all(r['passed'] for r in out),
                production_replacement=False,reason='Frame gate only. Candidate still requires full physics/event, visual, compatibility and lifecycle qualification.')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Refusing to overwrite earlier analysis')
    a.output.write_text(json.dumps(compare(json.loads(a.manifest.read_text())),indent=2)+'\n')
