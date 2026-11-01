"""Generate isolated unified R6 programs. Never mutate production shader assets."""
import argparse,hashlib,json,shutil
from pathlib import Path

HERE=Path(__file__).parent
VARIANTS={'production':(False,False,2.5,16384),'complete':(False,False,2.5,16384),'prune':(True,False,2.5,16384),'grid250':(True,True,2.5,16384),'grid250wide':(True,True,2.5,65536),'grid125':(True,True,1.25,65536),'grid100':(True,True,1.,65536),'order-space':(False,False,2.5,16384),'order-phase':(False,False,2.5,16384),'reuse-contact':(False,False,2.5,16384),'grid100-space':(True,True,1.,65536),'grid100-phase':(True,True,1.,65536),'grid100-space-reuse':(True,True,1.,65536)}
TRACE='''#define R6_QUERY_TRACE
layout(rgba32ui,binding=7) coherent uniform uimageBuffer r6Trace;
void r6Admit(uint member,uint other){if(member>=131072u||(member&127u)!=0u)return;int meta=int(member*4u+1u);uvec4 count=imageLoad(r6Trace,meta);if(other>=131072u){count.w++;imageStore(r6Trace,meta,count);memoryBarrierImage();return;}count.z++;imageStore(r6Trace,meta,count);int at=int(131072u*4u+(member>>7u)*1024u+(other>>7u));uvec4 bits=imageLoad(r6Trace,at);bits[(other>>5u)&3u]|=1u<<(other&31u);imageStore(r6Trace,at,bits);memoryBarrierImage();}
void r6Origin(uint m,uint i,vec3 p,float r,bool queryEnabled){if(m>=131072u)return;imageStore(r6Trace,int(m*4u),floatBitsToUint(vec4(p,r)));imageStore(r6Trace,int(m*4u+1u),uvec4(i,queryEnabled?1u:0u,0u,0u));memoryBarrierImage();}
void r6Force(uint m,vec3 f,uint visits){if(m<131072u)imageStore(r6Trace,int(m*4u+2u),uvec4(floatBitsToUint(f),visits));}
'''
def replace(s,a,b):
 if s.count(a)!=1:raise ValueError('Source contract changed: '+a[:90])
 return s.replace(a,b)
def prepare(baseline,out):
 out.mkdir(parents=True,exist_ok=False)
 for name,(prune,packed,size,buckets) in VARIANTS.items():
  target=out/name;shutil.copytree(baseline/'source/src/main/resources/assets',target/'assets')
  root=target/'assets/createmanaindustry/shaders/particles';update=(root/'update.comp').read_text();motion=(root/'chunks/storm_motion.glsl').read_text();dome=(root/'chunks/dome_contacts.glsl').read_text()
  if name not in ('production','order-space','order-phase','reuse-contact'):
   core=(HERE/'r6_query.glsl').read_text().replace('R6_CELL_LITERAL',str(size))
   defines=f'#define R6_PRUNE {str(prune).lower()}\n'
   at=update.index('} grid;')+len('} grid;');update=update[:at]+'\n#define R6_UPDATE\n'+defines+core+'\n#undef R6_UPDATE\n'+update[at:]
   start=motion.index('        int sepVisits = 0;');end=motion.index('        sepW *= STORM_W_SEP;',start)
   motion=motion[:start]+'''        int sepVisits=0;
        if(!domeMember)sepW=r6Separation(pos,idx,separationRadius,cmiStarMember(p0.w));
'''+motion[end:]
   marker='layout(std430,binding=13) readonly buffer Grid {int heads[GRID_TABLE];int next[];} grid;'
   dome=replace(dome,marker,marker+'\n'+defines+core)
   # Max/min reductions tolerate bucket order changes, but each bucket is consumed once.
   dome=replace(dome,'void main(){','void main(){bool r6Active=r6Valid();float r6BodyCell=r6Active?R6_CELL:2.5;')
   a=dome.index('void main(){');dome=dome[:a]+dome[a:].replace('CMI_BODY_CELL','r6BodyCell')
   # AABB/sphere padding alone cannot rescue a cell omitted by a rounded outer range.
   # One extra cell covers floor/divide rounding; sphere rejection removes its interior excess.
   if dome.count('int cells=int(ceil(reach/r6BodyCell)),visits=0;')!=2:raise ValueError('Dome range contract changed')
   dome=dome.replace('int cells=int(ceil(reach/r6BodyCell)),visits=0;','int cells=int(ceil((reach+r6Pad(old))/r6BodyCell))+1,visits=0;')
   dome=replace(dome,'if(++visits>768)break;','++visits;');dome=replace(dome,'if(++visits>768){exhausted=true;break;}','++visits;')
   dome=dome.replace('if(distance(old,closest)>reach)continue;','if(!r6Touch(old,cell,r6BodyCell,reach))continue;')
   old='uint hash=(uint(cell.x)*92837111u^uint(cell.y)*689287499u^uint(cell.z)*283923481u)&(GRID_TABLE-1u);int node=grid.heads[hash];'
   new='''if(!r6Active&&!r6FirstBucket(cell,ivec3(floor(old/2.5))-ivec3(cells),ivec3(floor(old/2.5))+ivec3(cells),old,reach,true))continue;
   uint hash=r6Hash(cell,r6Active?uR6Buckets-1u:uint(GRID_TABLE)-1u),r6Cursor=r6Active?r6Word(r6Offsets()+hash):0u,r6End=r6Active?r6Word(r6Offsets()+hash+1u):0u;int node=r6Active?0:grid.heads[hash];'''
   if dome.count(old)!=2:raise ValueError('Dome query count changed')
   dome=dome.replace(old,new)
   old='while(node!=0){uint j=uint(node-1);node=grid.next[j];'
   new='''while(r6Active?r6Cursor<r6End:node!=0){uint j;if(r6Active){uint entry=r6Entries()+r6Cursor++*8u;if(any(notEqual(ivec3(r6Word(entry+4u),r6Word(entry+5u),r6Word(entry+6u)),cell)))continue;j=r6Word(entry+7u);}else{j=uint(node-1);node=grid.next[j];}'''
   if dome.count(old)!=2:raise ValueError('Dome iteration count changed')
   dome=dome.replace(old,new)
   dome=replace(dome,'float gatherWorst=0.;','float gatherWorst=0.;uint r6GatherWinner=0xffffffffu,r6BlockWinner=0xffffffffu;')
   dome=replace(dome,'if(amount>gatherWorst){gatherWorst=amount;','if(amount>gatherWorst||(amount>0.&&amount==gatherWorst&&otherMember<r6GatherWinner)){r6GatherWinner=otherMember;gatherWorst=amount;')
   dome=replace(dome,'if(penalty>blockingPenalty){blockingPenalty=penalty;','if(penalty>blockingPenalty||(penalty>0.&&penalty==blockingPenalty&&otherMember<r6BlockWinner)){r6BlockWinner=otherMember;blockingPenalty=penalty;')
   dome=replace(dome,'=uint(visits)|(blockingPenalty','=min(uint(visits),65535u)|(blockingPenalty')
  if name in ('reuse-contact','grid100-space-reuse'):
   # Both owner reads use immutable assignment/ID and the same low phase bits.
   # No state writes occur between these reads; discard the local at each neighbour.
   dome=replace(dome,'bool same=cmiDomeOwner(other.w)==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u);','int r6OtherOwner=cmiDomeOwner(other.w);bool same=r6OtherOwner==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u);')
   dome=replace(dome,'if(same)continue;if(cmiDomeOwner(other.w)>=0','if(same)continue;if(r6OtherOwner>=0')
   dome=replace(dome,'bool same=cmiDomeOwner(other.w)==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u)&&(phase!=1u||otherPhase==1u);','int r6OtherOwner=cmiDomeOwner(other.w);bool same=r6OtherOwner==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u)&&(phase!=1u||otherPhase==1u);')
   dome=replace(dome,'bool reciprocal=cmiDomeOwner(other.w)>=0','bool reciprocal=r6OtherOwner>=0')
  (root/'update.comp').write_text(update);(root/'chunks/storm_motion.glsl').write_text(motion);(root/'chunks/dome_contacts.glsl').write_text(dome)
  traced=out/(name+'-trace');shutil.copytree(target,traced);tr=traced/'assets/createmanaindustry/shaders/particles'
  (tr/'update.comp').write_text(TRACE+(tr/'update.comp').read_text())
  s=(tr/'chunks/storm_motion.glsl').read_text();mark='        vec3 sepW = vec3(0.0);'
  s=replace(s,mark,'        r6Origin(cmiStarMember(p0.w),idx,pos,separationRadius,!domeMember);\n'+mark)
  s=replace(s,'        sepW *= STORM_W_SEP;','        r6Force(cmiStarMember(p0.w),sepW,uint(sepVisits));\n        sepW *= STORM_W_SEP;')
  if name in ('production','order-space','order-phase','reuse-contact'):s=replace(s,'if(d2>=separationRadius*separationRadius)continue;','if(d2>=separationRadius*separationRadius)continue;r6Admit(cmiStarMember(p0.w),cmiStarMember(readBuf.data[uint(j)*4u].w));')
  (tr/'chunks/storm_motion.glsl').write_text(s)
  meta=dict(variant=name,prune=prune,packed=packed,cell=size,buckets=buckets,scope='isolated unified update/contact variant; production unchanged')
  meta['files']={str(p.relative_to(target)):hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()};(target/'variant.json').write_text(json.dumps(meta,indent=2)+'\n')
 print('Prepared',len(VARIANTS),'unified variants plus diagnostic traces:',out)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);p.add_argument('output',type=Path);a=p.parse_args();prepare(a.baseline,a.output)
