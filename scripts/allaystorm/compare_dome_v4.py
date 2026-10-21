"""Deterministic decoded-reference turning comparison, not a game FPS test."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from dome_resource import DomeResource
from dome_resource_v4 import DomeResourceV4

def measure(resource,ids,frames):
    duration=resource.metadata[:,2];angles=[];dt=1/60
    for t in np.linspace(0,float(duration.max())-2*dt,frames):
        active=ids[duration[ids]>t+2*dt]
        if not len(active):continue
        p,q,r=(resource.sample(active,t+k*dt) for k in range(3));a=q-p;b=r-q
        na=np.linalg.norm(a,axis=1);nb=np.linalg.norm(b,axis=1);valid=(na>.00001)&(nb>.00001)
        cosine=np.sum(a[valid]*b[valid],axis=1)/(na[valid]*nb[valid]);angles.extend(np.degrees(np.arccos(np.clip(cosine,-1,1))))
    a=np.asarray(angles)
    return dict(samples=len(a),turnOver45Degrees=int(np.sum(a>45)),turnOver45Percent=float(np.mean(a>45)*100),angleDegreesP95=float(np.quantile(a,.95)),angleDegreesP99=float(np.quantile(a,.99)),maximumAngleDegrees=float(a.max()))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--old',required=True);p.add_argument('--new',required=True);p.add_argument('--report',default='build/dome-v14/reference-turning.json');a=p.parse_args()
    ids=np.sort(np.random.default_rng(14).choice(12288,2048,replace=False));report=dict(seed=14,members=2048,temporalSamples=128,frameIntervalSeconds=1/60,scope='Decoded canonical references only; runtime and pose are validated separately.')
    for name,path,decoder in [('v13',a.old,DomeResource),('v14',a.new,DomeResourceV4)]:
        report[name]=measure(decoder(path),ids,128);report[name]['sha256']=hashlib.sha256(Path(path).read_bytes()).hexdigest();print(name,report[name],flush=True)
    Path(a.report).write_text(json.dumps(report,indent=2)+'\n')
