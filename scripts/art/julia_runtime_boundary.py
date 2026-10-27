"""Precompute the complete real-c quadratic Julia slice, including disconnected radial intervals.

This is an escape-test contour, not the radial maximum/hull (which would fill genuine gaps).
Each contour segment is revolved about the real axis by the renderer, using quaternion symmetry.
"""
from pathlib import Path
import json
import struct
import numpy as np

C=-.5
NX,NR=2049,1025
EXTENT=(1+np.sqrt(3))/2
xs=np.linspace(-EXTENT,EXTENT,NX)
rs=np.linspace(0,EXTENT,NR)

def bounded(values):
    z=np.asarray(values,dtype=np.complex128).ravel().copy()
    active=np.arange(len(z));inside=np.zeros(len(z),bool)
    fixed=(1-np.sqrt(3))/2
    trap=(1-2*abs(fixed))*.999
    for _ in range(192):
        escaped=np.abs(z)>16
        trapped=np.abs(z-fixed)<trap
        inside[active[trapped]]=True
        keep=~(escaped|trapped);active=active[keep];z=z[keep]
        if not len(z):break
        z=z*z+C
    inside[active[np.abs(z)<=16]]=True
    return inside.reshape(np.shape(values))

field=bounded(xs[:,None]+1j*rs[None,:])
segments=[]
corners=[(0,0),(1,0),(1,1),(0,1)]
edges=[(0,1),(1,2),(2,3),(3,0)]
mixed=(field[:-1,:-1]!=field[1:,:-1])|(field[:-1,:-1]!=field[1:,1:])|(field[:-1,:-1]!=field[:-1,1:])
cells=np.argwhere(mixed)
centers=bounded((xs[cells[:,0]]+xs[cells[:,0]+1])/2+1j*(rs[cells[:,1]]+rs[cells[:,1]+1])/2)
for cell,(ix,ir) in enumerate(cells):
    values=[field[ix+dx,ir+dy] for dx,dy in corners]
    points={}
    for edge,(a,b) in enumerate(edges):
        if values[a]==values[b]:continue
        ax,ar=corners[a];bx,br=corners[b]
        points[edge]=((xs[ix+ax]+xs[ix+bx])/2,(rs[ir+ar]+rs[ir+br])/2)
    if len(points)==2:
        pairs=[tuple(points)]
    else:
        pairs=[(0,1),(2,3)] if bool(centers[cell])==bool(values[0]) else [(0,3),(1,2)]
    for a,b in pairs:segments.append((*points[a],*points[b]))

root=Path('src/main/resources/data/createmanaindustry')
reports=Path('docs/design-assets/julia-offline')
root.mkdir(parents=True,exist_ok=True)
reports.mkdir(parents=True,exist_ok=True)
with (root/'quadratic-c050.bin').open('wb') as output:
    output.write(struct.pack('>ii',0x434d494a,len(segments)))
    for segment in segments:output.write(struct.pack('>ffff',*segment))
report={'degree':2,'c':[-.5,0,0,0],'slice':'q0=(x,y,z,0)',
        'iterations':192,'grid':[NX,NR],'extent':EXTENT,'segments':len(segments),
        'radially_disconnected_columns':int((np.diff(field.astype(int),axis=1)>0).any(axis=1).sum()),
        'method':'all marching-square contours; ambiguous cells use independent center escape test',
        'max_grid_spacing':float(max(xs[1]-xs[0],rs[1]-rs[0])),'deformation':False}
(reports/'quadratic-c050.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))

# The reference is now a weak/orthographic front projection, not a perspective DE fit.
# The complete 3D contour remains unchanged. Only this silhouette's inscribed radius sets the lens fit.
contours=np.asarray(segments)
inner=float(np.linalg.norm(contours.reshape(-1,2),axis=1).min())
outer=np.max(np.where(field,rs[None,:],0),axis=1)
front_inner=float(np.hypot(xs,outer).min())
fit={'revision':2,'projection':'object-centred weak perspective; physical perspective within 128 blocks',
     'front_inscribed_radius':front_inner,'entrance_scale':64/inner*1.002,'inscribed_radius':inner,
     'method':'original quadratic front silhouette inscribed radius; no perspective lookup or shape deformation'}
(root/'quadratic-c050-fit.json').write_text(json.dumps(fit,indent=2)+'\n',encoding='utf-8')
print(json.dumps(fit))
