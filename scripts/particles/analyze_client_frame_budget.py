"""Same-frame attribution, never an optimization FPS claim. Missing ranges remain unknown."""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path

# These three ranges are disjoint at the current production call sites.
# Children (body/ghost, shadow prepare/draw, update.*) are intentionally excluded.
SIBLINGS = ('scene.compute', 'draw.pack.merge', 'draw.pack.model-shadow')
# Confirmed disjoint children inside beginTranslucents in this Iris/bridge build.
BEGIN_CHILDREN = ('iris.translucents.depth-copy', 'iris.whole-stage.deferred',
                  'irisveil.before-deferred.callbacks')


def difference(ranges, parent, children):
    values = [ranges.get(k, math.nan) for k in (parent, *children)]
    return values[0]-sum(values[1:]) if all(math.isfinite(v) for v in values) else math.nan


def summarize(values):
    values = sorted(v for v in values if math.isfinite(v))
    if not values:
        return {'samples': 0}
    return dict(samples=len(values), **{f'p{p}': values[math.ceil(len(values)*p/100)-1] for p in (50, 95, 99)})


def analyze(path, derived_output=None):
    path = Path(path)
    metadata = json.loads(path.read_text(encoding='utf-8'))
    if metadata.get('invalid_reason'):
        raise ValueError('invalid capture: '+metadata['invalid_reason'])
    with path.with_suffix('.csv').open(newline='') as stream:
        rows = list(csv.DictReader(stream))
    frames = {r['scene_frame']: r for r in rows if int(r['scene_frame']) >= 0}
    if len(frames) != len(rows):
        raise ValueError('missing/duplicate scene-frame tags')
    by_frame = {key: {} for key in frames}
    with path.with_name(path.stem+'-stages.csv').open(newline='') as stream:
        for r in csv.DictReader(stream):
            key = r['scene_frame']
            if key not in frames:
                raise ValueError('stage has no matching main frame')
            if r['stage'] in by_frame[key]:
                raise ValueError('duplicate stage row')
            # scene.compute covers a candidate; it can report generation -1.
            # Published draw ranges must match the census generation exactly.
            if r['stage'].startswith('draw.') and r['generation'] != frames[key]['published_generation']:
                raise ValueError('draw/census generation conflict')
            # Repeated scopes can exhaust a non-blocking query ring. A partial
            # sum cannot represent the whole stage, including older captures.
            complete = ('cpu_ranges' not in r or int(r['cpu_ranges']) == 0 or
                        int(r['cpu_ranges']) == int(r['gpu_ranges']))
            by_frame[key][r['stage']] = float(r['gpu_ms']) if complete else math.nan
    labels = sorted({k for ranges in by_frame.values() for k in ranges})
    stages = {label: summarize(ranges.get(label, math.nan) for ranges in by_frame.values()) for label in labels}
    residual = []; outside_compute = []; outside_world = []; world_unsectioned = []; inconsistent = 0
    derived = []
    for key, ranges in by_frame.items():
        derived.append(dict(scene_frame=key,
            begin_unexplained_ms=difference(ranges, 'iris.translucents.begin', BEGIN_CHILDREN),
            begin_outside_rift_ms=difference(ranges, 'iris.translucents.begin', (*BEGIN_CHILDREN,'rift.visual.whole')),
            rift_visual_outside_target_ms=difference(ranges, 'rift.visual.whole', ('rift.visibility.depth','rift.target.whole')),
            solid_outside_begin_ms=difference(ranges, 'world.section.solid_particles', ('iris.translucents.begin',)),
            merge_outer_ms=difference(ranges, 'submission.pack.merge-whole', ('draw.pack.merge',))))
        total = float(frames[key]['gpu_ms'])
        compute = ranges.get('scene.compute', math.nan)
        world = ranges.get('world.render', math.nan)
        if math.isfinite(world) and math.isfinite(total):
            outside_world.append(total-world)
            sections=[v for k,v in ranges.items() if k.startswith('world.section.')]
            # Do not assume an absent section did no work; this is the remainder of reported sections only.
            if sections and all(math.isfinite(v) for v in sections):world_unsectioned.append(world-sum(sections))
        if math.isfinite(total) and math.isfinite(compute):
            outside_compute.append(total-compute)
        if math.isfinite(total) and all(math.isfinite(ranges.get(k, math.nan)) for k in SIBLINGS):
            remainder = total-sum(ranges[k] for k in SIBLINGS)
            if remainder < 0:
                inconsistent += 1
            else:
                residual.append(remainder)
    intervals = [float(r['interval_ms']) for r in rows]
    if not intervals or not all(math.isfinite(v) and v > 0 for v in intervals):
        raise ValueError('invalid frame intervals')
    if derived_output is not None:
        with Path(derived_output).open('x', newline='', encoding='utf-8') as stream:
            writer=csv.DictWriter(stream, fieldnames=derived[0].keys())
            writer.writeheader()
            writer.writerows({k: '' if isinstance(v, float) and not math.isfinite(v) else v
                              for k,v in row.items()} for row in derived)
    return dict(metadata=metadata, frames=len(rows), average_fps=1000/statistics.mean(intervals),
                same_frame_differences={k:summarize(r[k] for r in derived) for k in derived[0] if k!='scene_frame'},
                required_saving_ms_for_20_percent=statistics.mean(intervals)/6,
                stages=stages, outside_compute_ms=summarize(outside_compute),
                outside_world_ms=summarize(outside_world), world_minus_reported_sections_ms=summarize(world_unsectioned),
                uncovered_ms=summarize(residual), uncovered_siblings=SIBLINGS,
                inconsistent_budget_frames=inconsistent,
                warning='Missing stages are unknown, never zero. Stage percentiles overlap; do not add them. '
                        'Residual includes other rendering, waits and uninstrumented CMI work; it is not shaderpack-exclusive.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--derived-frames', type=Path, help='Exclusive CSV; empty cells mean unknown, not zero')
    args = parser.parse_args()
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(analyze(args.capture, args.derived_frames), stream, indent=2, allow_nan=False)
        stream.write('\n')
