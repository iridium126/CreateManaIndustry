"""Fail-closed full-chain replacement gate. Initial evidence is never replaced by a repeat."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered)-1, __import__('math').ceil(len(ordered)*fraction)-1)]


def load(directory, expected, samples):
    if (directory / 'rule-smoke-complete.txt').exists():
        raise ValueError(f'Rule smoke is not gameplay qualification: {directory}')
    for metadata in directory.glob('validateShaderEfficiencyUpdateGpu-*-metadata.json'):
        properties = json.loads(metadata.read_text())['properties']
        if str(properties.get('efficiencyRules', '')).lower() == 'true':
            raise ValueError(f'Rule smoke is not gameplay qualification: {directory}')
    if not (directory / 'complete.txt').is_file():
        raise ValueError(f'Incomplete/failed run: {directory}')
    groups = {}
    with (directory / 'samples.csv').open(newline='') as stream:
        for row in csv.DictReader(stream):
            key = (int(row['count']), int(row['mode']), row['scene'], row['chain'])
            groups.setdefault(key, []).append(row)
    if set(groups) != expected:
        raise ValueError(f'Missing/unexpected cells: {directory}')
    for key, rows in groups.items():
        if len(rows) != samples or sorted(int(r['sample']) for r in rows) != list(range(samples)):
            raise ValueError(f'Missing/duplicate samples: {directory} {key}')
        for row in rows:
            for name in ('baseline_ms', 'candidate_ms', 'baseline_cpu_ms', 'candidate_cpu_ms'):
                value = float(row[name])
                if not __import__('math').isfinite(value) or value <= 0:
                    raise ValueError(f'Invalid timing: {directory} {key}')
    return groups


def changes(rows):
    a = [float(r['baseline_ms']) for r in rows]
    b = [float(r['candidate_ms']) for r in rows]
    return dict(relative=statistics.median(y/x-1 for x, y in zip(a, b)),
                absolute=statistics.median(y-x for x, y in zip(a, b)),
                baseline_p50=percentile(a, .5), candidate_p50=percentile(b, .5),
                p95_relative=percentile(b, .95)/percentile(a, .95)-1,
                p95_absolute=percentile(b, .95)-percentile(a, .95))


def evaluate(ab_dirs, aa_dirs, expected, samples=64):
    if len(ab_dirs) != 3 or len(aa_dirs) != 3 or len(set(ab_dirs+aa_dirs)) != 6:
        raise ValueError('Require three independent A/B and three independent A/A processes')
    ab = [load(d, expected, samples) for d in ab_dirs]
    aa = [load(d, expected, samples) for d in aa_dirs]
    metrics, improvements, regressions = [], [], []
    for key in sorted(expected):
        controls = [changes(g[key]) for g in aa]
        noise_r = max(.005, *(abs(c['relative']) for c in controls))
        noise_a = max(.002, *(abs(c['absolute']) for c in controls))
        noise_p95_r = max(.005, *(abs(c['p95_relative']) for c in controls))
        noise_p95_a = max(.002, *(abs(c['p95_absolute']) for c in controls))
        rounds = [changes(g[key]) for g in ab]
        metric = dict(cell=key, rounds=rounds, noise_relative=noise_r, noise_ms=noise_a,
                      p95_noise_relative=noise_p95_r, p95_noise_ms=noise_p95_a)
        metrics.append(metric)
        if all(r['relative'] < -noise_r and r['absolute'] < -noise_a for r in rounds):
            improvements.append(key)
        if (all(r['relative'] > noise_r and r['absolute'] > noise_a for r in rounds)
                or all(r['p95_relative'] > noise_p95_r and r['p95_absolute'] > noise_p95_a for r in rounds)):
            regressions.append(key)
    full = bool(expected) and all(k[-1] == 'full' for k in expected)
    return dict(passed=full and bool(improvements) and not regressions,
                full_chain=full, improvements=improvements, regressions=regressions, metrics=metrics,
                note='Kernel-only evidence cannot authorize replacement. Loading/resources and client acceptance remain separate gates.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ab', nargs=3, type=Path, required=True)
    parser.add_argument('--aa', nargs=3, type=Path, required=True)
    parser.add_argument('--cells', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        expected = {tuple(k) for k in json.loads(args.cells.read_text())}
        result = evaluate(args.ab, args.aa, expected)
    except (ValueError, OSError, KeyError) as error:
        result = dict(passed=False, errors=[str(error)])
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf8')
    print('Replacement gate:', 'PASS (timing only)' if result['passed'] else 'NOT MET')
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
