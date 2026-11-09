"""Audit ABI 12/13 lifecycle CSVs without hiding late work or mixing worker ABIs."""
from __future__ import annotations
import csv
from collections import deque


def audit(path, require_drained=False):
    pending = {}
    owners = {}
    records = {}
    errors = []
    carry_in = set()
    peak = submitted = consumed = retired = 0
    peaks = {}
    by_kind = {}
    worker_abis = {}
    with open(path, newline='', encoding='utf-8-sig') as stream:
        for line, row in enumerate(csv.DictReader(stream), 2):
            worker = (int(row.get('worker_epoch', 0)), row.get('query_kind', 'motion'))
            queue = pending.setdefault(worker, deque())
            key = (*worker, int(row['scene_epoch']), int(row['nonce']))
            slot = (*worker, int(row['slot']))
            kind_counts = by_kind.setdefault(worker[1], dict(submitted=0, consumed=0, retired=0))
            event = row['event']
            abi = int(row['native_abi'])
            if abi not in (12, 13):
                errors.append(f'{line}: unsupported native ABI {abi}')
            if worker_abis.setdefault(worker, abi) != abi:
                errors.append(f'{line}: native ABI changed within one worker')
            seen = records.setdefault(key, set())
            if event == 'submitted':
                if seen or slot in owners:
                    errors.append(f'{line}: duplicate submission or premature slot reuse')
                owners[slot] = key
                queue.append(key)
                submitted += 1
                kind_counts['submitted'] += 1
                occupied = sum(owner[:2] == worker for owner in owners)
                peaks[worker] = max(peaks.get(worker, 0), occupied)
                peak = max(peak, occupied)
            elif event in ('consumed', 'consumed_without_publication'):
                if 'consumed' in seen:
                    errors.append(f'{line}: duplicate consumption')
                if 'submitted' not in seen:
                    carry_in.add(key)
                elif not queue or queue[0] != key:
                    errors.append(f'{line}: consumption out of submission order')
                else:
                    queue.popleft()
                seen.add('consumed')
                consumed += 1
                kind_counts['consumed'] += 1
            elif event == 'retired':
                if 'retired' in seen:
                    errors.append(f'{line}: duplicate retirement')
                if 'consumed' not in seen:
                    if not seen:
                        carry_in.add(key)
                    else:
                        errors.append(f'{line}: retirement before consumption')
                if owners.get(slot) == key:
                    del owners[slot]
                retired += 1
                kind_counts['retired'] += 1
            else:
                errors.append(f'{line}: unsupported lifecycle event {event}')
            seen.add(event)
    if peak > 3:
        errors.append('more than three live slots')
    unfinished = [key for key, seen in records.items() if 'submitted' in seen and 'retired' not in seen]
    if require_drained and (unfinished or carry_in):
        errors.append('drained evidence must include every submission, consumption and retirement')
    return {'passed': not errors, 'submitted': submitted, 'consumed': consumed, 'retired': retired,
            'peak_slots': peak, 'workers': len(pending), 'by_query_kind': by_kind,
            'carry_in': len(carry_in), 'unfinished': len(unfinished),
            'drained': not unfinished and not carry_in, 'errors': errors,
            'scope': 'lifecycle only; does not qualify motion, visibility or whole-frame performance'}
