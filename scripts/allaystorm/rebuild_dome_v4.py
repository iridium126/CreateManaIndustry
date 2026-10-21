"""Rebuild and independently certify the immutable canonical v4 resource.

Production GPU handoff tests remain a separate release gate. This command
never publishes a candidate directly into Minecraft's packaged resources.
"""
import argparse,hashlib,json
from pathlib import Path
from generate_dome_v4 import encode
from complete_dome_v4 import complete
from validate_dome_v4 import validate,require
from dome_v4_certificate import certify as kinematics
from dome_v4_space_certificate import certify as separation
from dome_v4_gather_certificate import certify as gather

def rebuild(source,output):
    output=Path(output).resolve()
    if not output.is_relative_to(Path('build').resolve()):raise ValueError('Candidate must remain in build/')
    output.mkdir(parents=True,exist_ok=True)
    draft=output/'untrimmed.bin';asset=output/'dome-template-v4.bin'
    encoding=encode(source,draft);complete(draft,asset)
    report=validate(asset,.02);require(report)
    for validator in (kinematics,separation,gather):report.update(validator(asset))
    report['sourceSha256']=hashlib.sha256(Path(source).read_bytes()).hexdigest()
    report['scope']='Canonical decoded curves and gather streamlines. Actual GPU handoffs require separate lifecycle validation.'
    report['encoding']=encoding
    (output/'dome-template-v4.validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',default='scripts/allaystorm/data/dome-v4-source.npz');p.add_argument('--output',default='build/dome-v14/rebuilt');a=p.parse_args();rebuild(a.source,a.output)
