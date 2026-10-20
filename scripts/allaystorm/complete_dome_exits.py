"""Finish the encoded three-dimensional pass, not its horizontal projection."""
import argparse
import numpy as np
from dome_resource import DomeResource


def complete(asset, output):
    resource = DomeResource(asset)
    closest = resource.closest_arcs()
    tracks, total, changed = [], 0, []
    for rank, m in enumerate(resource.metadata):
        start, count = int(m[0]/2), int(m[1])
        n = resource.nodes[start:start+count].copy()
        deficit = 3.02-(n[-1,3]-closest[rank])
        if deficit > .0001:
            deficit=max(deficit,.012)
            p, velocity, parameter = n[-1,:3].copy(),n[-1,4:7].copy(),n[-1,7]
            direction=velocity/max(np.linalg.norm(velocity),1e-9)
            # Continue the actual exit tangent through its own closest plane;
            # a forced outward U-turn would create an extra centre crossing.
            if np.dot(p,direction)<0:deficit=-np.dot(p,direction)+3.02
            n=np.vstack((n,np.r_[p+direction*deficit,0,direction*6,parameter+deficit/6]))
            changed.append(rank)
        resource.metadata[rank,0]=total*2;resource.metadata[rank,1]=len(n);total+=len(n);tracks.append(n)
    resource.nodes=np.concatenate(tracks)
    for rank in changed:resource.quantize(rank)
    resource.write(output)
    print('three-dimensional exits extended',len(changed),'nodes',total,flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('output');args=p.parse_args();complete(args.asset,args.output)
