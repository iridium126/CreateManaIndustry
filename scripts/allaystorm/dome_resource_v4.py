"""Dome v4: C2 inverse-arc Hermite tables, decoded exactly as Java/GLSL.

Each node contains p(s), dp/ds and d2p/ds2. Arc inversion and integration are
offline operations. Runtime lookup is a binary search plus one quintic; there
is no per-member quadrature, root solve or sampled-frame playback.
"""
import struct
from pathlib import Path
import numpy as np

COUNT, RECORD, NODE = 12288, 16, 12

def coefficients(p,q,v,w,a,b):
    delta=q-p
    return np.stack((p,v,a*.5,10*delta-6*v-4*w-1.5*a+.5*b,
                     -15*delta+8*v+7*w+1.5*a-b,6*delta-3*v-3*w-.5*a+.5*b),axis=-2)

def evaluate(c,u,derivative=0):
    c=np.asarray(c);u=np.asarray(u)[...,None]
    if derivative:
        for _ in range(derivative):c=c[...,1:,:]*np.arange(1,c.shape[-2])[...,None]
    result=c[...,-1,:]
    for k in range(c.shape[-2]-2,-1,-1):result=result*u+c[...,k,:]
    return result

def clock_distance(seconds,initial_speed):
    t=np.maximum(seconds,0);u=np.minimum(t/.5,1)
    return initial_speed*t+(6-initial_speed)*.5*(u**6-3*u**5+2.5*u**4)+np.maximum(t-.5,0)*(6-initial_speed)

class DomeResourceV4:
    def __init__(self,path):
        data=Path(path).read_bytes();version,count,words,stride=struct.unpack_from('>4i',data)
        if (version,count,stride)!=(4,COUNT,6):raise ValueError('Invalid Dome v4 header')
        self.metadata=np.frombuffer(data,dtype='>f4',offset=16,count=COUNT*RECORD).reshape(COUNT,RECORD).astype(float)
        self.nodes=np.frombuffer(data,dtype='>f4',offset=16+COUNT*RECORD*4).reshape(-1,NODE).astype(float)
        if len(self.nodes)*6!=words:raise ValueError('Invalid Dome v4 table length')
        if not np.isfinite(self.metadata).all() or not np.isfinite(self.nodes).all():raise ValueError('Nonfinite Dome v4')

        for record in self.metadata:
            for first,total,last in [(record[0],record[1],record[11]),(record[8],record[12],record[10])]:
                if first!=int(first) or total!=int(total) or first<0 or total<2 or total>4096 or first+total>len(self.nodes):raise ValueError('Invalid Dome table window')
                arc=self.nodes[int(first):int(first+total),3]
                if arc[0]!=0 or np.any(np.diff(arc)<=0) or last<=arc[-2] or last>arc[-1]+.00002:raise ValueError('Invalid Dome arc order/active window')

    @staticmethod
    def write(path,metadata,nodes):
        metadata=np.asarray(metadata,dtype='>f4');nodes=np.asarray(nodes,dtype='>f4')
        assert metadata.shape==(COUNT,RECORD) and nodes.shape[1]==NODE
        output=Path(path);output.parent.mkdir(parents=True,exist_ok=True)
        temp=output.with_suffix(output.suffix+'.tmp')
        temp.write_bytes(struct.pack('>4i',4,COUNT,len(nodes)*6,6)+metadata.tobytes()+nodes.tobytes());temp.replace(output)

    def lookup(self,base,count,distance,derivative=0):
        base=np.asarray(base,dtype=int);count=np.asarray(count,dtype=int);distance=np.asarray(distance)
        lo=np.zeros_like(base);hi=count-1
        for _ in range(12):
            mid=(lo+hi)//2;active=hi-lo>1;left=self.nodes[base+mid,3]<distance
            lo=np.where(active&left,mid,lo);hi=np.where(active&~left,mid,hi)
        p,q=self.nodes[base+lo],self.nodes[base+hi];length=q[...,3]-p[...,3]
        u=np.clip((distance-p[...,3])/length,0,1)
        c=coefficients(p[...,:3],q[...,:3],p[...,4:7]*length[...,None],q[...,4:7]*length[...,None],p[...,8:11]*length[...,None]**2,q[...,8:11]*length[...,None]**2)
        return evaluate(c,u,derivative)/length[...,None]**derivative

    def sample_arc(self,ids,distance,derivative=0):
        m=self.metadata[ids];return self.lookup(m[...,0],m[...,1],np.minimum(distance,m[...,11]),derivative)

    def sample(self,ids,seconds,derivative=0):
        m=self.metadata[ids];s=clock_distance(np.asarray(seconds),abs(m[...,9]));value=self.sample_arc(ids,s,derivative)
        if not derivative:return value
        u=np.minimum(np.maximum(seconds,0)/.5,1);delta=6-abs(m[...,9]);speed=abs(m[...,9])+delta*(10*u**3-15*u**4+6*u**5)
        if derivative==1:return value*speed[...,None]
        acceleration=delta*60*u*u*(1-u)**2
        if derivative==2:return value*speed[...,None]**2+self.sample_arc(ids,s,1)*acceleration[...,None]
        jerk=delta*240*u*(1-u)*(1-2*u)
        return value*speed[...,None]**3+3*self.sample_arc(ids,s,2)*(speed*acceleration)[...,None]+self.sample_arc(ids,s,1)*jerk[...,None]

    def gather(self,ids,seconds,derivative=0):
        m=self.metadata[ids];fraction=(np.asarray(ids)-m[...,3])/m[...,7]+m[...,9]*seconds/m[...,10]
        value=self.lookup(m[...,8],m[...,12],np.mod(fraction,1)*m[...,10],derivative)
        return value*m[...,9,None]**derivative

    def core(self,ids):
        m=self.metadata[ids];base=m[...,3].astype(int);count=m[...,7].astype(int);return ((base//12+(np.asarray(ids)-base)%(count//12))%16)==0

    def mapped(self,ids,seconds):
        m=self.metadata[ids];shift=np.floor(m[...,9]*seconds/m[...,10]*m[...,7]+.5)
        return (m[...,3]+np.mod(np.asarray(ids)-m[...,3]+shift,m[...,7])).astype(int)

    def locked(self,ids,seconds,gather_seconds,derivative=0):
        mapped=self.mapped(ids,gather_seconds);value=self.sample(mapped,seconds,derivative)
        u=np.clip(np.asarray(seconds)/1.25,0,1)
        if np.all(u>=1):return value
        m=self.metadata[ids];speed=np.abs(m[...,9]);offset=self.gather(ids,gather_seconds)-self.sample_arc(mapped,0)
        dv=self.gather(ids,gather_seconds,1)-self.sample_arc(mapped,0,1)*speed[...,None]
        da=self.gather(ids,gather_seconds,2)-self.sample_arc(mapped,0,2)*speed[...,None]**2
        duration=1.25
        h=np.array([1.,0,0,-10,15,-6]);g=np.array([0.,1,0,-6,8,-3]);k=np.array([0.,0,1,-3,3,-1])
        weights=[np.polynomial.polynomial.polyval(u,np.polynomial.polynomial.polyder(c,m=derivative))/duration**derivative for c in (h,g,k)]
        return value+offset*weights[0][...,None]+dv*(duration*weights[1])[...,None]+da*(.5*duration**2*weights[2])[...,None]
