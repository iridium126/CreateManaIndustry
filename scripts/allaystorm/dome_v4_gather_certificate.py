"""Phase-independent clearance of the final decoded closed gather streamlines."""
import argparse,json,math
from pathlib import Path
import numpy as np
from dome_resource_v4 import DomeResourceV4,coefficients
from dome_v4_certificate import bernstein_matrix,norm_squared,scalar_bound,derivative,product


def certify(path):
 r=DomeResourceV4(path);ids=np.unique(r.metadata[:,3]).astype(int);bounds=[];same=100.;far=100.;min_speed=100.;max_speed=0.;max_displacement=0.
 for rank in ids:
  m=r.metadata[rank];base,count=int(m[8]),int(m[12]);n=r.nodes[base:base+count];d=np.diff(n[:,3]);c=coefficients(n[:-1,:3],n[1:,:3],n[:-1,4:7]*d[:,None],n[1:,4:7]*d[:,None],n[:-1,8:11]*d[:,None]**2,n[1:,8:11]*d[:,None]**2)
  controls=np.einsum('nkd,kj->njd',c,bernstein_matrix(5));lo=controls.min(axis=(0,1));hi=controls.max(axis=(0,1))
  # Certify derivative bounds over complete decoded intervals, then use
  # them to bound the gaps between radial samples, not just sampled positions.
  velocity=derivative(c,1,d);acceleration=derivative(c,2,d)
  vmax=math.sqrt(scalar_bound(norm_squared(velocity),1.001,'gather speed'))
  vmin=math.sqrt(-scalar_bound(-norm_squared(velocity),-.999,'gather minimum speed'))
  curvature=math.sqrt(scalar_bound(norm_squared(acceleration),100.,'gather curvature'))/(vmin*vmin)
  angular=product(c[:,:,0],velocity[:,:,2])-product(c[:,:,2],velocity[:,:,0])
  cross_min=-scalar_bound(-angular,-.00001,'gather winding')
  t=np.linspace(0,m[10],4097);p=r.lookup(np.full(len(t),base),np.full(len(t),count),t);rad=np.linalg.norm(p[:,[0,2]],axis=-1)
  h=m[10]/4096/2;radial_margin=vmax*h+1e-8;rmin=float(rad.min()-radial_margin);rmax=float(rad.max()+radial_margin)
  bounds.append((rmin,rmax,float(lo[1])-1e-8,float(hi[1])+1e-8))
  if not np.array_equal(n[0,:3],n[-1,:3]) or not np.array_equal(n[0,4:7],n[-1,4:7]) or not np.array_equal(n[0,8:11],n[-1,8:11]):raise ValueError('Gather seam is not C2 closed')
  if vmax*m[10]/rmin>=4*math.pi:raise ValueError('Uncertified gather winding count')
  arc=m[10]/m[7];minimum_arc=vmin*arc
  if curvature*minimum_arc>=math.pi:raise ValueError('Schur interval exceeds half turn')
  chord=2/curvature*math.sin(curvature*minimum_arc/2);same=min(same,chord-1e-7)
  # The nearest cyclic neighbours use the curvature bound above. Every other
  # pair is separated by at least two arc intervals in both winding directions.
  theta_min=(cross_min/(rmax*rmax))*2*arc
  far=min(far,2*rmin*math.sin(min(theta_min,math.pi)/2)-1e-7)
  min_speed=min(min_speed,abs(m[9])*vmin);max_speed=max(max_speed,abs(m[9])*vmax)
  maximum_radial=max(abs(rmin-m[4]),abs(rmax-m[4]));maximum_vertical=max(abs(lo[1]-m[5]),abs(hi[1]-m[5]));max_displacement=max(max_displacement,math.hypot(maximum_radial,maximum_vertical))

 bounds=np.array(bounds);cross=100.
 for i in range(len(bounds)):
  for j in range(i):
   a,b=bounds[i],bounds[j];dr=max(0,a[0]-b[1],b[0]-a[1]);dy=max(0,a[2]-b[3],b[2]-a[3]);cross=min(cross,math.hypot(dr,dy))
 if max_displacement>.6 or min_speed<3.999 or max_speed>6.001:raise ValueError('Gather displacement or speed limit')
 if min(same,far,cross)<.5:raise ValueError(f'Closed streamline clearance failed: same={same}, nonadjacent={far}, cross={cross}')
 return dict(closedStreamlines=len(ids),gatherSameFlowClearance=same,gatherCrossFlowClearance=cross,gatherNonAdjacentClearance=far,minimumGatherSpeed=min_speed,maximumGatherSpeed=max_speed,maximumGatherCircleDisplacement=max_displacement)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('--report',default='build/dome-v14/gather-certificate.json');args=p.parse_args();result=certify(args.asset);Path(args.report).write_text(json.dumps(result,indent=2));print(result,flush=True)
