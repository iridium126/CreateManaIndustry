"""Extract completed client telemetry as data; log text is never executed."""
import argparse,hashlib,json,re
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('log',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    raw=a.log.read_bytes();captures=[]
    for line in raw.decode('utf8',errors='replace').splitlines():
        if '[CMI profile]' not in line or 'frames=240/240; details' not in line:continue
        text=line.replace('\\n','\n');parts=text.split('\n')
        header=next((s for s in parts if s.startswith('Capture frames=')),None)
        if not header:continue
        match=re.search(r'Capture frames=(\d+)\.\.(\d+); (\d+)x(\d+); pack=(.*?); source=(.*?); options=(.*?)(?:; session=(.*?))?; world=(.*)',header)
        if not match:raise ValueError(header)
        start,end,width,height,pack,source,options,session,world=match.groups()
        session=session or 'unavailable'
        entry=dict(time=text.split(']')[0].lstrip('['),frames=[int(start),int(end)],size=[int(width),int(height)],pack=pack,source=source,options=options,session=session,world=world,metrics={},records=[])
        for s in parts:
            if not (s.startswith('MODEL ') or s.startswith('Update ') or s.startswith('Simulation input counts:') or s.startswith('Storm LOD:') or s.startswith('LOD1:')):continue
            entry['records'].append(s)
            m=re.search(r'^(MODEL (?:body|carrier|ghost|feather|shadow)|Update [a-z_]+):.*?GPU p50/p95=([\d.]+)/([\d.]+) ms',s)
            if m:entry['metrics'][m[1]]=[float(m[2]),float(m[3])]
            if s.startswith('MODEL submitted counts:'):
                entry['submitted']=list(map(int,re.search(r'=(\d+/\d+/\d+/\d+/\d+/\d+);',s)[1].split('/')))
        entry['options_are_active']=pack.strip().strip('()').lower() not in ('off','none','disabled','pending')
        if not entry['options_are_active']:
            entry['historical_source']=entry['source'];entry['historical_options']=entry['options']
            entry['source']='none';entry['options']='unavailable'
        captures.append(entry)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(dict(log_sha256=hashlib.sha256(raw).hexdigest(),captures=captures),ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print('Completed captures:',len(captures))

if __name__=='__main__':main()
