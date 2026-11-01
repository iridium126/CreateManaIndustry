import argparse,csv,json
from pathlib import Path
import numpy as np

def summarize(root):
    results=[]
    for directory in sorted(root.glob('native-real-*')):
        if not directory.is_dir() or not (directory/'metadata.json').exists():continue
        rows=list(csv.DictReader((directory/'samples.csv').open()))
        for name in sorted({r['input'] for r in rows}):
            for path in ('0','1','2','3'):
                group=[r for r in rows if r['input']==name and r['path']==path];pairs={}
                for row in group:pairs.setdefault(int(row['pair']),{})[int(row['side'])]=row
                valid=[p for p in pairs.values() if len(p)==2]
                if len(valid)<64:continue
                values={}
                for metric in ('gpu_ms','completed_wall_ms'):
                    a=np.array([float(p[0][metric]) for p in valid]);b=np.array([float(p[1][metric]) for p in valid]);values[metric]=dict(gl_p50=float(np.median(a)),path_p50=float(np.median(b)),paired_gain_percent=float(np.median((a-b)/a)*100),noise_abs_p95=float(np.quantile(abs(a-b),.95)) if path=='0' else None)
                results.append(dict(process=directory.name,input=name,path={'0':'GL-AA','1':'CUDA-grid','2':'RT-build','3':'RT-static-refit'}[path],pairs=len(valid),**values))
    report=dict(scope='1024 radius-1 geometric queries on actual read-pool snapshots; static positions; full neighbour sets verified independently; no production update/physics/shaderpack/FPS qualification',periodic_rebuild_status='not measured: no valid real contiguous query sequence is available',results=results);(root/'native-summary.json').write_text(json.dumps(report,indent=2)+'\n');return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();r=summarize(a.root);print('Native geometric-query results:',len(r['results']))
