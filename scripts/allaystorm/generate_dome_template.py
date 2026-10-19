"""Offline shared conveyor lanes. All members move immediately at six blocks/second."""
import numpy as np, math, struct
from scipy.spatial import cKDTree
from pathlib import Path
import argparse
parser=argparse.ArgumentParser();parser.add_argument("--exit-forward",type=float,default=20);parser.add_argument("--exit-up",type=float,default=18);parser.add_argument("--output",default="src/main/resources/data/createmanaindustry/dome-template-v1.bin");args=parser.parse_args()
spacing=1.45
patch=[]
for row in range(9):
 for col in range(9):
  x=(col-4+(row%2)*.5)*spacing;y=(row-4)*spacing*math.sqrt(3)/2
  if x*x+y*y<=5.92**2:patch.append((x,y))
# The flattened patch allows consecutive coils without closing the empty interior.
print('lanes',len(patch),flush=True)
y=np.linspace(3.1,20.0,12000);R=26.3-.10*(y-3.1);r=np.sqrt(R*R-y*y);angle=(y-3.1)*2*math.pi/6.28
coil=np.column_stack((r*np.cos(angle),y,r*np.sin(angle)))
p=coil[-1];t=(p-coil[-2]);t/=np.linalg.norm(t)
# C1 cubic exit, transported cross-section becomes horizontal at the center.
end=np.array((0.,.8,0.));c1=p+t*args.exit_forward;c2=end+np.array((0.,args.exit_up,0.))
u=np.linspace(0,1,3000)[1:,None];exit=(1-u)**3*p+3*(1-u)**2*u*c1+3*(1-u)*u*u*c2+u**3*end
center=np.concatenate((coil,exit));derivative=np.gradient(center,axis=0);tangent=derivative/np.linalg.norm(derivative,axis=1)[:,None]
normal=np.zeros_like(center);normal[0]=np.array((1.,0.,0.));normal[0]-=tangent[0]*np.dot(normal[0],tangent[0]);normal[0]/=np.linalg.norm(normal[0])
for i in range(1,len(center)):
 base=np.array((center[i,0],0.,center[i,2])) if i<len(coil) else normal[i-1]
 v=base-tangent[i]*np.dot(base,tangent[i]);normal[i]=v/np.linalg.norm(v)
binormal=np.cross(tangent,normal)
lanes=[]
for a,b in patch:
 line=center+a*normal+b*binormal
 arc=np.concatenate(([0],np.cumsum(np.linalg.norm(np.diff(line,axis=0),axis=1))))
 knot=np.linspace(0,arc[-1],1024);xyz=np.column_stack([np.interp(knot,arc,line[:,j]) for j in range(3)])
 for iteration in range(12):
  metric=np.concatenate(([0],np.cumsum(np.linalg.norm(np.diff(xyz,axis=0),axis=1))))
  knot=np.linspace(0,metric[-1],1024);xyz=np.column_stack([np.interp(knot,metric,xyz[:,j]) for j in range(3)])
 # Authoritative metric is the actual stored polyline, including its final corner intervals.
 knot=np.concatenate(([0],np.cumsum(np.linalg.norm(np.diff(xyz,axis=0),axis=1))))
 lanes.append(np.column_stack((xyz,knot)))
lanes=np.array(lanes);counts=np.full(len(lanes),8192//len(lanes));counts[:8192%len(lanes)]+=1
records=[];initial=[]
capacity=[]
for lane,(points,count) in enumerate(zip(lanes,counts)):
 a,b=patch[lane]
 total=points[-1,3];radius=np.linalg.norm(points[:,:3],axis=1);legal=(radius>=16.001)&(radius<=31.999)&(points[:,1]>=0)&(points[:,3]<=np.linalg.norm(np.diff(center[:len(coil)]+a*normal[:len(coil)]+b*binormal[:len(coil)],axis=0),axis=1).sum())
 # Preserve the complete legal shell interval.
 indices=np.where(legal)[0];lo=points[indices[0],3];hi=points[indices[-1],3]
 # Continuous legal suffix, excluding any terminal bend into the interior.
 for j in range(indices[0],indices[-1]+1):
  if not legal[j]:hi=points[j-1,3];break
 start=max(lo,total-359.5)
 capacity.append(int((hi-start)/1.455)+1)
 if lane==len(lanes)-1:print('CAPACITY',sum(capacity),capacity,flush=True)
 count=capacity[-1]
 for i in range(count):
  arc=start+i*1.455;records.append((lane,arc,total,0))
  initial.append([np.interp(arc,points[:,3],points[:,j]) for j in range(3)])
initial=np.array(initial);dist=cKDTree(initial).query(initial,k=2)[0][:,1];print('candidate initial min',dist.min(),'max duration',max((r[2]-r[1])/6 for r in records),'radius',np.linalg.norm(initial,axis=1).min(),np.linalg.norm(initial,axis=1).max(),flush=True)

# Continuous relative-segment tests, with a conservative interpolation margin.
import heapq,time,json
records=np.array(records);n=len(records);edges=set();began=time.time();step=.025
def sample(lane,arc):
 index=np.empty(len(arc),dtype=np.int32)
 for l in range(len(lanes)):
  ix=np.where(lane==l)[0];index[ix]=np.clip(np.searchsorted(lanes[l,:,3],arc[ix],side='right')-1,0,1022)
 first=lanes[lane,index];last=lanes[lane,index+1];metric=last[:,3]-first[:,3]
 point=first[:,:3]+(last[:,:3]-first[:,:3])*((arc-first[:,3])/metric)[:,None]
 velocity=(last[:,:3]-first[:,:3])*(6/metric)[:,None]
 return point,velocity,(last[:,3]-arc)/6
for frame in range(2401):
 t=frame*step;ids=np.where(records[:,1]+6*t<records[:,2])[0];p,_,_=sample(records[ids,0].astype(int),records[ids,1]+6*t)
 pairs=cKDTree(p).query_pairs(1.71,output_type='ndarray')
 if not len(pairs):continue
 a,b=ids[pairs[:,0]],ids[pairs[:,1]];until=np.minimum(step,np.minimum(records[a,2]-records[a,1]-6*t,records[b,2]-records[b,1]-6*t)/6);tau=np.zeros(len(a));hit=np.zeros(len(a),dtype=bool)
 # Split at every knot of either member: relative motion is exactly linear on each subinterval.
 for event in range(64):
  live=np.where((tau<until-1e-8)&~hit)[0]
  if not len(live):break
  aa,bb=a[live],b[live];at=t+tau[live]
  pa,va,ta=sample(records[aa,0].astype(int),records[aa,1]+6*at+1e-9);pb,vb,tb=sample(records[bb,0].astype(int),records[bb,1]+6*at+1e-9)
  dt=np.minimum(until[live]-tau[live],np.maximum(1e-8,np.minimum(ta,tb)));start=pa-pb;delta=(va-vb)*dt[:,None]
  u=np.clip(-np.einsum('ij,ij->i',start,delta)/np.maximum(np.einsum('ij,ij->i',delta,delta),1e-12),0,1);distance=np.linalg.norm(start+delta*u[:,None],axis=1)
  hit[live[distance<1.4005]]=True;tau[live]+=dt
 else:raise RuntimeError('Unresolved lane metric interval')
 edges.update(zip(a[hit],b[hit]))
links=[set() for _ in range(n)]
for a,b in edges:links[a].add(b);links[b].add(a)
alive=np.ones(n,dtype=bool);heap=[(-len(v),i) for i,v in enumerate(links)];heapq.heapify(heap)
while heap:
 neg,i=heapq.heappop(heap)
 if not alive[i] or -neg!=len(links[i]):continue
 if not links[i]:break
 alive[i]=False
 for j in list(links[i]):links[j].discard(i);heapq.heappush(heap,(-len(links[j]),j))
 links[i].clear()
indices=np.where(alive)[0];assert len(indices)>=8192,len(indices)
# Keep the complete volume rather than truncating a spatial end of the template.
indices=indices[np.linspace(0,len(indices)-1,8192,dtype=int)];chosen=records[indices].astype('>f4');chosenInitial=initial[indices]
assert cKDTree(chosenInitial).query(chosenInitial,k=2)[0][:,1].min()>=1.4
assert np.min(chosenInitial[:,1])>=0 and np.min(np.linalg.norm(chosenInitial,axis=1))>=16 and np.max(np.linalg.norm(chosenInitial,axis=1))<=32
assert np.max((chosen[:,2]-chosen[:,1])/6)<=60
endpoints=lanes[:,-1,:3];assert np.max(np.linalg.norm(endpoints,axis=1))<6
# Unused prefix knots may lie below the center; active paths start inside the legal hemisphere.
out=Path(args.output);out.write_bytes(struct.pack('>iiii',1,8192,len(lanes),1024)+chosen.tobytes()+lanes.astype('>f4').tobytes())
report=dict(members=8192,lanes=len(lanes),knots=1024,survivors=len(np.where(alive)[0]),minimumReferenceSpacing=1.4,interpolationMargin=.0005,intervalSeconds=step,maxSeconds=float(np.max((chosen[:,2]-chosen[:,1])/6)),initialMinimum=float(cKDTree(chosenInitial).query(chosenInitial,k=2)[0][:,1].min()),initialRadiusMinimum=float(np.linalg.norm(chosenInitial,axis=1).min()),initialRadiusMaximum=float(np.linalg.norm(chosenInitial,axis=1).max()),assetBytes=out.stat().st_size,generationSeconds=time.time()-began)
Path('build/dome-template-validation.json').write_text(json.dumps(report,indent=2));print(report)
