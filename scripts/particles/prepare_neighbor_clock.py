"""Detached instrumentation only. ARB shader-clock counts have no milliseconds/FPS meaning."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

HEADER='''#extension GL_ARB_shader_clock : require
layout(rgba32ui,binding=7) coherent uniform uimageBuffer cmiClockImage;
void cmiClockAdd(uint category,uvec2 begin,uint work){
 uvec2 end=clock2x32ARB(),elapsed=end-begin;elapsed.y-=uint(end.x<begin.x);
 uint at=gl_GlobalInvocationID.x*4u+category;if(at>=uint(imageSize(cmiClockImage)))return;uvec4 old=imageLoad(cmiClockImage,int(at));uvec2 sum=old.xy+elapsed;sum.y+=uint(sum.x<old.x);
 imageStore(cmiClockImage,int(at),uvec4(sum,old.z+1u,old.w+work));memoryBarrierImage();
}
'''


def wrap_main(text,category):
    old='void main()'
    if text.count(old)!=1:
        raise ValueError('main function changed')
    return text.replace(old,'void cmiClockMain()',1)+f'\nvoid main(){{uvec2 begin=clock2x32ARB();cmiClockMain();cmiClockAdd({category}u,begin,1u);}}\n'


def wrap_function(text,signature,result_type,name,argument):
    if text.count(signature)!=1:raise ValueError('terrain function changed: '+name)
    start=text.index(signature);brace=text.index('{',start);depth=1;end=brace+1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    body=text[start:end].replace(signature,signature.replace(name,name+'Impl'),1)
    wrapper=f'\n{signature}{{uvec2 begin=clock2x32ARB();{result_type} result={name}Impl({argument});cmiClockAdd(1u,begin,1u);return result;}}\n'
    return text[:start]+body+wrapper+text[end:]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('baseline',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    source=args.baseline/'src/main/resources/assets/createmanaindustry/shaders'
    target=args.output/'assets/createmanaindustry/shaders';shutil.copytree(source,target)
    root=target/'particles'
    for path in root.rglob('*.comp'):
        path.write_text(HEADER+path.read_text(encoding='utf8'),encoding='utf8')
    path=root/'update.comp';path.write_text(wrap_main(path.read_text(),3))
    path=root/'chunks/dome_contacts.glsl';path.write_text(wrap_main(path.read_text(),2))
    path=root/'chunks/storm_motion.glsl';text=path.read_text()
    start='float separationRadius=STORM_SEP_R;';end='sepW *= STORM_W_SEP;'
    if text.count(start)!=1 or text.count(end)!=1:raise ValueError('separation boundary changed')
    text=text.replace(start,'uvec2 cmiSepBegin=clock2x32ARB();'+start).replace(end,'cmiClockAdd(0u,cmiSepBegin,uint(sepVisits));'+end)
    path.write_text(text)
    path=root/'chunks/storm_collision.glsl';text=path.read_text()
    for signature,result_type,name,argument in [('CmiStormBody cmiStormBody(vec3 feet)','CmiStormBody','cmiStormBody','feet'),('int cmiStormLookupPage(ivec3 key)','int','cmiStormLookupPage','key')]:
        text=wrap_function(text,signature,result_type,name,argument)
    path.write_text(text)
    manifest={'instrumented':True,'units':'implementation-dependent invocation clock ticks; never convert to milliseconds',
              'categories':['separation','terrain_body_and_page_nested','dome_contact_main','update_main_including_other_categories'],
              'warning':'nested categories, divergent execution and changed register pressure: sums are not elapsed GPU time',
              'files':{str(p.relative_to(args.output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()}}
    (args.output/'clock-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')


if __name__=='__main__':main()
