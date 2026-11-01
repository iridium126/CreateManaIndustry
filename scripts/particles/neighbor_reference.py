"""Independent complete/swept query oracle and bounded streaming consumer.

No old traversal order/budget is a correctness oracle. This is diagnostic tooling,
not a replacement for production attack, contact resolution or event publication.
"""
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct


@dataclass(frozen=True)
class Member:
    identity: int
    position: tuple
    velocity: tuple = (0., 0., 0.)
    applicable: bool = True


def complete_candidates(members, query, radius, horizon=0.):
    """Conservative broad phase for the entire relative linear sweep.

    Exact production priority/phase/terrain rules must run after this query.
    Double precision and outward error padding retain FP32 boundary candidates.
    """
    if not math.isfinite(radius) or radius < 0 or not math.isfinite(horizon) or horizon < 0:
        raise ValueError('invalid radius/horizon')
    for other in members:
        if other.identity == query.identity or not other.applicable:
            continue
        delta=tuple(a-b for a,b in zip(query.position,other.position))
        velocity=tuple(a-b for a,b in zip(query.velocity,other.velocity))
        if not all(math.isfinite(v) for v in (*delta,*velocity)):
            raise ValueError('nonfinite member state')
        vv=sum(v*v for v in velocity)
        closest=max(0.,min(horizon,-sum(d*v for d,v in zip(delta,velocity))/vv)) if vv else 0.
        distance=math.sqrt(sum((d+v*closest)**2 for d,v in zip(delta,velocity)))
        padding=(sum(abs(d)+abs(v)*horizon for d,v in zip(delta,velocity))+radius)*2**-21
        if distance <= radius+padding:
            yield other.identity


def candidate_batches(members, queries, radius, horizon, capacity):
    """Yield bounded chunks, including multiple chunks for a single dense query.

    Never publish a partial list as complete. Empty rows still have an end marker.
    """
    if capacity < 1:
        raise ValueError('positive batch capacity required')
    identities=[m.identity for m in members]
    if len(set(identities)) != len(identities):
        raise ValueError('duplicate stable identities')
    for query in queries:
        chunk=[]
        for candidate in complete_candidates(members,query,radius,horizon):
            if len(chunk)==capacity:
                yield query.identity,False,tuple(chunk)
                chunk=[]
            chunk.append(candidate)
        yield query.identity,True,tuple(chunk)


def most_dangerous(candidates):
    """Positive penalty first; ties go to the lower stable identity, independent of order."""
    best=None
    for identity,penalty in candidates:
        if not math.isfinite(penalty):
            raise ValueError('nonfinite contact penalty')
        if penalty>0 and (best is None or penalty>best[1] or penalty==best[1] and identity<best[0]):
            best=(identity,penalty)
    return best


def reduction_error(terms,observed):
    """FP64 fsum reference for FP32 contribution reduction, including cancellation.

    Bounds only addition error. Callers separately validate contribution generation
    (sqrt/normalization/overlap rules), and must not hide NaNs inside this tolerance.
    """
    if not all(math.isfinite(v) for v in (*terms,observed)):
        raise ValueError('nonfinite force')
    n=max(0,len(terms)-1);unit=2**-24
    if n*unit>=1:
        raise ValueError('sum too long for gamma bound; use segmented reduction')
    reference=math.fsum(terms)
    bound=(n*unit)/(1-n*unit)*math.fsum(abs(v) for v in terms)+len(terms)*2**-149
    return abs(observed-reference),bound


def load_capture(directory,include_corpses=True):
    """Verify the committed manifest before exposing frozen MODEL positions/velocities."""
    directory=Path(directory).resolve();manifest=json.loads((directory/'manifest.json').read_text())
    if manifest.get('complete') is not True:
        raise ValueError('incomplete input freeze')
    files={}
    for name,expected in manifest['sha256'].items():
        path=(directory/name).resolve()
        if not path.is_relative_to(directory):raise ValueError('capture path escapes directory')
        data=path.read_bytes()
        if hashlib.sha256(data).hexdigest()!=expected:raise ValueError('input checksum mismatch: '+name)
        files[name]=data
    def binding(index):
        desc=manifest['ssbo'][str(index)];data=files[desc['file']];offset=desc['start'];size=desc['size']
        return memoryview(data)[offset:offset+size if size else None]
    pool,emitters,previous=binding(0),binding(5),binding(14)
    live=struct.unpack_from('<I',previous)[0]
    if live>len(pool)//64:raise ValueError('invalid population count')
    members=[]
    for i in range(live):
        p=struct.unpack_from('<16f',pool,i*64);emitter=struct.unpack_from('<I',pool,i*64+60)[0]
        # Read the recorded stride rather than guessing a production ABI.
        stride=manifest.get('emitter_vec4_stride')
        if stride is None:raise ValueError('capture lacks emitter stride; cannot infer a production ABI')
        base=emitter*stride*16
        if base+(19*16)>len(emitters):raise ValueError('emitter range outside frozen input')
        material=struct.unpack_from('<f',emitters,base+7*16)[0]
        storm=struct.unpack_from('<f',emitters,base+18*16)[0]
        if 1.5<=material<2.5 and storm>=.5:
            identity=(int(p[3]+.5)&262143)-1
            members.append(Member(identity,tuple(p[:3]),tuple(p[4:7]),include_corpses or p[13]>0))
    if len({m.identity for m in members})!=len(members):raise ValueError('duplicate storm identity in capture')
    return manifest,members
