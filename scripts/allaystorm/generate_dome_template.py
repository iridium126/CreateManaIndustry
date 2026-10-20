"""Deterministic offline Dome v2. NumPy/SciPy only; no trajectory generation runs in game.

A stratified HCP shell starts all 12288 models at full scale. Constant 6m/s
steps follow inward, counter-pressure spiral flow. Offline local projections
maintain spacing; compact timed polylines have a certified interpolation bound.
The game shares one immutable quantized key texture across all four major slots.
"""
import numpy as np,time,math,struct,json,argparse
parser=argparse.ArgumentParser();parser.add_argument("--output",default="src/main/resources/data/createmanaindustry/dome-template-v2.bin");parser.add_argument("--report",default="build/dome-v2-validation.json");args=parser.parse_args()
from scipy.spatial import cKDTree
from pathlib import Path
spacing=1.72;pts=[]
for k in range(24):
 y=k*spacing*math.sqrt(2/3)+.7
 for j in range(-23,24):
  for i in range(-23,24):
   x=(i+(j%2)*.5+(k%2)*.5)*spacing;z=(j*math.sqrt(3)/2+(k%2)*math.sqrt(3)/6)*spacing
   r=math.sqrt(x*x+y*y+z*z)
   if 16.05<=r<=31.98:pts.append((x,y,z))
p=np.array(pts);print('capacity',len(p),flush=True)
rng=np.random.default_rng(1824);p=p[rng.choice(len(p),12288,replace=False)];n=len(p);paths=[p.copy()];alive=np.ones(n,bool);ends=np.full(n,1200);beg=time.time();minimum=10.;maxit=0
for frame in range(1200):
 ids=np.where(alive)[0];old=p[ids];r=np.linalg.norm(old,axis=1);radial=old/r[:,None];axis=np.column_stack((-old[:,2],np.zeros(len(ids)),old[:,0]));axis[np.linalg.norm(axis,axis=1)<1e-8]=[1,0,0];axis/=np.linalg.norm(axis,axis=1)[:,None]
 vr=np.minimum(5.9,np.maximum(240/r**2,max(0,(frame*.05-40)/4)));v=-radial*vr[:,None]+axis*np.sqrt(36-vr*vr)[:,None];desired=old+v*.05;q=desired.copy();pairs=cKDTree(old).query_pairs(2.1,output_type='ndarray')
 for iteration in range(100):
  delta=q[pairs[:,0]]-q[pairs[:,1]];d=np.linalg.norm(delta,axis=1);bad=d<1.46
  if not bad.any():break
  a,b=pairs[bad].T;corr=delta[bad]*((1.46001-d[bad])/(2*np.maximum(d[bad],1e-9)))[:,None]
  change=np.column_stack([np.bincount(a,weights=corr[:,j],minlength=len(ids))-np.bincount(b,weights=corr[:,j],minlength=len(ids)) for j in range(3)])
  q+=change;move=q-old;length=np.linalg.norm(move,axis=1);q=old+move*(.3/np.maximum(length,1e-9))[:,None]
  floor=q[:,1]<.1
  if floor.any():
   delta=q[floor]-old[floor];delta[:,1]=.1-old[floor,1];horizontal=np.linalg.norm(delta[:,[0,2]],axis=1);delta[:,[0,2]]*=np.sqrt(np.maximum(0,.09-delta[:,1]**2))[:,None]/np.maximum(horizontal[:,None],1e-9);q[floor]=old[floor]+delta
 else:

  if d.min()<1.445:print('FAILED',frame,'min',d.min(),'pairs',bad.sum(),flush=True);break
 maxit=max(maxit,iteration);minimum=min(minimum,float(d.min(initial=10.)))
 p[ids]=q;end=np.linalg.norm(q,axis=1)<5.9;alive[ids[end]]=False;ends[ids[end]]=frame+1;paths.append(p.copy())
 if frame%100==0:print('frame',frame,'alive',sum(alive),'minimum',minimum,'maxit',maxit,'seconds',time.time()-beg,flush=True)
 if not alive.any():break
assert not alive.any(), 'Dome reference paths exceeded 60 seconds'
paths=np.array(paths,dtype=np.float32)
# Independent spatial verification includes both endpoints of every active segment.
# Outside the 2.1m broad phase, a pair cannot become close in one 0.3m step.
minimum=100.;max_step=0.
for f in range(len(paths)):
 ids=np.where(ends>=f)[0]
 if len(ids)>1:minimum=min(minimum,float(cKDTree(paths[f,ids]).query(paths[f,ids],k=2)[0][:,1].min()))
 if f:max_step=max(max_step,float(np.linalg.norm(paths[f,ids]-paths[f-1,ids],axis=1).max(initial=0)))
assert np.min(paths[:,:,1])>=0 and np.max(np.abs(paths))<40
assert max_step<=.30001

print('END',frame,'alive',sum(alive),'minimum',minimum,'maxit',maxit,flush=True)

records=[];keys=[];error=0;beg=time.time()
for rank,end in enumerate(ends):
 line=paths[:end+1,rank].astype(np.float64);keep={0,int(end)};todo=[(0,int(end))]
 while todo:
  a,b=todo.pop()
  if b-a<=1:continue
  u=np.arange(1,b-a)/(b-a);delta=line[a+1:b]-(line[a]+u[:,None]*(line[b]-line[a]));dist=np.linalg.norm(delta,axis=1);i=int(np.argmax(dist))
  if dist[i]>.003:at=a+i+1;keep.add(at);todo.extend(((a,at),(at,b)))
 keep=np.array(sorted(keep));q=np.rint((line[keep]+40)*65535/80).astype(np.uint32);t=keep.astype(np.uint32)*50
 restored=q*80/65535-40
 for a,b in zip(range(len(keep)-1),range(1,len(keep))):
  l,r=keep[a],keep[b];u=np.arange(r-l+1)/(r-l);at=restored[a]+u[:,None]*(restored[b]-restored[a]);error=max(error,float(np.linalg.norm(at-line[l:r+1],axis=1).max()))
 records.append((len(keys),len(keep),float(end)*.05,0));keys.extend(zip(q[:,0]|(q[:,1]<<16),q[:,2]|(t<<16)))
 if rank%2000==0:print(rank,len(keys),error,'secs',time.time()-beg,flush=True)
records=np.array(records,dtype='>f4');keys=np.array(keys,dtype='>u4');out=Path(args.output);asset=struct.pack('>4i',2,12288,len(keys),2)+records.tobytes()+keys.tobytes()
initial=paths[0];rad=np.linalg.norm(initial,axis=1);report=dict(members=12288,keyCount=len(keys),maxKeys=int(records[:,1].max()),assetBytes=len(asset),maxSeconds=float(ends.max()*.05),initialMinimum=float(cKDTree(initial).query(initial,k=2)[0][:,1].min()),maxY=float(initial[:,1].max()),polarBins=np.histogram(initial[:,1]/rad,bins=np.linspace(0,1,11))[0].tolist(),volumeBins=np.histogram(rad**3,bins=np.linspace(16**3,32**3,5))[0].tolist(),maxQuantizedInterpolationError=error,minimumNodeSpacing=minimum,continuousSpacingLowerBound=math.sqrt(minimum**2-max_step**2)-2*error,initialInwardFraction=float(np.mean(np.linalg.norm(paths[20],axis=1)<rad)))
assert report['continuousSpacingLowerBound']>=1.4
assert report['maxY']>=31.5
assert report['maxKeys']<=2048
temporary=out.with_suffix('.tmp');temporary.write_bytes(asset);temporary.replace(out)
Path(args.report).write_text(json.dumps(report,indent=2));print(report)
