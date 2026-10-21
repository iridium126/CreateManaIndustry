"""Continuous polynomial bounds on the final decoded Dome v4 curves.

Constant-speed pieces use their inverse-arc polynomial directly. During the
half-second speed ramp the arc clock is a degree-six polynomial, so composition
is still polynomial. Bernstein subdivision bounds entire intervals, including
both sides of every C2 seam; this is not a frame-sampling speed check.
"""
import math
from functools import lru_cache
import numpy as np
from dome_resource_v4 import DomeResourceV4,coefficients,clock_distance
from complete_dome_v4 import clock_at

@lru_cache(None)
def bernstein_matrix(degree):
    # Rows are power coefficients; columns are Bernstein control points.
    return np.array([[math.comb(i,j)/math.comb(degree,j) if j<=i else 0. for i in range(degree+1)] for j in range(degree+1)])

def norm_squared(power):
    out=np.zeros((len(power),power.shape[1]*2-1))
    for i in range(power.shape[1]):
        for j in range(i,power.shape[1]):out[:,i+j]+=np.sum(power[:,i]*power[:,j],axis=-1)*(1 if i==j else 2)
    return out

def product(a,b):
    out=np.zeros((len(a),a.shape[1]+b.shape[1]-1))
    for j in range(b.shape[1]):out[:,j:j+a.shape[1]]+=a*b[:,j,None]
    return out

def vector_cross(a,b):
    out=np.zeros((len(a),a.shape[1]+b.shape[1]-1,3))
    for i in range(a.shape[1]):
        for j in range(b.shape[1]):out[:,i+j]+=np.cross(a[:,i],b[:,j])
    return out

def derivative(c,order,span):
    for _ in range(order):c=c[:,1:]*np.arange(1,c.shape[1])[None,:,None]
    return c/span[:,None,None]**order

def scalar_bound(power,limit,label):
    controls=power@bernstein_matrix(power.shape[1]-1)
    # Roundoff inflation is kept through subdivision. Resource inputs are the
    # exact decoded binary32 values, promoted to binary64 for the certificate.
    rounding=np.sum(abs(power),axis=1)*np.finfo(float).eps*power.shape[1]*8
    rows=controls;epsilon=rounding;maximum=-np.inf
    for depth in range(24):
        upper=rows.max(axis=1)+epsilon;safe=upper<=limit;maximum=max(maximum,upper[safe].max(initial=-np.inf))
        failed=(rows[:,0]-epsilon>limit)|(rows[:,-1]-epsilon>limit)
        if failed.any():raise ValueError(f'{label}: encoded interval exceeds limit {limit}; endpoint={max(rows[failed,0].max(),rows[failed,-1].max())}')
        keep=~safe
        if not keep.any():return float(maximum)
        rows=rows[keep];epsilon=epsilon[keep]
        work=rows.copy();left=np.empty_like(work);right=np.empty_like(work);left[:,0]=work[:,0];right[:,-1]=work[:,-1]
        for j in range(1,work.shape[1]):
            work= (work[:,:-1]+work[:,1:])*.5;left[:,j]=work[:,0];right[:,-j-1]=work[:,-1]
        rows=np.concatenate((left,right));epsilon=np.r_[epsilon,epsilon]
        if len(rows)>1000000:raise ValueError(f'{label}: ill-conditioned bound, not published')
    raise ValueError(f'{label}: unresolved encoded interval')

def restrict(curve,low,high):
    result=np.zeros_like(curve);width=high-low
    for i in range(6):
        for j in range(i+1):result[:,j]+=curve[:,i]*(math.comb(i,j)*low**(i-j)*width**j)[:,None]
    return result

def compose_clock(curve,arc_start,arc_span,t0,t1,speed0):
    width=t1-t0;delta=6-speed0
    global_clock=np.stack((np.zeros_like(delta),speed0,np.zeros_like(delta),np.zeros_like(delta),20*delta,-48*delta,32*delta),axis=1)
    clock=np.zeros_like(global_clock)
    for j in range(7):
        for k in range(j+1):clock[:,k]+=global_clock[:,j]*math.comb(j,k)*t0**(j-k)*width**k
    local=clock/arc_span[:,None];local[:,0]-=arc_start/arc_span
    out=curve[:,-1:, :]
    for i in range(4,-1,-1):
        next=np.zeros((len(curve),out.shape[1]+6,3))
        for j in range(7):next[:,j:j+out.shape[1]]+=out*local[:,j,None,None]
        next[:,0]+=curve[:,i];out=next
    expected=derivative(clock[:,:,None],1,width)[:,:,0]
    return out,expected,width

def certify(path):
    r=DomeResourceV4(path);stable=[];entry=[]
    for rank,record in enumerate(r.metadata):
        base,count=int(record[0]),int(record[1]);n=r.nodes[base:base+count];d=np.diff(n[:,3]);c=coefficients(n[:-1,:3],n[1:,:3],n[:-1,4:7]*d[:,None],n[1:,4:7]*d[:,None],n[:-1,8:11]*d[:,None]**2,n[1:,8:11]*d[:,None]**2)
        split=clock_distance(.5,abs(record[9]));last=record[11]
        for k,curve in enumerate(c):
            lo=n[k,3];hi=min(n[k+1,3],last)
            if hi<=lo:break
            if lo<split:entry.append((curve,lo,d[k],clock_at(lo,abs(record[9])),clock_at(min(hi,split),abs(record[9])),abs(record[9])))
            if hi>split:stable.append((curve,(max(lo,split)-lo)/d[k],(hi-lo)/d[k],hi-max(lo,split)))
    max_acc=max_jerk=max_speed=0.;max_horizontal=max_y=max_negative_y=0.
    def position_bounds(c):
        nonlocal max_horizontal,max_y,max_negative_y
        max_horizontal=max(max_horizontal,math.sqrt(scalar_bound(norm_squared(c[:,:,[0,2]]),43**2,'horizontal collision demand')))
        max_y=max(max_y,scalar_bound(c[:,:,1],39.,'upper collision demand'));max_negative_y=max(max_negative_y,scalar_bound(-c[:,:,1],19.,'lower collision demand'))

    for begin in range(0,len(stable),4096):
        batch=stable[begin:begin+4096];c=np.stack([v[0] for v in batch]);low=np.array([v[1] for v in batch]);high=np.array([v[2] for v in batch]);span=np.array([v[3] for v in batch]);c=restrict(c,low,high)
        position_bounds(c);v=derivative(c,1,span);a=derivative(c,2,span)*36;j=derivative(c,3,span)*216
        speed=norm_squared(v);speed[:,0]-=1
        bound=max(scalar_bound(speed,.0033,'speed squared'),scalar_bound(-speed,.0033,'speed squared'))
        max_speed=max(max_speed,bound*6/1.99)
        max_acc=max(max_acc,math.sqrt(scalar_bound(norm_squared(a),324,'acceleration')))
        max_jerk=max(max_jerk,math.sqrt(scalar_bound(norm_squared(j),14400,'jerk')))
    for begin in range(0,len(entry),256):
        batch=entry[begin:begin+256];c=np.stack([v[0] for v in batch]);start=np.array([v[1] for v in batch]);span=np.array([v[2] for v in batch]);t0=np.array([v[3] for v in batch]);t1=np.array([v[4] for v in batch]);speed0=np.array([v[5] for v in batch])
        c,expected,dt=compose_clock(c,start,span,t0,t1,speed0);position_bounds(c);v=derivative(c,1,dt);a=derivative(c,2,dt);j=derivative(c,3,dt)
        square=norm_squared(v);error=square.copy();expected_square=product(expected,expected);error[:,:expected_square.shape[1]]-=expected_square
        bound=max(scalar_bound(error,.079,'entry speed'),scalar_bound(-error,.079,'entry speed'));max_speed=max(max_speed,bound/7.99)
        max_acc=max(max_acc,math.sqrt(scalar_bound(norm_squared(a),324,'entry acceleration')));max_jerk=max(max_jerk,math.sqrt(scalar_bound(norm_squared(j),14400,'entry jerk')))
        cross_square=norm_squared(vector_cross(v,a));fourth=product(square,square)
        angular=np.zeros((len(c),max(cross_square.shape[1],fourth.shape[1])));angular[:,:cross_square.shape[1]]=cross_square;angular[:,:fourth.shape[1]]-=math.pi**2*fourth
        scalar_bound(angular,0.,'entry turn rate')
    return dict(continuousMaximumSpeedError=max_speed,continuousMaximumAcceleration=max_acc,continuousMaximumJerk=max_jerk,continuousTurnRateBound=math.pi,certifiedSegments=len(stable)+len(entry),continuousMaximumHorizontalRadius=max_horizontal,continuousMaximumY=max_y,continuousMinimumY=-max_negative_y)

if __name__=='__main__':
    import argparse,json
    p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('--report',default='build/dome-v14/kinematic-certificate.json');args=p.parse_args();result=certify(args.asset);open(args.report,'w').write(json.dumps(result,indent=2));print(result,flush=True)
