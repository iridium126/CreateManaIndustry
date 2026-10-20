"""Validate final encoded space-time geometry; this is not a game-frame benchmark."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from dome_resource import DomeResource


def validate(asset, step):
    resource = DomeResource(asset)
    metadata = resource.metadata
    rank = np.arange(12288)
    base, count = metadata[:, 3].astype(int), metadata[:, 7].astype(int)
    core = ((base//12+(rank-base) % (count//12)) % 16) == 0
    minimum, time_at, near = 100., 0., 0
    possible=[]
    closest_xz, closest_3d = np.full(12288, np.inf), np.full(12288, np.inf)
    starts = resource.sample(rank, 0.)
    below = np.zeros(12288, bool)
    lowest = starts[:, 1].min()
    for t in np.arange(0, metadata[:, 2].max(), step):
        ids = np.flatnonzero(metadata[:, 2] > t)
        points = resource.sample(ids, t)
        tree=cKDTree(points);distances = tree.query(points, k=2)[0][:, 1]
        pairs=tree.query_pairs(.40+12*step,output_type='ndarray')
        if len(pairs):possible.append(np.column_stack((ids[pairs[:,0]],ids[pairs[:,1]],np.full(len(pairs),t))))
        gap = distances.min(initial=100)
        if gap < minimum:
            minimum, time_at = float(gap), float(t)
        near += int(np.count_nonzero(distances < .315))
        closest_xz[ids] = np.minimum(closest_xz[ids], np.linalg.norm(points[:, [0, 2]], axis=1))
        closest_3d[ids] = np.minimum(closest_3d[ids], np.linalg.norm(points, axis=1))
        below[ids] |= points[:, 1] < 0
        lowest = min(lowest, points[:, 1].min(initial=100))
    point = resource.sample(rank, .5)
    velocity = (resource.sample(rank, .5001)-point)/.0001
    radial = -np.sum(point*velocity, axis=1)/np.linalg.norm(point, axis=1)
    # A relative speed <=12m/s bounds the unsampled half-interval by 6*step.
    lower=.40;refined_near=0
    if possible:
        possible=np.concatenate(possible);a,b=possible[:,0].astype(int),possible[:,1].astype(int)
        refined,at,bounds,observed=resource.contact_intervals(a,b,possible[:,2],step,.40)
        minimum=min(minimum,float(observed));lower=min(lower,float(bounds.min()));refined_near=int(np.count_nonzero(refined<.315))
    result = dict(sha256=hashlib.sha256(Path(asset).read_bytes()).hexdigest(), members=12288,
                  stepSeconds=step, minimumCenterSpacing=minimum, minimumAt=time_at,
                  continuousSpacingLowerBound=lower,refinedNearOverlapPairs=refined_near,
                  centerNearOverlapSamples=near, coreChannels=int(core.sum()),
                  maximumCoreClosestXZ=float(closest_xz[core].max()),
                  maximumPeripheralClosest3D=float(closest_3d[~core].max()),
                  highestInitialY=float(starts[:, 1].max()), belowPlaneChannels=int(below.sum()),
                  minimumY=float(lowest), minimumInwardSpeedAtHalfSecond=float(radial.min()),
                  maximumDuration=float(metadata[:, 2].max()), assetBytes=Path(asset).stat().st_size)
    result['minimumNodeDerivative'] = float(np.linalg.norm(resource.nodes[:, 4:7], axis=1).min())
    result['minimumArcAfterClosest'] = float(np.min(metadata[:,2]*6-resource.closest_arcs()))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--asset', default='src/main/resources/data/createmanaindustry/dome-template-v3.bin')
    parser.add_argument('--output', default='build/dome-v13/dense-reference.json')
    parser.add_argument('--step', type=float, default=.01)
    parser.add_argument('--require-clearance', type=float, default=.34)
    args = parser.parse_args()
    result = validate(args.asset, args.step)
    Path(args.output).write_text(json.dumps(result, indent=2))
    print(result, flush=True)
    assert result['continuousSpacingLowerBound'] >= args.require_clearance, 'Encoded trajectories still intersect'
    assert result['maximumCoreClosestXZ'] <= 1.06
    assert result['maximumPeripheralClosest3D'] < 8.06
    assert result['highestInitialY'] >= 31.5 and result['maximumDuration'] <= 60
    assert result['minimumNodeDerivative'] > .001 and result['belowPlaneChannels'] <= 768
    assert result['minimumArcAfterClosest'] >= 2.999
