"""Repair sparse contacts on the final decoded, independently arc-timed curves."""
import argparse
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from dome_resource import DomeResource


def bake(asset, output, passes=24):
    resource = DomeResource(asset)
    m = resource.metadata
    rank = np.arange(12288)
    base, size = m[:, 3].astype(int), m[:, 7].astype(int)
    core = ((base//12+(rank-base) % (size//12)) % 16) == 0
    for member in rank:
        begin, count = int(m[member, 0]/2), int(m[member, 1])
        if np.any(np.linalg.norm(resource.nodes[begin:begin+count, 4:7], axis=1) < .001):
            resource.quantize(member)
    for iteration in range(passes):
        events = [];possible=[]
        minimum = 100.
        for time in np.arange(0, m[:, 2].max(), .02):
            ids = np.flatnonzero(m[:, 2] > time)
            points = resource.sample(ids, time)
            pairs = cKDTree(points).query_pairs(.42+12*.02, output_type='ndarray')
            if not len(pairs):
                continue
            possible.append(np.column_stack((ids[pairs[:,0]],ids[pairs[:,1]],np.full(len(pairs),time))))
        if possible:
            possible=np.concatenate(possible);a,b=possible[:,0].astype(int),possible[:,1].astype(int)
            distances,times,_,minimum=resource.contact_intervals(a,b,possible[:,2],.02,.42)
            for k in np.flatnonzero(np.isfinite(distances)):events.append((float(distances[k]),float(times[k]),int(a[k]),int(b[k])))
        print('encoded repair', iteration, 'minimum', minimum, 'events', len(events), 'worst', sorted(events)[:2], flush=True)
        if not events:
            resource.write(output)
            return
        touched = set()
        for distance, time, a, b in sorted(events):
            if a in touched or b in touched:
                continue
            ids = np.array([a, b])
            p = resource.sample(ids, time)
            velocity = resource.sample(ids, time+.0001)-p
            velocity /= np.maximum(np.linalg.norm(velocity, axis=1)[:, None], 1e-9)
            normal = (p[0]-p[1])/max(distance, 1e-9)
            candidates = []
            for index, member in enumerate((a, b)):
                direction = normal*(1 if index == 0 else -1)
                direction -= velocity[index]*np.dot(direction, velocity[index])
                if not core[member] and direction[1] < 0 and p[index, 1] < .6:
                    direction[1] = 0
                magnitude = np.linalg.norm(direction)
                if magnitude < .15:
                    direction = np.cross(velocity[index], np.array([0., 1., 0.]))
                    magnitude = np.linalg.norm(direction)
                direction /= max(magnitude, 1e-9)
                projection = np.dot(direction, normal)*(1 if index == 0 else -1)
                amplitude = min(.5, (.46-distance)/max(projection, .25))
                displacement = direction*amplitude
                penalty = amplitude+(1 if core[member] else 0)+max(0, np.dot(displacement[[0, 2]], p[index, [0, 2]]))*.5
                candidates.append((penalty, member, displacement))
            for _, member, displacement in candidates:
                displacement *= .5
                begin, count = int(m[member, 0]/2), int(m[member, 1])
                n = resource.nodes[begin:begin+count]
                u = (n[:, 3]/6-time)/.55
                inside = abs(u) < 1
                weight = np.where(inside, (1-u*u)**3, 0)
                derivative = np.where(inside, -6*u*(1-u*u)**2/.55, 0)
                if not core[member] and displacement[1] < 0:
                    positive = weight > .001
                    displacement[1] = max(displacement[1], np.max((.08-n[positive, 1])/weight[positive], initial=-1))
                rate = np.linalg.norm(n[:, 4:7], axis=1)/6
                n[:, :3] += weight[:, None]*displacement
                n[:, 4:7] += (derivative*rate)[:, None]*displacement
                resource.quantize(member)
                touched.add(member)
            if len(touched) >= 1024:
                break
        resource.write(output)
    raise RuntimeError('Encoded contact baking did not converge; draft was not published')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('asset')
    p.add_argument('output')
    p.add_argument('--passes', type=int, default=24)
    args = p.parse_args()
    bake(args.asset, args.output, args.passes)
