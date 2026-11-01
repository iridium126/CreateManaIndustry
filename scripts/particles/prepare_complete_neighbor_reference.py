"""Detached streaming reference candidate. Never overwrite/replace production shaders."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def once(text,old,new):
    if text.count(old)!=1:raise ValueError('Source contract changed: '+old[:60])
    return text.replace(old,new)


def main():
    p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=False)
    target=a.output/'assets/createmanaindustry/shaders';shutil.copytree(a.baseline/'src/main/resources/assets/createmanaindustry/shaders',target)
    root=target/'particles/chunks'
    path=root/'storm_motion.glsl';text=path.read_text()
    text=once(text,'''                if (++sepVisits > STORM_MAX_VISITS) {
                    sepBudgetGone = true;
                    break;
                }''','                ++sepVisits;')
    text=once(text,'vec3 op = readBuf.data[uint(j) * 4u].xyz;','vec3 op = readBuf.data[uint(j) * 4u].xyz;\n                if(any(notEqual(ivec3(floor(op/CELL_SIZE)),c3)))continue;')
    path.write_text(text)
    path=root/'dome_contacts.glsl';text=path.read_text()
    text=once(text,'if(++visits>768)break;','++visits;')
    text=once(text,'if(++visits>768){exhausted=true;break;}','++visits;')
    text=once(text,'vec4 other=pool.data[j*4u],otherLife=pool.data[j*4u+3u];','vec4 other=pool.data[j*4u],otherLife=pool.data[j*4u+3u];if(any(notEqual(ivec3(floor(other.xyz/CMI_BODY_CELL)),cell)))continue;')
    text=once(text,'vec4 other=pool.data[j*4u];if(distance','vec4 other=pool.data[j*4u];if(any(notEqual(ivec3(floor(other.xyz/CMI_BODY_CELL)),cell)))continue;if(distance')
    text=once(text,'float gatherWorst=0.;','float gatherWorst=0.;uint gatherWinner=0xffffffffu,blockingWinner=0xffffffffu;')
    text=once(text,'if(amount>gatherWorst){gatherWorst=amount;','if(amount>gatherWorst||(amount>0.&&amount==gatherWorst&&otherMember<gatherWinner)){gatherWinner=otherMember;gatherWorst=amount;')
    text=once(text,'if(penalty>blockingPenalty){blockingPenalty=penalty;','if(penalty>blockingPenalty||(penalty>0.&&penalty==blockingPenalty&&otherMember<blockingWinner)){blockingWinner=otherMember;blockingPenalty=penalty;')
    # Diagnostic low 16 bits cannot wrap into hazard/feasibility flags in dense full traversals.
    text=once(text,'=uint(visits)|(blockingPenalty','=min(uint(visits),65535u)|(blockingPenalty')
    path.write_text(text)
    (a.output/'reference-manifest.json').write_text(json.dumps(dict(kind='inline complete streaming reference; no visit/capacity truncation; experimental only',
        physics_qualification='pending; preliminary invariants and full-chain cost screen only',
        files={str(p.relative_to(a.output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()}),indent=2)+'\n')


if __name__=='__main__':main()
