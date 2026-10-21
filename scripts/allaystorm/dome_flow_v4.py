"""Deterministic equal-arc closed streamlines; offline-only numeric geometry.

The displacement budget is measured against the original thick-shell circle.
All members sharing an orbit have one clock and one speed, so they cannot lap
one another. Different orbits have independent, stable speeds and handedness.
"""
import numpy as np
from scipy.integrate import cumulative_trapezoid

TAU = 2*np.pi

def hash01(seed):
    seed = (int(seed)*747796405+2891336453) & 0xffffffff
    word = (((seed >> ((seed >> 28)+4)) ^ seed)*277803737) & 0xffffffff
    return ((word >> 22) ^ word)/4294967296.

class Streamline:
    def __init__(self, rho, height, count, identity):
        if rho<2:
            rho=1.85
        self.rho, self.height, self.count = rho, height, count
        # The polar orbit is deliberately slower: its small radius must not
        # exceed the attack entry acceleration budget before turning inward.
        self.speed = min(4+2*hash01(identity+3), np.sqrt(16*rho))
        self.direction = 1 if hash01(identity+9)>.5 else -1
        margin = min(np.hypot(rho,height)-16,32-np.hypot(rho,height))
        self.amplitude = max(0,min(.50,margin*.45))
        if rho<2:
            self.speed=4.
            self.amplitude*=.3
        seed = np.array([hash01(identity*17+i) for i in range(8)])
        self.offset = (seed[:3]-.5)*self.amplitude*.65
        self.phase = seed[3:6]*TAU
        self.radial = self.amplitude*.22
        self.vertical = self.amplitude*.28
        self.angular = (seed[6]-.5)*self.amplitude*.4/max(rho,1)
        t=np.linspace(0,TAU,8193)
        _,v,_=self.evaluate(t)
        arc=cumulative_trapezoid(np.linalg.norm(v,axis=-1),t,initial=0)
        self.length=float(arc[-1]);self.angle_table=t;self.arc_table=arc

    def evaluate(self, theta):
        t=np.asarray(theta);a=t+self.angular*np.sin(2*t+self.phase[2]);ad=1+2*self.angular*np.cos(2*t+self.phase[2]);add=-4*self.angular*np.sin(2*t+self.phase[2])
        r=self.rho+self.radial*np.cos(2*t+self.phase[0]);rd=-2*self.radial*np.sin(2*t+self.phase[0]);rdd=-4*self.radial*np.cos(2*t+self.phase[0])
        y=self.height+self.vertical*np.sin(2*t+self.phase[1]);yd=2*self.vertical*np.cos(2*t+self.phase[1]);ydd=-4*self.vertical*np.sin(2*t+self.phase[1])
        c,s=np.cos(a),np.sin(a)
        p=np.stack((r*c,y,r*s),axis=-1)+self.offset
        v=np.stack((rd*c-r*s*ad,yd,rd*s+r*c*ad),axis=-1)
        acc=np.stack((rdd*c-2*rd*s*ad-r*c*ad*ad-r*s*add,ydd,rdd*s+2*rd*c*ad-r*s*ad*ad+r*c*add),axis=-1)
        return p,v,acc

    def sample(self, fraction):
        t=np.interp(np.mod(fraction,1)*self.length,self.arc_table,self.angle_table)
        p,v,a=self.evaluate(t);speed=np.linalg.norm(v,axis=-1)
        tangent=v/speed[...,None]
        # First and second derivatives with respect to normalised arc phase.
        curvature=(a-tangent*np.sum(tangent*a,axis=-1)[...,None])/speed[...,None]**2
        return p,tangent*self.length,curvature*self.length**2

def streamlines(metadata):
    result=[];base=0
    while base<len(metadata):
        r=metadata[base];count=int(r[7]);orbit=Streamline(float(r[4]),float(r[5]),count,len(result)+1)
        result.append((base,orbit));base+=count
    return result
