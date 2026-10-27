"""Independent CPU escape-test silhouette for real-c, w=0 quaternion Julia slices.

For real c, a quaternion's imaginary direction is invariant (up to sign), so its
orbit is isomorphic to the complex orbit (real(q), norm(imag(q))). Revolving the
complex filled Julia set about its real axis gives the 3D slice. No shape edit.
"""
import csv
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ROOT=Path('build/julia-offline')
rows=list(csv.DictReader((ROOT/'candidates.csv').open()))
reports=[]
for index in (12,49,51):
    entry=rows[index]; c=float(entry['c_real'])
    assert entry['power']=='2' and all(float(entry[k])==0 for k in ('c_i','c_j','c_k','slice_w','roll_radians'))
    gpu=np.asarray(Image.open(ROOT/f'render/{index:03d}-mask192.png'))[:,:,3]>0
    n=gpu.shape[0]; scale=1.85
    xs=(2*(np.arange(n)+.5)-n)/n*scale
    radial=np.linspace(0,scale,4097)
    x,r=np.meshgrid(xs,radial)
    z=(x+1j*r).ravel(); active=np.arange(z.size); inside=np.zeros(z.size,dtype=bool)
    fixed=(1-np.sqrt(1-4*c))/2
    invariant_radius=(1-2*abs(fixed))*.999
    assert invariant_radius>0
    for it in range(192):
        escaped=np.abs(z)>16
        trapped=np.abs(z-fixed)<invariant_radius
        inside[active[trapped]]=True
        remaining=~(escaped|trapped)
        active=active[remaining];z=z[remaining]
        if len(active)==0:break
        z=z*z+c
    inside[active[np.abs(z)<=16]]=True
    bounded=inside.reshape(r.shape)
    maxima=np.where(bounded,r,0).max(axis=0)
    ys=(2*(np.arange(n)+.5)-n)/n*scale
    exact=np.abs(ys[:,None])<=maxima[None,:]
    nonempty=maxima>0
    exact[:,~nonempty]=False
    expected_low=np.argmax(exact,axis=0)
    gpu_low=np.argmax(gpu,axis=0)
    expected_high=n-1-np.argmax(exact[::-1],axis=0)
    gpu_high=n-1-np.argmax(gpu[::-1],axis=0)
    common=exact.any(axis=0)&gpu.any(axis=0)
    err=np.maximum(abs(expected_low-gpu_low),abs(expected_high-gpu_high))[common]
    report={'id':index,'c':c,'power':2,'slice_w':0,'cpu_iterations':192,'radial_samples':len(radial),
            'gpu_epsilon':.00035,'silhouette_iou':float((gpu&exact).sum()/(gpu|exact).sum()),
            'max_column_edge_error_pixels':int(err.max()),'p99_column_edge_error_pixels':float(np.quantile(err,.99)),
            'gpu_pixels':int(gpu.sum()),'cpu_pixels':int(exact.sum()),
            'cpu_closed_form_escape_trap_radius':float(invariant_radius)}
    print(json.dumps(report),flush=True)
    # Comparison shows the independently generated masks, not a new silhouette for the artwork.
    image=np.zeros((n,n*3,3),dtype=np.uint8)
    image[:,:n]=gpu[:,:,None]*255;image[:,n:2*n]=exact[:,:,None]*255
    image[:,2*n:]=np.stack((gpu&~exact,exact&~gpu,gpu&exact),axis=-1)*255
    Image.fromarray(image).save(ROOT/f'render/{index:03d}-independent-masks.png')
    reports.append(report)
(ROOT/'verification.json').write_text(json.dumps(reports,indent=2),encoding='utf-8')
assert all(v['silhouette_iou']>.99 and v['p99_column_edge_error_pixels']<=2 for v in reports)
