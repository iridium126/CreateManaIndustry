"""Validate schema-3 full GPU command captures; never upgrade old snapshots."""
import argparse,hashlib,json
from pathlib import Path

def load(directory):
    root=Path(directory).resolve();data=json.loads((root/'manifest.json').read_text())
    if data.get('schema')!=3 or data.get('kind')!='gpu-command-chain' or data.get('complete') is not True or data.get('failure_reason'):
        raise ValueError('incomplete full-chain capture; old update snapshots are not a full chain')
    if data.get('storage_bytes',2**63)>512*1024**2 or data.get('byte_order')!='little-endian':raise ValueError('capture budget/byte-order mismatch')
    for name,expected in data['sha256'].items():
        path=(root/name).resolve()
        if not path.is_relative_to(root) or path.parent!=root:raise ValueError('capture file escaped its directory')
        h=hashlib.sha256()
        with path.open('rb') as source:
            for block in iter(lambda:source.read(1024**2),b''):h.update(block)
        if h.hexdigest()!=expected:raise ValueError('capture checksum mismatch: '+name)
    def file(name):
        if name not in data['sha256']:raise ValueError('unhashed resource payload')
        return (root/name).stat().st_size
    resources=data['resources'];sizes={}
    def buffer(name):
        if name not in sizes:raise ValueError('unknown/non-buffer resource '+name)
        return sizes[name]
    for name,r in resources.items():
        if r['type']=='buffer':
            if r['bytes']<0 or r['bytes']!=file(r['file']):raise ValueError('initial buffer length')
            sizes[name]=r['bytes']
        elif r['type']=='texture':
            if r['target']==35882:
                # Buffer views can point to a buffer allocated later in the stream.
                if r['buffer']=='none' or r['buffer'] not in resources:raise ValueError('invalid texture-buffer alias')
            elif file(r['file'])<=0:raise ValueError('empty texture')
        else:raise ValueError('unknown resource type')
    for program in data['programs'].values():
        if file(program['binary'])<=0:raise ValueError('empty program binary')
        if 'compute_source' in program and file(program['compute_source'])<=0:raise ValueError('empty captured source')
    commands=data['commands']
    if not commands or len(commands)>4096:raise ValueError('unbounded/empty command stream')
    def span(name,at,count):
        if at<0 or count<0 or at+count>buffer(name):raise ValueError('command buffer range')
    for e in commands:
        op=e['op'];a=e['args'];payload=file(e['payload']) if 'payload' in e else 0
        if op=='allocate':sizes[e['buffer']]=a[1]
        elif op=='allocate-upload':sizes[e['buffer']]=payload
        elif op=='upload':span(e['buffer'],a[1],payload)
        elif op=='copy':span(e['read'],a[2],a[4]);span(e['write'],a[3],a[4])
        elif op=='clear':buffer(e['buffer'])
        elif op=='clear-range':span(e['buffer'],a[2],a[3])
        elif op=='texture-upload':
            if e['texture'] not in resources or payload<=0:raise ValueError('texture upload resource/payload')
        elif op in ('dispatch','dispatch-indirect'):
            d=e['inputs']
            if d['program'] not in data['programs']:raise ValueError('unknown dispatch program')
            for binding in d['ssbo'].values():
                if binding['buffer']!='none':span(binding['buffer'],binding['offset'],binding['bytes'] or buffer(binding['buffer']))
            for image in d['images'].values():
                if image['texture']!='none' and image['texture'] not in resources:raise ValueError('image resource missing')
            if op=='dispatch-indirect':span(e['indirect'],a[0],12)
            elif len(a)!=3 or any(x<0 for x in a):raise ValueError('dispatch dimensions')
        elif op!='barrier':raise ValueError('unknown command '+op)
    p=data.get('publication')
    if data.get('require_publication') and (not p or p['generation']!=data['generation'] or p['population_generation']!=p['generation']):raise ValueError('missing/mismatched publication')
    if p:
        for name in ('pool','commands','counter','population'):buffer(p[name])
    return data

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);a=p.parse_args();d=load(a.directory)
    print(json.dumps(dict(valid=True,scope='recorded full compute command chain; not FPS',commands=len(d['commands']),resources=len(d['resources']))))
