"""Decoder shared by offline baking and independent publication checks."""
from pathlib import Path
import numpy as np


class DomeResource:
    def __init__(self, path):
        data = Path(path).read_bytes()
        header = np.frombuffer(data, dtype='>i4', count=4)
        assert tuple(header[[0, 1, 3]]) == (3, 12288, 2)
        self.metadata = np.frombuffer(data, dtype='>f4', offset=16, count=12288*8).reshape(-1, 8).astype(float)
        packed = np.frombuffer(data, dtype='>u4', offset=16+12288*32).astype(np.uint32).reshape(-1, 4)
        self.nodes = np.empty((len(packed), 8))
        self.nodes[:, 0] = (packed[:, 0] & 65535)*(80./65535)-40
        self.nodes[:, 1] = (packed[:, 0] >> 16)*(80./65535)-40
        self.nodes[:, 2] = (packed[:, 1] & 65535)*(80./65535)-40
        self.nodes[:, 3] = packed[:, 2].copy().view(np.float32)
        self.nodes[:, 7] = (packed[:, 1] >> 16)*.001
        for k in range(3):
            velocity = ((packed[:, 3] >> (10*k)) & 1023).astype(int)
            self.nodes[:, 4+k] = np.where(velocity >= 512, velocity-1024, velocity)*(6./511)

    def quantize(self, rank):
        base, count = int(self.metadata[rank, 0]/2), int(self.metadata[rank, 1])
        take = slice(base, base+count)
        n = self.nodes[take]
        n[:, :3] = np.rint((n[:, :3]+40)*65535/80)*(80./65535)-40
        n[:, 4:7] = np.clip(np.rint(n[:, 4:7]*511/6), -511, 511)*(6./511)
        for j in np.flatnonzero(np.linalg.norm(n[:, 4:7], axis=1) < .001):
            before = n[j, :3]-n[max(0, j-1), :3]
            after = n[min(count-1, j+1), :3]-n[j, :3]
            direction = before/max(np.linalg.norm(before), 1e-9)+after/max(np.linalg.norm(after), 1e-9)
            if np.linalg.norm(direction) < .01:
                direction = np.cross(before, [0., 1., 0.] if abs(before[1]) < np.linalg.norm(before[[0, 2]]) else [1., 0., 0.])
            n[j, 4:7] = np.rint(direction/max(np.linalg.norm(direction), 1e-9)*.5*511/6)*(6./511)
        n[:, 7] = np.rint(n[:, 7]*1000)*.001
        p, q = n[:-1, :3], n[1:, :3]
        dt = np.diff(n[:, 7])[:, None]
        v, w = n[:-1, 4:7]*dt, n[1:, 4:7]*dt
        length = np.zeros(count-1)
        for x, weight in zip(*np.polynomial.legendre.leggauss(8)):
            u = (x+1)*.5
            derivative = (6*u*u-6*u)*p+(-6*u*u+6*u)*q+(3*u*u-4*u+1)*v+(3*u*u-2*u)*w
            length += weight*.5*np.linalg.norm(derivative, axis=1)
        n[:, 3] = np.r_[0, np.cumsum(length)].astype(np.float32)
        self.metadata[rank, 2] = np.float32(n[-1, 3]/6)

    def write(self, path):
        import struct
        n = self.nodes
        assert np.all(abs(n[:, :3]) < 40)
        position = np.rint((n[:, :3]+40)*65535/80).astype(np.uint32)
        parameter = np.rint(n[:, 7]*1000).astype(np.uint32)
        velocity = np.clip(np.rint(n[:, 4:7]*511/6), -511, 511).astype(np.int32).astype(np.uint32) & 1023
        packed = np.column_stack((position[:, 0] | (position[:, 1] << 16), position[:, 2] | (parameter << 16),
                                  n[:, 3].astype(np.float32).view(np.uint32),
                                  velocity[:, 0] | (velocity[:, 1] << 10) | (velocity[:, 2] << 20))).astype('>u4')
        output = Path(path)
        temporary = output.with_suffix(output.suffix+'.tmp')
        temporary.write_bytes(struct.pack('>4i', 3, 12288, len(n)*2, 2)+self.metadata.astype('>f4').tobytes()+packed.tobytes())
        temporary.replace(output)

    def closest_arcs(self):
        """Closest points of each encoded cubic, using its degree-five critical polynomial."""
        result = np.zeros(len(self.metadata))
        gx, gw = np.polynomial.legendre.leggauss(8)
        for rank, record in enumerate(self.metadata):
            base, count = int(record[0]/2), int(record[1])
            n = self.nodes[base:base+count]
            p, q = n[:-1, :3], n[1:, :3]
            dt = np.diff(n[:, 7])[:, None]
            v, w = n[:-1, 4:7]*dt, n[1:, 4:7]*dt
            controls = np.stack((p, p+v/3, q-w/3, q), axis=1)
            low, high = controls.min(axis=1), controls.max(axis=1)
            bound = np.maximum(low, np.maximum(0, -high))
            best = np.sum(n[:, :3]*n[:, :3], axis=1)
            at = int(np.argmin(best));distance, arc_at = best[at], n[at, 3]
            for segment in np.flatnonzero(np.sum(bound*bound, axis=1) <= distance+1e-8):
                a, b, c, d = 2*p[segment]-2*q[segment]+v[segment]+w[segment], -3*p[segment]+3*q[segment]-2*v[segment]-w[segment], v[segment], p[segment]
                polynomial = [3*np.dot(a,a),5*np.dot(a,b),4*np.dot(a,c)+2*np.dot(b,b),3*np.dot(d,a)+3*np.dot(c,b),np.dot(c,c)+2*np.dot(d,b),np.dot(d,c)]
                roots = np.roots(polynomial)
                for u in [0., 1.]+[float(z.real) for z in roots if abs(z.imag)<1e-7 and 0<z.real<1]:
                    point = ((a*u+b)*u+c)*u+d;value = np.dot(point,point)
                    if value > distance+1e-9:continue
                    def integral(limit):
                        t=(gx+1)*limit*.5;derivative=3*a*t[:,None]**2+2*b*t[:,None]+c
                        return limit*.5*np.sum(gw*np.linalg.norm(derivative,axis=1))
                    arc = n[segment,3]+integral(u)/max(integral(1.),1e-12)*(n[segment+1,3]-n[segment,3])
                    if value<distance-1e-9 or arc>arc_at:distance,arc_at=value,arc
            result[rank]=arc_at
        return result

    def refine_pairs(self,a,b,times,interval):
        """Fine checks only where a 12m/s relative-speed bound permits contact."""
        minimum=np.full(len(a),np.inf);at=times.copy();last=np.minimum(self.metadata[a,2],self.metadata[b,2])-1e-8
        count=max(1,int(np.ceil(interval/.002)))
        for offset in np.linspace(0,interval,count+1):
            time=np.minimum(times+offset,last)
            p=self.sample(a,time);q=self.sample(b,time);distance=np.linalg.norm(p-q,axis=1);take=distance<minimum
            minimum[take]=distance[take];at[take]=time[take]
        return minimum,at,interval/count

    def contact_intervals(self,a,b,times,interval,clearance):
        """Lipschitz branch-and-bound; refine only intervals not already clear."""
        size=len(a);hit=np.full(size,np.inf);when=times.copy();bounds=np.full(size,np.inf)
        root=np.arange(size);left=times.copy();right=np.minimum(times+interval,np.minimum(self.metadata[a,2],self.metadata[b,2])-1e-8)
        observed=np.inf
        while len(root):
            middle=(left+right)*.5;distance=np.empty(len(root))
            for start in range(0,len(root),32768):
                take=slice(start,start+32768);r=root[take]
                p=self.sample(a[r],middle[take]);q=self.sample(b[r],middle[take]);distance[take]=np.linalg.norm(p-q,axis=1)
            observed=min(observed,distance.min(initial=np.inf));lower=distance-6*(right-left)
            unsafe=(distance<clearance)|((right-left<=.002)&(lower<clearance))
            for k in np.flatnonzero(unsafe):
                r=root[k]
                if distance[k]<hit[r]:hit[r],when[r]=distance[k],middle[k]
            finished=(lower>=clearance)|unsafe
            np.minimum.at(bounds,root[finished],lower[finished])
            keep=(~finished)&~np.isfinite(hit[root])
            r,l,h,c=root[keep],left[keep],right[keep],middle[keep]
            root=np.r_[r,r];left=np.r_[l,c];right=np.r_[c,h]
        return hit,when,bounds,observed

    def sample(self, ids, seconds):
        m, nodes = self.metadata, self.nodes
        base = (m[ids, 0]/2).astype(int)
        lo = np.zeros(len(ids), int)
        hi = m[ids, 1].astype(int)-1
        distance = np.minimum(np.asarray(seconds)*6, m[ids, 2]*6)
        for _ in range(12):
            mid = (lo+hi)//2
            left, active = nodes[base+mid, 3] < distance, hi-lo > 1
            lo, hi = np.where(left & active, mid, lo), np.where(~left & active, mid, hi)
        first, last = nodes[base+lo], nodes[base+hi]
        p, q = first[:, :3], last[:, :3]
        dt = (last[:, 7]-first[:, 7])[:, None]
        v, w = first[:, 4:7]*dt, last[:, 4:7]*dt
        local, length = distance-first[:, 3], last[:, 3]-first[:, 3]
        u = np.clip(local/length, 0, 1)

        def derivative(t):
            t = t[:, None]
            return (6*t*t-6*t)*p+(-6*t*t+6*t)*q+(3*t*t-4*t+1)*v+(3*t*t-2*t)*w

        def integral(t):
            result = np.zeros(len(ids))
            for x, weight in zip((.9602898564975363, .7966664774136267, .525532409916329, .1834346424956498),
                                 (.1012285362903763, .2223810344533745, .3137066458778873, .362683783378362)):
                result += weight*(np.linalg.norm(derivative(t*(1-x)*.5), axis=1)+np.linalg.norm(derivative(t*(1+x)*.5), axis=1))
            return result*t*.5

        total = integral(np.ones(len(ids)))
        lower, upper, finished = np.zeros(len(ids)), np.ones(len(ids)), np.zeros(len(ids), bool)
        for _ in range(8):
            error = local-integral(u)*length/np.maximum(total, 1e-9)
            finished |= abs(error) < .000001
            lower = np.where((error > 0) & ~finished, u, lower)
            upper = np.where((error <= 0) & ~finished, u, upper)
            candidate = u+error/np.maximum(np.linalg.norm(derivative(u), axis=1)*length/np.maximum(total, 1e-9), 1e-9)
            u = np.where(finished, u, np.where((candidate > lower) & (candidate < upper), candidate, (lower+upper)*.5))
        u = u[:, None]
        return (2*u**3-3*u*u+1)*p+(-2*u**3+3*u*u)*q+(u**3-2*u*u+u)*v+(u**3-u*u)*w
