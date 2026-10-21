"""Independent final-float validation; never relax constraints for publication."""
import argparse,hashlib,json,time
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from dome_resource_v4 import COUNT,DomeResourceV4

def validate(path,step=.02):
    r=DomeResourceV4(path);m=r.metadata;minimum=100.;maximum_speed_error=maximum_acceleration=maximum_jerk=maximum_turn=0.
    closest=np.full(COUNT,np.inf);closest_xz=closest.copy();closest_time=np.zeros(COUNT);minimum_y=100.;below=np.zeros(COUNT,bool);inward=np.full(COUNT,100.)
    begin=time.time()
    for frame,t in enumerate(np.arange(0,m[:,2].max(),step)):
        ids=np.flatnonzero(m[:,2]>t);p=r.sample(ids,t);v=r.sample(ids,t,1);a=r.sample(ids,t,2);j=r.sample(ids,t,3)
        speed=np.linalg.norm(v,axis=-1);target=abs(m[ids,9])+(6-abs(m[ids,9]))*(10*min(t/.5,1)**3-15*min(t/.5,1)**4+6*min(t/.5,1)**5)
        maximum_speed_error=max(maximum_speed_error,float(np.max(abs(speed-target),initial=0)))
        maximum_acceleration=max(maximum_acceleration,float(np.linalg.norm(a,axis=-1).max(initial=0)));maximum_jerk=max(maximum_jerk,float(np.linalg.norm(j,axis=-1).max(initial=0)))
        maximum_turn=max(maximum_turn,float((np.linalg.norm(np.cross(v,a),axis=-1)/speed**2).max(initial=0)))
        gap=cKDTree(p).query(p,k=2)[0][:,1].min(initial=100);minimum=min(minimum,float(gap))
        radius=np.linalg.norm(p,axis=-1);smaller=radius<closest[ids];closest_time[ids[smaller]]=t;closest[ids]=np.minimum(closest[ids],radius);closest_xz[ids]=np.minimum(closest_xz[ids],np.linalg.norm(p[:,[0,2]],axis=-1))
        below[ids]|=p[:,1]<0;minimum_y=min(minimum_y,float(p[:,1].min(initial=100)))
        if abs(t-.5)<step*.1:inward[ids]=-np.sum(p*v,axis=-1)/radius
        if frame%200==0:print('validate',t,'minimum',minimum,'acceleration',maximum_acceleration,'jerk',maximum_jerk,'seconds',time.time()-begin,flush=True)
    core=r.core(np.arange(COUNT));initial=r.gather(np.arange(COUNT),0)
    from dome_resource_v4 import clock_distance
    ids=np.arange(COUNT);arc=clock_distance(closest_time,abs(m[:,9]));lo=np.maximum(0,arc-step*6.02);hi=np.minimum(m[:,11],arc+step*6.02)
    for _ in range(8):
        p=r.sample_arc(ids,arc);v=r.sample_arc(ids,arc,1);a=r.sample_arc(ids,arc,2)
        gradient=np.sum(p*v,axis=-1);curvature=np.sum(v*v+p*a,axis=-1)
        arc=np.clip(arc-gradient/np.maximum(curvature,.001),lo,hi)
    near=r.sample_arc(ids,arc);tangent=r.sample_arc(ids,arc,1);tangent/=np.linalg.norm(tangent,axis=-1)[:,None]
    normals=m[:,13:16];assert np.all(np.abs(np.linalg.norm(normals,axis=-1)-1)<.0001),"Invalid stored exit normal"
    end=r.sample_arc(ids,m[:,11]);assert np.min(np.sum(end*normals,axis=-1)-m[:,6])>=-.0001,"Stored exit plane is unreachable"
    stored_exit=m[:,6]-np.sum(near*normals,axis=-1)
    after=m[:,11]-arc;exit_plane=np.sum((end-near)*tangent,axis=-1)
    return dict(sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest(),members=COUNT,templateVersion=4,stepSeconds=step,minimumCenterSpacing=minimum,maximumSpeedError=maximum_speed_error,maximumAcceleration=maximum_acceleration,maximumJerk=maximum_jerk,maximumTurnRadiansPerSecond=maximum_turn,minimumInwardSpeedAtHalfSecond=float(inward.min()),coreChannels=int(core.sum()),maximumCoreClosestXZ=float(closest_xz[core].max()),maximumPeripheralClosest3D=float(closest[~core].max()),highestInitialY=float(initial[:,1].max()),belowPlaneChannels=int(below.sum()),minimumY=minimum_y,minimumArcAfterClosest=float(after.min()),minimumExitPlane=float(exit_plane.min()),minimumStoredExitPlaneFromClosest=float(stored_exit.min()),minimumPeripheralClosest3D=float(np.linalg.norm(near[~core],axis=-1).min()),maximumDuration=float(m[:,2].max()),assetBytes=Path(path).stat().st_size)

def require(result):
    assert result['minimumCenterSpacing']>=.50,'Reference bodies intersect'
    assert result['maximumSpeedError']<=.01,'Inverse arc speed error'
    assert result['maximumAcceleration']<=18,'Acceleration limit'
    assert result['maximumJerk']<=120,'Jerk limit'
    assert result['maximumTurnRadiansPerSecond']<=np.pi,'Turn limit'
    assert result['minimumInwardSpeedAtHalfSecond']>=3,'Delayed inward motion'
    assert result['maximumCoreClosestXZ']<=1 and result['maximumPeripheralClosest3D']<=8,'Curtain missed center'
    assert result['minimumArcAfterClosest']>=3 and result['minimumExitPlane']>=3 and result['minimumStoredExitPlaneFromClosest']>=3,'Missing exit'
    assert result['minimumPeripheralClosest3D']>=1,'Peripheral channel entered core'
    assert result['belowPlaneChannels']<=768,'Too many below-plane channels'
    assert result['highestInitialY']>=31.5 and result['maximumDuration']<=60

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('--report',default='build/dome-v14/validation.json');p.add_argument('--step',type=float,default=.02);args=p.parse_args()
    result=validate(args.asset,args.step);Path(args.report).write_text(json.dumps(result,indent=2));print(result,flush=True);require(result)
