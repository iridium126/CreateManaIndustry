"""Input-cohort localization with stable member tags. Clock ticks are not GPU ms."""
import argparse,json
from pathlib import Path
import numpy as np

def summarize(captures,run):
    results=[]
    for capture in sorted(captures.iterdir()):
        if not (capture/'manifest.json').exists():continue
        manifest=json.loads((capture/'manifest.json').read_text());desc=manifest['ssbo']['26'];attack=np.memmap(capture/desc['file'],dtype='<u4',mode='r',offset=desc['start']);floats=attack.view('<f4');values=np.fromfile(run/capture.name/'clock/queries.bin',dtype='<u4').reshape(-1,4,4)
        groups={}
        for member in np.flatnonzero(values[:,3,2]):
            _,assignment,phase,hp=values[member,2];hp=np.array(hp,dtype='<u4').view('<f4').item()
            if hp<=0:group='corpse'
            elif assignment&0x40000000:group='Column'
            elif assignment==0:group='unassigned_return' if phase==6 else 'ordinary'
            else:
                kind=float(floats[131088+int(assignment>>1&3)*312+20]);group='Dome' if .5<kind<1.5 else 'Helix' if 1.5<kind<2.5 else 'Starfall'
            groups.setdefault(group,[]).append(int(member))
        for group,members in groups.items():
            for category,label in ((0,'separation'),(1,'nested_terrain'),(3,'whole_update_including_other_categories')):
                rows=values[members,category];rows=rows[rows[:,2]>0]
                if not len(rows):continue
                ticks=rows[:,0].astype('u8')+(rows[:,1].astype('u8')<<np.uint64(32));results.append(dict(capture=capture.name,cohort=group,category=label,members=len(members),invocations=len(rows),ticks_p50=float(np.median(ticks)),ticks_p95=float(np.quantile(ticks,.95)),calls_p50=float(np.median(rows[:,2])),work_p50=float(np.median(rows[:,3]))))
    report=dict(scope='instrumented input-cohort invocation clocks; nested/divergent scopes overlap; never subtract/sum as GPU elapsed time, never convert to milliseconds/FPS',results=results);(run/'clock-summary.json').write_text(json.dumps(report,indent=2)+'\n');return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('captures',type=Path);p.add_argument('run',type=Path);a=p.parse_args();r=summarize(a.captures,a.run);print(json.dumps(r,indent=2))
