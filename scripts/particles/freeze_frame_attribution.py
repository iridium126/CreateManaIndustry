"""Freeze selected user captures and referenced options; never overwrite evidence."""
import argparse
import csv
import hashlib
import json
import re
import shutil
import statistics
from pathlib import Path
from analyze_client_frame_budget import analyze, summarize


def freeze(captures, output, game):
    output.mkdir(parents=True, exist_ok=False)
    inputs=output/'inputs';inputs.mkdir()
    hashes={};reports=[]
    def preserve(path):
        destination=inputs/path.name
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        if destination.exists() and hashes[destination.name]['sha256']!=digest:
            raise ValueError('input filename collision')
        shutil.copy2(path,destination)
        hashes[destination.name]=dict(source=str(path.resolve()),sha256=digest)
    for path in captures:
        result=analyze(path);meta=result['metadata']
        for source in path.parent.glob(path.stem+'*'):
            if source.is_file():preserve(source)
        reference=meta.get('profile_context_file')
        if not reference:raise ValueError('missing options snapshot reference')
        source=Path(reference.replace(chr(92),'/'))
        if not source.is_absolute():source=game/source
        preserve(source)
        rows=list(csv.DictReader(path.with_suffix('.csv').open()))
        for label,suffix,fields in (
            ('population','.csv',('live','model_input','body_visible','carrier_visible','ghost_visible')),
            ('camera','-camera.csv',('x','y','z','yaw_degrees','pitch_degrees','vertical_fov_degrees')),
            ('shadow','-submissions.csv',('shadow_body','shadow_carrier','shadow_ghost'))):
            records=list(csv.DictReader(path.with_name(path.stem+suffix).open()))
            result[label]={}
            for key in fields:
                values=[float(r[key]) for r in records if label=='camera' or float(r[key])>=0]
                if values:result[label][key]=dict(min=min(values),median=statistics.median(values),max=max(values))
        result['attack_contracts']=dict(unique=len({r['attack_contract_stage'] for r in rows}),
            wave_count=summarize(float(len(re.findall(r'[0-9]+:(?:dome|starfall|helix)/',r['attack_contract_stage']))) for r in rows))
        result['capture']=path.name
        reports.append(result)
    (output/'input-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n',encoding='utf-8')
    (output/'analysis.json').write_text(json.dumps(reports,indent=2,allow_nan=False)+'\n',encoding='utf-8')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('captures',type=Path,nargs='+')
    parser.add_argument('--game',type=Path,default=Path('run'))
    args=parser.parse_args();freeze(args.captures,args.output,args.game)
