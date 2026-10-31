"""Join per-pack measurements, replacing complete packs only; preserve provenance."""
import argparse,csv,hashlib,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('inputs',nargs='+',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
manifest={}
for name in ['production.csv','compile.csv','startup.csv','draw.csv']:
    batches={};fields=None
    for directory in a.inputs:
        source=directory/name
        with source.open(encoding='utf8') as f:
            reader=csv.DictReader(f);fields=reader.fieldnames;rows=list(reader)
        for pack in {r['pack'] for r in rows}:
            batches[pack]=[r for r in rows if r['pack']==pack]
            manifest.setdefault(name,{})[pack]={'source':str(source),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
    with (a.output/name).open('w',encoding='utf8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader()
        for pack in sorted(batches):w.writerows(batches[pack])
(a.output/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8')
