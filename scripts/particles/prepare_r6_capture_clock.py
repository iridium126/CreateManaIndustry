"""Stable-member clock localization on real update captures, never formal timing."""
import argparse,json,shutil
from pathlib import Path
from prepare_neighbor_clock import HEADER,wrap_main,wrap_function

def prepare(root):
    target=root/'clock';shutil.copytree(root/'production',target)
    particles=target/'assets/createmanaindustry/shaders/particles'
    header=HEADER.replace('uint at=gl_GlobalInvocationID.x*4u+category;','if(r6ClockIndex>=131072u)return;uint at=r6ClockIndex*4u+category;')
    header=header.replace('layout(rgba32ui','uint r6ClockIndex=0xffffffffu;\nlayout(rgba32ui')
    path=particles/'update.comp';text=header+path.read_text();text=wrap_main(text,3)
    marker='bool isModel = matId >= 1.5 && matId < 2.5;'
    if text.count(marker)!=1:raise ValueError('update material boundary changed')
    text=text.replace(marker,marker+'''\nif(isModel&&uModelPass==2u){r6ClockIndex=cmiStarMember(p0.w);if(r6ClockIndex<131072u)imageStore(cmiClockImage,int(r6ClockIndex*4u+2u),uvec4(idx,cmiStarAssignment(p0.w),uStarfallEnabled?(CMI_STATE(starStateRead,r6ClockIndex*2u+1u)&255u):0u,floatBitsToUint(p3.y)));}\n''')
    path.write_text(text)
    path=particles/'chunks/storm_motion.glsl';text=path.read_text();text=text.replace('float separationRadius=STORM_SEP_R;','uvec2 r6SepBegin=clock2x32ARB();float separationRadius=STORM_SEP_R;').replace('sepW *= STORM_W_SEP;','cmiClockAdd(0u,r6SepBegin,uint(sepVisits));sepW *= STORM_W_SEP;');path.write_text(text)
    path=particles/'chunks/storm_collision.glsl';text=path.read_text()
    for signature,result_type,name,argument in [('CmiStormBody cmiStormBody(vec3 feet)','CmiStormBody','cmiStormBody','feet'),('int cmiStormLookupPage(ivec3 key)','int','cmiStormLookupPage','key')]:text=wrap_function(text,signature,result_type,name,argument)
    path.write_text(text)
    meta=json.loads((target/'variant.json').read_text());meta.update(variant='clock',instrumented=True,units='invocation clock ticks; never milliseconds or FPS',categories=['separation','nested terrain','metadata, not ticks','whole update including other categories']);(target/'variant.json').write_text(json.dumps(meta,indent=2)+'\n')
    shutil.copytree(target,root/'clock-trace')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);args=parser.parse_args();prepare(args.root)
