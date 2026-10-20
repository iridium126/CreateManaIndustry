"""Deterministic Dome v3 rings and twelve crossing curtains; offline NumPy/SciPy only.
The asset stores cubic controls and arc tables, never simulation frames.
"""
import argparse,json,math,struct
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
p=argparse.ArgumentParser();p.add_argument('--output',default='src/main/resources/data/createmanaindustry/dome-template-v3.bin');p.add_argument('--report',default='build/dome-v3-validation.json');p.add_argument('--flow-cache');p.add_argument('--native');p.add_argument('--draft',action='store_true');args=p.parse_args()
if args.draft:assert Path(args.output).resolve().is_relative_to(Path('build').resolve()),'Drafts must stay outside packaged resources'
native=None
if args.native:
 import ctypes
 from numpy.ctypeslib import ndpointer
 library=ctypes.CDLL(str(Path(args.native).resolve()))
 if not hasattr(library,'dome_bake_abi') or library.dome_bake_abi()!=2:raise RuntimeError('Rebuild the offline accelerator with build_dome_baker.ps1')
 native=library.dome_step
 native.argtypes=[ctypes.c_int,ctypes.c_int,ndpointer(np.int64,flags='C_CONTIGUOUS'),*[ndpointer(np.float64,flags='C_CONTIGUOUS')]*5,ctypes.c_double,ctypes.c_double];native.restype=ctypes.c_double
N,ARC,STRIDE=12288,65,80
rings=[]
for j in range(24):
 y=.75+j*1.4
 for i in range(24):
  rho=.9+i*1.4;capacity=int(2*math.pi*rho/1.5/12)*12
  if 16.05<=math.hypot(rho,y)<=31.98 and capacity:rings.append([rho,y,capacity,0])
total=sum(r[2] for r in rings)
for r in rings:r[3]=max(12,int(r[2]*N/total/12)*12)
while sum(r[3] for r in rings)<N:max(rings,key=lambda r:r[2]*N/total-r[3])[3]+=12
assert sum(r[3] for r in rings)==N
metadata=[];curves=[];starts=[];core=[];sector_rank=np.zeros(12,dtype=int)
for rho,y,capacity,count in rings:
 if y>31.5 and rho<4:rho=1.32
 base=len(metadata)
 for k in range(count):
  theta=2*math.pi*k/count;sector=k*12//count;local=int(sector_rank[sector]);sector_rank[sector]+=1;is_core=local%16==0
  angle=theta+(.16 if sector%2 else -.16);direction=np.array([-math.cos(angle),0.,-math.sin(angle)]);side=np.array([-direction[2],0.,direction[0]])
  a=(local*.618033988749895)%1;b=(local*.414213562373095+sector*.137)%1
  center=side*((a-.5)*.5 if is_core else (2.2+4.2*a)*(1 if local&1 else -1));center[1]=.15+1.3*b if is_core else .25+(math.sqrt(6.8**2-np.dot(center,center))-.25)*b
  p0=np.array([rho*math.cos(theta),y,rho*math.sin(theta)]);tangent=np.array([-math.sin(theta),0.,math.cos(theta)])
  p1=p0+tangent*1.1;p2=center-direction*(3+.5*(sector%2));controls=np.array([p0,p1,p2,center,center+direction*3])
  u=np.linspace(0,1,4097)[:,None];line=(1-u)**3*p0+3*(1-u)**2*u*p1+3*(1-u)*u*u*p2+u**3*center
  distance=np.r_[0,np.cumsum(np.linalg.norm(np.diff(line,axis=0),axis=1))];arc=np.interp(np.linspace(0,1,ARC),u[:,0],distance);duration=(distance[-1]+3)/6
  metadata.append([len(curves)*STRIDE//2,STRIDE//2,duration,base,rho,y,theta,count]);curves.append(np.r_[controls.ravel(),arc]);starts.append(p0);core.append(is_core)
metadata=np.array(metadata,dtype='>f4');curves=np.array(curves,dtype='>f4');starts=np.array(starts);noncore=np.flatnonzero(~np.array(core))
assert curves.shape==(N,STRIDE) and np.all(sector_rank==1024) and sum(core)==768
minimum=float(cKDTree(starts).query(starts,k=2)[0][:,1].min());assert minimum>=.68 and starts[:,1].max()>=31.5

# Offline body-aware flow on a fine common clock before curve encoding.
STEP=.01;DISTANCE=6*STEP;CLEARANCE=.65
# A projection changes direction, not member size; no solver runs on the server.
import time
from scipy.interpolate import CubicHermiteSpline
if args.flow_cache:
 saved=np.load(args.flow_cache);paths=saved['paths'];ends=saved['ends'];STEP=float(saved['step']) if 'step' in saved else round(float(np.median(np.linalg.norm(paths[1]-paths[0],axis=1)))/6,3)
 assert np.max(np.linalg.norm(paths[0]-starts,axis=1))<.002,'Cache belongs to a different gather template'
 assert STEP>0 and STEP<=.05
 minimum=min(cKDTree(paths[f,np.flatnonzero(ends>=f)]).query(paths[f,np.flatnonzero(ends>=f)],k=2)[0][:,1].min(initial=10) for f in range(len(paths)))
else:
 def project_step(q,old):
  move=q-old;move*= (DISTANCE/np.maximum(np.linalg.norm(move,axis=1),1e-9))[:,None]
  direction=move/DISTANCE;lower=np.clip((floors-old[:,1])/DISTANCE,-1,1);adjust=direction[:,1]<lower
  horizontal=direction[adjust][:,[0,2]];length=np.linalg.norm(horizontal,axis=1);horizontal[length<1e-9]=[1.,0.];horizontal/=np.maximum(np.linalg.norm(horizontal,axis=1)[:,None],1e-9)
  direction[adjust,0]=horizontal[:,0]*np.sqrt(1-lower[adjust]**2);direction[adjust,2]=horizontal[:,1]*np.sqrt(1-lower[adjust]**2);direction[adjust,1]=lower[adjust]
  return old+direction*DISTANCE
 controls=curves[:,:15].astype(float).reshape(-1,5,3);pos=starts.copy();velocity=controls[:,1]-pos;velocity*=6/np.linalg.norm(velocity,axis=1)[:,None]
 goal=controls[:,3];direction=(controls[:,4]-goal)/3;alive=np.ones(N,bool);passed=np.zeros(N,bool);after=np.zeros(N);exit_origin=pos.copy();nearest=np.sum(pos*pos,axis=1);ends=np.full(N,6000);paths=[pos.copy()];begin=time.time();minimum=10
 for frame in range(6000):
  ids=np.flatnonzero(alive);old=pos[ids].copy();floors=np.where(np.array(core)[ids],-11.,.15);dv=goal[ids]+direction[ids]*.3-old;go=np.where(passed[ids,None],direction[ids],dv/np.maximum(np.linalg.norm(dv,axis=1)[:,None],1e-9));blend=min(1,(frame+1)*STEP/.4)
  desired=(1-blend)*velocity[ids]+blend*go*6;desired[:,1]+=np.where(np.array(core)[ids]|passed[ids],0,np.clip((.9-old[:,1])*8,0,6));desired*=6/np.maximum(np.linalg.norm(desired,axis=1)[:,None],1e-9)
  if native:
   pairs=np.ascontiguousarray(cKDTree(old).query_pairs(2.8,output_type='ndarray'),dtype=np.int64);q=np.empty_like(old)
   d=np.array([native(len(ids),len(pairs),pairs,old,np.ascontiguousarray(velocity[ids]),desired,q,floors,STEP,CLEARANCE)])
  else:
   # Constant-speed reciprocal velocity barriers start before contact. Rotating
   # inside the speed sphere avoids the stop-and-renormalise compression of PBD.
   pairs=cKDTree(old).query_pairs(2.8,output_type='ndarray');a,b=pairs.T
   delta=old[a]-old[b];distance=np.linalg.norm(delta,axis=1);normal=delta/np.maximum(distance[:,None],1e-9);bound=-(distance-.58)/.18
   for iteration in range(48):
    error=bound-np.sum((desired[a]-desired[b])*normal,axis=1);bad=error>.001
    if not bad.any():break
    worst=np.zeros(len(ids));np.maximum.at(worst,a,error);np.maximum.at(worst,b,error)
    choice=np.full(len(ids),len(a));index=np.arange(len(a));np.minimum.at(choice,a,np.where((error==worst[a])&bad,index,len(a)));np.minimum.at(choice,b,np.where((error==worst[b])&bad,index,len(a)))
    members=np.flatnonzero(choice<len(a));chosen=choice[members];out=normal[chosen]*np.where(a[chosen]==members,1.,-1.)[:,None]
    radial=np.sum(desired[members]*out,axis=1);tangent=desired[members]-radial[:,None]*out;length=np.linalg.norm(tangent,axis=1);zero=length<.00001
    if zero.any():tangent[zero]=np.cross(out[zero],np.where(abs(out[zero,1,None])<.8,np.array([0.,1.,0.]),np.array([1.,0.,0.])));length=np.linalg.norm(tangent,axis=1)
    radial=np.clip(radial+error[chosen]*.55,-5.999,5.999);desired[members]=out*radial[:,None]+tangent*(np.sqrt(36-radial*radial)/np.maximum(length,1e-9))[:,None]
    lower=np.clip(-(old[:,1]-floors)/.12,-5.999,5.999);limit=desired[:,1]<lower
    horizontal=desired[limit][:,[0,2]];length=np.linalg.norm(horizontal,axis=1);horizontal[length<1e-9]=[1.,0.];horizontal/=np.maximum(np.linalg.norm(horizontal,axis=1)[:,None],1e-9)
    desired[limit,0]=horizontal[:,0]*np.sqrt(36-lower[limit]**2);desired[limit,2]=horizontal[:,1]*np.sqrt(36-lower[limit]**2);desired[limit,1]=lower[limit]
   q=project_step(old+desired*STEP,old);pairs=cKDTree(old).query_pairs(1.4,output_type='ndarray')
   for iteration in range(32):
    delta=q[pairs[:,0]]-q[pairs[:,1]];d=np.linalg.norm(delta,axis=1);bad=d<CLEARANCE
    if not bad.any():break
    a,b=pairs[bad].T;corr=delta[bad]*((CLEARANCE+.000001-d[bad])/(2*np.maximum(d[bad],1e-9)))[:,None]
    change=np.column_stack([np.bincount(a,weights=corr[:,j],minlength=len(ids))-np.bincount(b,weights=corr[:,j],minlength=len(ids)) for j in range(3)])
    q=project_step(q+change*.65,old)
  pos[ids]=q;velocity[ids]=(q-old)/STEP;core_accept=(np.linalg.norm(q[:,[0,2]],axis=1)<=.9)&(np.linalg.norm(q+np.array([0.,.1,0.]),axis=1)<=1.6)
  # Capture the actual closest-approach plane. Requiring a crowded member to
  # visit one exact goal again creates recirculating deadlocks at constant speed.
  reached=np.where(np.array(core)[ids],core_accept,np.linalg.norm(q,axis=1)<7.8)&(np.sum(q*velocity[ids],axis=1)>0)
  radius2=np.sum(q*q,axis=1);closer=radius2<nearest[ids];exit_origin[ids[closer]]=q[closer];nearest[ids]=np.minimum(nearest[ids],radius2)
  just=~passed[ids]&reached;outgoing=velocity[ids[just]]/6
  outgoing[:,1]=np.where(np.array(core)[ids[just]],outgoing[:,1],np.maximum(0,outgoing[:,1]));outgoing/=np.maximum(np.linalg.norm(outgoing,axis=1)[:,None],1e-9)
  direction[ids[just]]=outgoing;passed[ids]|=reached;after[ids]+=np.where(passed[ids]&~just,DISTANCE,0);done=passed[ids]&(np.sum((q-exit_origin[ids])*direction[ids],axis=1)>=3.08);alive[ids[done]]=False;ends[ids[done]]=frame+1;paths.append(pos.copy());minimum=min(minimum,d.min(initial=10))
  if frame%100==0:print('flow',frame,'remaining',sum(alive),'minimum',minimum,flush=True)
  if not alive.any():break
 assert not alive.any(),f'Reference flow exceeded 60 seconds: {np.flatnonzero(alive)} positions={pos[alive]}'
 paths=np.array(paths,dtype=np.float32)
 cache_output=Path('build/dome-v13')/(Path(args.output).stem+'.flow.npz');cache_output.parent.mkdir(parents=True,exist_ok=True)
 np.savez_compressed(cache_output,paths=paths,ends=ends,step=STEP)
# Adaptively fit C1 Hermite segments. Each node has position, cumulative arc,
# velocity and original parameter. Arc is integrated offline; GPU only looks up.
all_nodes=[];error=0
for rank,end in enumerate(ends):
 end=int(end);line=paths[:end+1,rank].astype(float);times=np.arange(end+1)*STEP;velocity=np.gradient(line,STEP,axis=0);theta=metadata[rank,6];velocity[0]=[-6*math.sin(theta),0,6*math.cos(theta)];keep=set(range(0,end+1,8))|{end}
 for iteration in range(20):
  indices=np.array(sorted(keep));spline=CubicHermiteSpline(times[indices],line[indices],velocity[indices]);errors=np.linalg.norm(spline(times)-line,axis=1)
  if errors.max()<=.012:break
  for a,b in zip(indices[:-1],indices[1:]):
   at=a+np.argmax(errors[a:b+1])
   if errors[at]>.012:keep.add(at)
 error=max(error,float(errors.max()));arcs=[0.]
 for a,b in zip(indices[:-1],indices[1:]):
  t=np.linspace(times[a],times[b],33);speeds=np.linalg.norm(spline(t,1),axis=1);arc=(t[-1]-t[0])/96*(speeds[0]+speeds[-1]+4*speeds[1:-1:2].sum()+2*speeds[2:-1:2].sum());arcs.append(arcs[-1]+arc)
 metadata[rank,0]=len(all_nodes)*2;metadata[rank,1]=len(indices);metadata[rank,2]=arcs[-1]/6
 all_nodes.extend(np.column_stack((line[indices],arcs,velocity[indices],times[indices])).tolist())
 if rank%3000==0:print('fit',rank,'nodes',len(all_nodes),flush=True)
metadata=metadata.astype('>f4');nodes=np.array(all_nodes,dtype=np.float32)
assert np.all(np.abs(nodes[:,:3])<40),'A curtain left its collision/encoding bounds'
position=np.rint((nodes[:,:3]+40)*65535/80).astype(np.uint32);parameter=np.rint(nodes[:,7]*1000).astype(np.uint32)
velocity=np.clip(np.rint(nodes[:,4:7]*511/6),-511,511).astype(np.int32).astype(np.uint32)&1023
packed=np.column_stack((position[:,0]|(position[:,1]<<16),position[:,2]|(parameter<<16),nodes[:,3].view(np.uint32),velocity[:,0]|(velocity[:,1]<<10)|(velocity[:,2]<<20))).astype('>u4')
# Publication uses the decoded geometry, not the pre-quantisation arc. Otherwise
# rounding each short segment introduces a per-member clock drift after baking.
decoded_position=position.astype(float)*(80./65535)-40
decoded_velocity=velocity.astype(np.int32);decoded_velocity=np.where(decoded_velocity>=512,decoded_velocity-1024,decoded_velocity)*(6./511)
gauss_x,gauss_w=np.polynomial.legendre.leggauss(8)
for rank in range(N):
 base=int(metadata[rank,0]/2);size=int(metadata[rank,1]);take=slice(base,base+size)
 points=decoded_position[take];velocities=decoded_velocity[take];times=parameter[take].astype(float)*.001;dt=np.diff(times)[:,None];v,w=velocities[:-1]*dt,velocities[1:]*dt;p0,p1=points[:-1],points[1:];length=np.zeros(size-1)
 for x,weight in zip(gauss_x,gauss_w):
  u=(x+1)*.5;derivative=(6*u*u-6*u)*p0+(-6*u*u+6*u)*p1+(3*u*u-4*u+1)*v+(3*u*u-2*u)*w;length+=weight*.5*np.linalg.norm(derivative,axis=1)
 arc=np.r_[0,np.cumsum(length)].astype(np.float32);packed[take,2]=arc.view(np.uint32);metadata[rank,2]=arc[-1]/6
asset=struct.pack('>4i',3,N,len(all_nodes)*2,2)+metadata.tobytes()+packed.tobytes();out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True)
core_closest=np.min(np.linalg.norm(paths[:,np.array(core)][:,:,[0,2]],axis=2),axis=0)
assert core_closest.max()<=1.001
peripheral_closest=np.linalg.norm(paths[:,noncore],axis=2).min(axis=0)
assert peripheral_closest.max()<8,peripheral_closest.max()
print('limits',metadata[:,1].max(),metadata[:,2].max(),flush=True)
assert metadata[:,1].max()<=4096 and metadata[:,2].max()<=60
assert np.all(position<=65535) and parameter.max()<=60000
temporary=out.with_suffix('.tmp');temporary.write_bytes(asset)
proof=None
if not args.draft:
 from bake_dome_contacts import bake
 from complete_dome_exits import complete
 from dome_resource import DomeResource
 for cycle in range(3):
  complete(temporary,temporary);bake(temporary,temporary)
  exits=DomeResource(temporary)
  if np.min(exits.metadata[:,2]*6-exits.closest_arcs())>=2.999:break
 else:raise RuntimeError('Exit/contact constraints did not converge; resource not published')
 from validate_dome_template import validate
 proof=validate(temporary,.01);print('encoded',proof,flush=True)
 assert proof['continuousSpacingLowerBound']>=.34,'Final decoded trajectories are not separated'
 assert proof['maximumCoreClosestXZ']<=1.06 and proof['maximumPeripheralClosest3D']<8.06
 assert proof['minimumInwardSpeedAtHalfSecond']>=3,'Curtain entry does not close inward soon enough'
 assert proof['belowPlaneChannels']<=768,'Too many peripheral channels moved below the centre plane'
temporary.replace(out)
if proof is not None:out.with_suffix('.validation.json').write_text(json.dumps(proof,indent=2))
report=dict(version=3,members=N,rings=len(rings),curtains=12,channelsPerCurtain=1024,corePerCurtain=64,assetBytes=out.stat().st_size,rawMaxSeconds=float(metadata[:,2].max()),initialMinimum=float(cKDTree(starts).query(starts,k=2)[0][:,1].min()),rawFlowMinimum=float(minimum),maxY=float(starts[:,1].max()),rawNodes=len(all_nodes),rawMaxNodes=int(metadata[:,1].max()),fitError=error,rawCoreMaximumClosestRadius=float(core_closest.max()),rawPeripheralMaximumClosestRadius=float(peripheral_closest.max()),rawBelowPlaneChannels=int(np.count_nonzero(paths[:,:,1].min(axis=0)<0)),rawMinimumY=float(paths[:,:,1].min()),encoded=proof)
Path(args.report).parent.mkdir(parents=True,exist_ok=True);Path(args.report).write_text(json.dumps(report,indent=2));print(report)
