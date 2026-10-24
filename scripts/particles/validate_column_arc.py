"""Offline geometric comparison of the frozen 33-node cubic inverse and 65-node linear inverse.

This does not replace real GPU lifecycle/recovery validation. Requires NumPy.
"""
import argparse
import csv
import json
from pathlib import Path
import numpy as np


def curve(u, radius, height, angle, rank, direction, seed, duration):
    root = np.sqrt(.04 + 1.92 * (rank + .5) / 64)
    end = angle + .7 * duration
    end += ((rank * 27 % 64) * np.pi / 32 + seed * .0001 - end + np.pi) % (2*np.pi) - np.pi
    radial = np.array([np.cos(angle), np.sin(angle)])
    dr = np.clip(np.dot(direction[[0, 2]], radial) * 4, -.25*radius, .25*radius)
    angular = np.dot(direction[[0, 2]], [-radial[1], radial[0]]) * 4 / radius
    h0, h1 = u*(1-u)**2, u*u*(u-1)
    blend = u*u*u*(10 + u*(-15 + 6*u))
    r = np.maximum(.2, radius+(root-radius)*blend + dr*h0 + .35*h1)
    a = angle+(end-angle)*blend + angular*h0
    y = height+(.0625*root*root-height)*blend + direction[1]*4*h0 + .35*root*.125*h1
    return np.stack([np.cos(a)*r, y, np.sin(a)*r], axis=-1)


def compare(parameters):
    def point(u):
        return curve(u, *parameters)
    samples = point(np.arange(129)/128)
    arc = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(samples, axis=0), axis=1))])
    old = arc[::4]
    ratio = np.clip((parameters[1]/parameters[0]-1)/3,0,1)
    strength = .1+.1*ratio*ratio*(3-2*ratio)
    def warp(t):
        return t+strength*t*(1-t)*(1-2*t)*(1-7*t+7*t*t)
    warped = point(warp(np.arange(129)/128))
    new_arc = np.concatenate([[0],np.cumsum(np.linalg.norm(np.diff(warped,axis=0),axis=1))])
    new = new_arc[::2]
    knots = np.arange(33)/32
    lower, upper = np.maximum(0, knots-1/1024), np.minimum(1, knots+1/1024)
    metric = np.linalg.norm(point(upper)-point(lower), axis=1)/(upper-lower)
    distance = arc[-1] * np.linspace(0, 1, 2049)
    lo = np.clip(np.searchsorted(old, distance, side="left")-1, 0, 31)
    delta = np.maximum(old[lo+1]-old[lo], .000001)
    goal = distance-old[lo]
    q = np.clip(goal/delta, 0, 1)
    m0, m1 = np.clip(metric[lo]/32, .000001, 3*delta), np.clip(metric[lo+1]/32, .000001, 3*delta)
    a, b = m0+m1-2*delta, 3*delta-2*m0-m1
    for _ in range(3):
        value, derivative = ((a*q+b)*q+m0)*q, (3*a*q+2*b)*q+m0
        q = np.clip(q-(value-goal)/np.maximum(derivative, .000001), 0, 1)
    old_u = (lo+q)/32
    old_u[0], old_u[-1] = 0, 1
    new_u = np.interp(distance, new, warp(np.arange(65)/64))
    return float(np.max(np.linalg.norm(point(old_u)-point(new_u), axis=1)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/cold-escape/column-arc")
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    directions = [np.array(v, dtype=float) for v in [(1,0,0), (-1,0,0), (0,1,0), (0,-1,0), (0,0,1), (0,0,-1), (1,-1,1)]]
    directions = [v/np.linalg.norm(v) for v in directions]
    rows = []
    for rank in range(64):
        for radius in [.2, 1, 4, 8, 16, 32]:
            for height in [1, 8, 32, 64, 128]:
                for index, direction in enumerate(directions):
                    seed = [1, 123, 10000][rank % 3]
                    angle = rank * .73
                    duration = max(.1, 7.28+rank*.06)
                    error = compare((radius, height, angle, rank, direction, seed, duration))
                    rows.append([rank, radius, height, index, error])
    maximum = max(row[-1] for row in rows)
    with (out/"matrix.csv").open("w", newline="") as file:
        writer = csv.writer(file);writer.writerow(["rank", "radius", "height", "direction", "max_position_error"]);writer.writerows(rows)
    result = {"curves": len(rows), "samples_per_curve": 2049, "maximum_position_error": maximum, "limit": .05, "passed": maximum <= .05}
    (out/"summary.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
    if maximum > .05:
        raise SystemExit("Arc inverse exceeded the approved geometric tolerance")


if __name__ == "__main__":
    main()
