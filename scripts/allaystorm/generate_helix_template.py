"""Offline, deterministic double-log-cone Gaussian/Poisson template. Never run during gameplay."""
import math, random, struct, argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--spacing',type=float,default=1.4);parser.add_argument('--output',default='src/main/resources/data/createmanaindustry/helix-template-v1.bin');args=parser.parse_args()
rng=random.Random(314159);grid={};groups=[[],[]];attempts=0;d=args.spacing;growth=math.log(32/.6)
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def norm(v):
 n=math.sqrt(dot(v,v));return tuple(x/n for x in v)
def cross(a,b):return(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
while min(map(len,groups))<2048 and attempts<2000000:
 g=attempts%2;attempts+=1
 if len(groups[g])>=2048:continue
 u=rng.random()**.45;e=.6*math.exp(growth*u);r=.4*e;t=4*math.pi*u+g*math.pi
 radial=(0,math.cos(t),math.sin(t));tangent=norm((-64,r*growth*math.cos(t)-r*4*math.pi*math.sin(t),r*growth*math.sin(t)+r*4*math.pi*math.cos(t)))
 projection=dot(radial,tangent);normal=norm(tuple(radial[i]-tangent[i]*projection for i in range(3)));binormal=cross(tangent,normal)
 q=math.sqrt(-2*math.log(1-rng.random()*(1-math.exp(-4.5))));angle=rng.random()*2*math.pi
 point=tuple((32-64*u if i==0 else radial[i]*r)+.2*e*q*(normal[i]*math.cos(angle)+binormal[i]*math.sin(angle)) for i in range(3))
 v=(32-point[0])/64
 if not 0<=v<=1 or math.hypot(point[1],point[2])>.6*math.exp(growth*v):continue
 key=tuple(math.floor(x/d) for x in point)
 if any(sum((old[i]-point[i])**2 for i in range(3))<d*d for x in range(-1,2) for y in range(-1,2) for z in range(-1,2) for old in grid.get((key[0]+x,key[1]+y,key[2]+z),[])):continue
 grid.setdefault(key,[]).append(point);groups[g].append(point)
assert list(map(len,groups))==[2048,2048],(attempts,list(map(len,groups)))
for points in groups:points.sort(key=lambda p:-p[0])
output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(struct.pack('>ii',1,4096)+b''.join(struct.pack('>fff',*p) for group in groups for p in group))
print('spacing',d,'members',list(map(len,groups)),'attempts',attempts,'bytes',output.stat().st_size)
