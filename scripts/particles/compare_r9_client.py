"""Strict user-owned 20% FPS gate on matched real client windows."""
import argparse,json
from pathlib import Path
from compare_client_frames import compare

BACKENDS=('rt-aabb-r9-force','rt-aabb-r9-force-gpu-result')
def evaluate(manifest,backend):
    if backend not in BACKENDS:raise ValueError('Explicit R9 candidate required')
    if manifest.get('comparison'):raise ValueError('Use same-build backend windows')
    result=compare(manifest)
    for round in result['rounds']:
        captures=round['aa']+round['candidate_aa']+round['ab']
        if any(c['metadata']['vsync'] is not False or c['metadata']['fps_limit']!=260 for c in captures):raise ValueError('VSync off and Minecraft unlimited (260) required')
        if round['ab'][1]['metadata']['backend']!=backend:raise ValueError('Different candidate or production fallback')
        baseline,candidate=round['ab'];gain=candidate['average_fps']/baseline['average_fps']-1
        noise=max(abs(b['average_fps']/a['average_fps']-1) for a,b in (round['aa'],round['candidate_aa']))
        round.update(average_fps_gain=gain,average_fps_aa_noise=noise,passed=round['passed'] and gain>=.20 and gain>noise)
    result['frame_gate_pass']=len(result['rounds'])==3 and all(r['passed'] for r in result['rounds'])
    result['reason']='20% average FPS plus matched A/A noise and p95/p99; user visual/rule/lifecycle acceptance still required'
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('output',type=Path);p.add_argument('--backend',choices=BACKENDS,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Refusing to overwrite prior analysis')
    a.output.write_text(json.dumps(evaluate(json.loads(a.manifest.read_text()),a.backend),indent=2)+'\n')
