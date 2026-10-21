"""End each curtain at its global closest centre passage plus a 3.01m exit plane.

Trimming preserves the decoded quintic exactly (including its C2 derivatives).
It cannot introduce a contact or alter the clocks of surviving curve prefixes.
"""
import argparse
from pathlib import Path
import numpy as np
from dome_resource_v4 import DomeResourceV4,coefficients,evaluate

def roots(poly):
    return [float(np.clip(v.real,0,1)) for v in np.polynomial.polynomial.polyroots(poly) if abs(v.imag)<1e-7 and -1e-7<=v.real<=1+1e-7]

def clock_at(distance,initial_speed):
    from dome_resource_v4 import clock_distance
    threshold=clock_distance(.5,initial_speed)
    if distance>=threshold:return (distance+(6-initial_speed)*.25)/6
    t=distance/initial_speed
    for _ in range(8):
        u=np.clip(t/.5,0,1);speed=initial_speed+(6-initial_speed)*(10*u**3-15*u**4+6*u**5)
        t=np.clip(t-(clock_distance(t,initial_speed)-distance)/speed,0,.5)
    return float(t)

def complete(asset,output):
    r=DomeResourceV4(asset);m=r.metadata.copy();prefix=int(m[:,0].min());all_nodes=list(r.nodes[:prefix]);cuts=0;minimum_exit=100.
    for rank,record in enumerate(m):
        base,count=int(record[0]),int(record[1]);original_length=float(record[11]);nodes=r.nodes[base:base+count];length=np.diff(nodes[:,3]);
        c=coefficients(nodes[:-1,:3],nodes[1:,:3],nodes[:-1,4:7]*length[:,None],nodes[1:,4:7]*length[:,None],nodes[:-1,8:11]*length[:,None]**2,nodes[1:,8:11]*length[:,None]**2)
        # Bernstein bounds prune segments that cannot improve the closest node.
        minima=[]
        for segment,curve in enumerate(c):
            derivative=curve[1:]*np.arange(1,6)[:,None]
            poly=sum((np.convolve(curve[:,axis],derivative[:,axis]) for axis in range(3)),np.zeros(10))
            for u in roots(poly):
                p=evaluate(curve,u);tangent=evaluate(curve,u,1)/length[segment]
                velocity=evaluate(curve,u,1);acceleration=evaluate(curve,u,2)
                if np.dot(velocity,velocity)+np.dot(p,acceleration)<=0:continue
                s=nodes[segment,3]+u*length[segment]
                if ((int(record[3])//12+(rank-int(record[3]))%(int(record[7])//12))%16)==0:valid=np.linalg.norm(p[[0,2]])<=1 and -.6<=p[1]<=1.5
                else:valid=np.linalg.norm(p)<=8
                if valid:minima.append((s,np.dot(p,p),p,tangent/np.linalg.norm(tangent)))
        if not minima:
            raise ValueError(f'No legitimate centre passage for member {rank}')
        minima.sort(key=lambda item:item[1]);chosen=None
        for s,radial,p,tangent in minima:
            start=max(0,int(np.searchsorted(nodes[:,3],s,side='right')-1))
            for segment in range(start,len(c)):
                plane=c[segment]@tangent;plane[0]-=np.dot(p,tangent)+3.01
                for u in sorted(roots(plane)):
                    cut=nodes[segment,3]+u*length[segment]
                    if cut<=s:continue
                    if np.dot(evaluate(c[segment],u,1),tangent)<=0:continue
                    if any(s<other[0]<cut and other[1]<radial-1e-7 for other in minima):continue
                    chosen=(cut,segment,u,p,tangent);break
                if chosen:break
            if chosen:break
        if chosen is None:raise ValueError(f'Incomplete exit for member {rank}')
        cut,segment,u,p,tangent=chosen
        # Keep the existing final interval intact and clip only its active arc.
        # A newly quantised very short interval would amplify coordinate rounding
        # into a large third derivative. The lookup clamps at record[11].
        window=max(0,int(np.searchsorted(nodes[:,3],float(np.float32(cut)),side='left')-1));kept=nodes[:window+2];m[rank,0]=len(all_nodes);m[rank,1]=len(kept);m[rank,2]=clock_at(cut,abs(record[9]));m[rank,11]=cut;m[rank,6]=float(np.dot(p,tangent)+3.01);m[rank,13:16]=tangent;all_nodes.extend(kept)
        minimum_exit=min(minimum_exit,float(np.dot(evaluate(c[segment],u)-p,tangent)));cuts+=cut<original_length-.00001
        if rank%1024==0:print('complete',rank,'trimmed',cuts,flush=True)
    DomeResourceV4.write(output,m,np.array(all_nodes));print('trimmed',cuts,'minimum exit plane',minimum_exit,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('output');args=p.parse_args()
    if not Path(args.output).resolve().is_relative_to(Path('build').resolve()):raise ValueError('Unverified exit edits stay in build')
    complete(args.asset,args.output)
