"""Keep fixed-input topology timings separate from whole-frame qualification."""
import csv
import json
import math
import statistics
from pathlib import Path


def summarize(directory):
    directory = Path(directory)
    metadata = json.loads((directory / 'topology.json').read_text(encoding='utf-8'))
    groups = {}
    paired = {}
    errors = []
    row_count = 0
    owned = []
    with (directory / 'topology.csv').open(newline='', encoding='utf-8-sig') as stream:
        for row in csv.DictReader(stream):
            row_count += 1
            owned.append(int(row['owned_bytes']))
            step, scenario = int(row['step']), int(row['scenario'])
            strategy = row['strategy']
            pair = paired.setdefault(step, {})
            if strategy in pair:
                errors.append(f'duplicate strategy at step {step}')
            pair[strategy] = (scenario, row['members'], row['terrain'], row['receipt'])
            if int(row['receipt']) or int(row['delay_ms']) or step < 10:
                continue
            group = groups.setdefault((scenario, strategy), {name: [] for name in ('ias_ms', 'probe_ms', 'query_ms')})
            values = [float(row[name]) for name in ('input_wait_ms', 'prepare_ms', 'ias_ms', 'probe_ms', 'seal_ms')]
            if not all(math.isfinite(value) and value >= 0 for value in values):
                errors.append(f'invalid GPU timing at step {step}')
                continue
            group['ias_ms'].append(values[2]);group['probe_ms'].append(values[3]);group['query_ms'].append(sum(values))
    for step, pair in paired.items():
        if set(pair) != {'rebuild', 'reference_refit'} or pair['rebuild'] != pair['reference_refit']:
            errors.append(f'input or completion mismatch at step {step}')
    if row_count != metadata['submitted']:
        errors.append('timing receipts do not cover every submitted query')
    def distribution(values):
        ordered = sorted(values)
        if not ordered:
            return {'samples': 0}
        return {'samples': len(values), 'mean': statistics.mean(values),
                **{name: ordered[min(len(ordered)-1, math.ceil(q*len(ordered))-1)] for name, q in [('p50', .5), ('p95', .95), ('p99', .99)]}}
    results = [{'scenario': scenario, 'strategy': strategy, **{name: distribution(values) for name, values in group.items()}}
               for (scenario, strategy), group in sorted(groups.items())]
    conserved = metadata['submitted'] == metadata['consumed'] == metadata['retired'] and metadata['owned_bytes_after_close'] == 0
    return {'passed': metadata['passed'] and conserved and not errors,
            'long_control_complete': metadata['elapsed_seconds'] >= 900 and len({key[0] for key in groups}) == 6,
            'lifecycle_conserved': conserved, 'errors': errors, 'results': results,
            'native_owned_bytes': {'min': min(owned, default=0), 'max': max(owned, default=0)},
            'scope': 'serial fixed-input queries; GPU waits/packing and draws are not whole-frame measurements',
            'performance_gate': False, 'complete_plan_replay': False, 'client_acceptance': 'pending'}
