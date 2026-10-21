"""Encode smooth v4 controls as C2 inverse-arc tables; publication is fail-closed.

An optimisation checkpoint is an offline build input, never a Minecraft resource.
The independent validator reads back the encoded floats before publication.
"""
import argparse,json,hashlib,math,time
from pathlib import Path
import numpy as np
from scipy.interpolate import BSpline,PchipInterpolator
from scipy.integrate import cumulative_simpson
from dome_flow_v4 import streamlines
from dome_resource_v4 import COUNT,RECORD,DomeResourceV4,coefficients,evaluate

def decode_controls(planes):
    """Lossless byte-plane/XOR authoring storage; not a runtime codec."""
    planes=np.asarray(planes,dtype=np.uint8)
    if planes.ndim!=4 or planes.shape[:2]!=(4,3):raise ValueError('Invalid control byte planes')
    words=planes.transpose(2,3,1,0).copy().view('<u4')[...,0]
    return np.bitwise_xor.accumulate(words,axis=0).astype('<u4').view('<f4')

def encode(checkpoint,output):
    with np.load(checkpoint) as archive:
        saved={key:archive[key] for key in archive.files}
    if 'control_xor_planes' in saved:saved['controls']=decode_controls(saved.pop('control_xor_planes'))
    old=saved['metadata'];orbits=streamlines(old)
    metadata=np.zeros((COUNT,RECORD));nodes=[];orbit_records={};begin=time.time()
    for base,orbit in orbits:
        fraction=np.linspace(0,1,129);p,v,a=orbit.sample(fraction)
        p[-1],v[-1],a[-1]=p[0],v[0],a[0]
        data=np.zeros((len(fraction),12));data[:,:3]=p;data[:,3]=fraction*orbit.length;data[:,4:7]=v/orbit.length;data[:,8:11]=a/orbit.length**2
        orbit_records[base]=(len(nodes),len(data),orbit);nodes.extend(data)
    worst_error=0.;worst_speed_error=0.
    for rank in range(COUNT):
        spline=BSpline(saved['knots'],saved['controls'][:,rank].astype(float),5)
        end=float(saved['ends'][rank]*saved['dt']);time_grid=np.linspace(0,end,int(math.ceil(end/.005))+1)
        velocity=spline(time_grid,1);speed=np.linalg.norm(velocity,axis=-1)
        if speed.min()<1:raise ValueError(f'Singular source curve {rank}: {speed.min()}')
        # PCHIP provides a seed, not the arc oracle: its derivative is not
        # exactly 1/speed. Refining against it would invent tiny oscillations.
        # CPU quadrature/inversion is permitted here; none reaches the shaders.
        gx,gw=np.polynomial.legendre.leggauss(8);span=np.diff(time_grid)
        lengths=np.zeros(len(span))
        for g,weight in zip(gx,gw):lengths+=weight*.5*span*np.linalg.norm(spline(time_grid[:-1]+span*(g+1)*.5,1),axis=-1)
        arc=np.r_[0,np.cumsum(lengths)];length=arc[-1];inverse=PchipInterpolator(arc,time_grid)
        def partial(t,index):
            start=time_grid[index];width=t-start;result=np.zeros_like(width)
            for g,weight in zip(gx,gw):result+=weight*.5*width*np.linalg.norm(spline(start+width*(g+1)*.5,1),axis=-1)
            return result
        def distance_at_time(t):
            index=np.clip(np.searchsorted(time_grid,t,side='right')-1,0,len(time_grid)-2)
            return arc[index]+partial(t,index)
        def at(distances):
            index=np.clip(np.searchsorted(arc,distances,side='right')-1,0,len(arc)-2);t=inverse(distances)
            for _ in range(1):
                error=arc[index]+partial(t,index)-distances
                t=np.clip(t-error/np.linalg.norm(spline(t,1),axis=-1),time_grid[index],time_grid[index+1])
            p=spline(t);v=spline(t,1);a=spline(t,2);norm=np.linalg.norm(v,axis=-1);tangent=v/norm[...,None]
            curvature=(a-tangent*np.sum(tangent*a,axis=-1)[...,None])/norm[...,None]**2
            return p,tangent,curvature,t
        # Keep every C2 source seam as an inverse-table seam. Interpolating
        # across a legitimate one-sided jerk change can otherwise overshoot it.
        source_knots=np.unique(saved['knots']);source_knots=source_knots[(source_knots>.001)&(source_knots<end-.001)]
        distances=np.r_[0,distance_at_time(source_knots),length]
        for iteration in range(12):
            p,v,a,t=at(distances);d=np.diff(distances);c=coefficients(p[:-1],p[1:],v[:-1]*d[:,None],v[1:]*d[:,None],a[:-1]*d[:,None]**2,a[1:]*d[:,None]**2)
            fail=np.zeros(len(d),bool);fit_error=fit_speed_error=0.
            u=np.broadcast_to(np.linspace(.05,.95,19),(len(d),19))
            oracle=at(distances[:-1,None]+d[:,None]*u)[0]
            error=np.linalg.norm(evaluate(c[:,None],u)-oracle,axis=-1)
            rate=np.abs(np.linalg.norm(evaluate(c[:,None],u,1)/d[:,None,None],axis=-1)-1)
            fit_error=float(error.max());fit_speed_error=float(rate.max())
            fail=np.any((error>.00035)|(rate>.00015),axis=1)
            if not fail.any():
                worst_error=max(worst_error,fit_error);worst_speed_error=max(worst_speed_error,fit_speed_error);break
            distances=np.sort(np.r_[distances,(distances[:-1]+distances[1:])[fail]*.5])
        else:raise ValueError(f'Inverse arc table did not converge: {rank}')
        data=np.zeros((len(distances),12));data[:,:3]=p;data[:,3]=distances;data[:,4:7]=v;data[:,7]=t;data[:,8:11]=a
        base=int(old[rank,3]);orbit_base,orbit_count,orbit=orbit_records[base]
        count=int(old[rank,7]);core=(base//12+(rank-base)%(count//12))%16==0
        metadata[rank]=[len(nodes),len(data),length/6+(6-orbit.speed)/24,base,orbit.rho,orbit.height,(rank-base)/count,count,orbit_base,orbit.speed*orbit.direction,orbit.length,length,orbit_count,float(core),(rank-base)*12//count,0]
        nodes.extend(data)
        if rank%1024==0:print('encode',rank,'nodes',len(nodes),'seconds',time.time()-begin,flush=True)
    DomeResourceV4.write(output,metadata,np.array(nodes))
    return dict(bytes=Path(output).stat().st_size,sha256=hashlib.sha256(Path(output).read_bytes()).hexdigest(),nodes=len(nodes),maximumFitError=worst_error,maximumInverseArcSpeedError=worst_speed_error)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--checkpoint',required=True);parser.add_argument('--output',default='build/dome-v14/candidate.bin');args=parser.parse_args()
    if not Path(args.output).resolve().is_relative_to(Path('build').resolve()):raise ValueError('Draft curves must stay outside packaged resources')
    result=encode(args.checkpoint,args.output);Path(args.output+'.encoding.json').write_text(json.dumps(result,indent=2));print(result,flush=True)
