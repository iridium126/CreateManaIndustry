// Dedicated section-bit atlas. Ordinary MODEL / sprite particles retain their eight-slice bake.
uniform bool uStormCollisionEnabled;
uniform bool uStormCollisionOn;
layout(binding=1) uniform usampler3D uStormCollisionAtlas;
layout(binding=2) uniform isamplerBuffer uStormCollisionTable;
layout(binding=6) uniform usamplerBuffer uStormTerrainEdits;
uniform bool uStormTerrainEdited;
uniform uint uStormCollisionMask;
uniform uint uStormCollisionOverflow;
uniform uint uStormCollisionRevision;
bool cmiStormVoxel=false,cmiStormMissing=false,cmiStormEmbedded=false;
ivec3 cmiStormCached=ivec3(2147483647);int cmiStormCachedPage=-1;
uint cmiStormMix(uint v){v=(v^(v>>16u))*0x7feb352du;v=(v^(v>>15u))*0x846ca68bu;return v^(v>>16u);}
uint cmiStormSectionHash(ivec3 key){uint y=cmiStormMix(uint(key.y)+0x85ebca6bu),z=cmiStormMix(uint(key.z)+0xc2b2ae35u);return cmiStormMix(cmiStormMix(uint(key.x)+0x9e3779b9u)^((y<<11u)|(y>>21u))^((z<<22u)|(z>>10u)));}
ivec4 cmiStormEditRow(uint row){
 int at=4+int(row)*4;
 return ivec4(texelFetch(uStormTerrainEdits,at).x,texelFetch(uStormTerrainEdits,at+1).x,
              texelFetch(uStormTerrainEdits,at+2).x,texelFetch(uStormTerrainEdits,at+3).x);
}
ivec3 cmiStormEditCachedKey=ivec3(2147483647);
int cmiStormEditCached=-1;
int cmiStormEditPage(ivec3 key){
 if(!uStormTerrainEdited)return -1;
 if(all(equal(key,cmiStormEditCachedKey)))return cmiStormEditCached;
 cmiStormEditCachedKey=key;cmiStormEditCached=-1;
 uint primary=texelFetch(uStormTerrainEdits,0).x,mask=primary-1u;
 uint row=cmiStormSectionHash(key)&mask;
 uint probes=min(8u,primary);
 for(uint i=0u;i<probes;i++,row=(row+1u)&mask){
  ivec4 entry=cmiStormEditRow(row);if(entry.w<0)break;
  if(all(equal(entry.xyz,key)))return cmiStormEditCached=entry.w;
 }
 uint lo=primary,hi=lo+texelFetch(uStormTerrainEdits,1).x;
 while(lo<hi){
  uint mid=(lo+hi)/2u;ivec4 v=cmiStormEditRow(mid);
  int c=v.x<key.x?-1:v.x>key.x?1:v.y<key.y?-1:v.y>key.y?1:v.z<key.z?-1:v.z>key.z?1:0;
  if(c<0)lo=mid+1u;else if(c>0)hi=mid;else return cmiStormEditCached=v.w;
 }
 return -1;
}
bool cmiStormCleared(ivec3 block,uint cell){
 int page=cmiStormEditPage(block>>4);if(page<0)return false;
 uint base=4u+4u*(texelFetch(uStormTerrainEdits,0).x+texelFetch(uStormTerrainEdits,1).x);
 uint bits=texelFetch(uStormTerrainEdits,int(base+uint(page)*128u+(cell>>5u))).x;
 return (bits&(1u<<(cell&31u)))!=0u;
}
int cmiStormLookupPage(ivec3 key){
 int page=-1;
 if(uStormCollisionOn){uint row=cmiStormSectionHash(key)&uStormCollisionMask;
  // A uniform bound avoids exponential driver unrolling in nested body probes.
  uint probes=min(8u,uStormCollisionMask+1u);for(uint i=0u;i<probes;i++,row=(row+1u)&uStormCollisionMask){ivec4 entry=texelFetch(uStormCollisionTable,int(row));if(entry.w==-1)break;if(all(equal(entry.xyz,key)))return entry.w;}
  uint lo=uStormCollisionMask+1u,hi=lo+uStormCollisionOverflow;
  while(lo<hi){uint mid=lo+(hi-lo)/2u;ivec4 entry=texelFetch(uStormCollisionTable,int(mid));
   int c=entry.x<key.x?-1:entry.x>key.x?1:entry.y<key.y?-1:entry.y>key.y?1:entry.z<key.z?-1:entry.z>key.z?1:0;
   if(c<0)lo=mid+1u;else if(c>0)hi=mid;else return entry.w;}
 }
 return page;
}
int cmiStormPage(ivec3 key){
 if(all(equal(key,cmiStormCached)))return cmiStormCachedPage;
 int page=cmiStormLookupPage(key);cmiStormCached=key;cmiStormCachedPage=page;return page;
}
bool cmiStormSolidInPage(ivec3 block,int page){
 if(page<0&&page!=-3)return false;
 uint cell=uint((block.x&15)|((block.z&15)<<4)|((block.y&15)<<8)),word=cell>>5u;
 if(uStormTerrainEdited&&cmiStormCleared(block,cell))return false;
 if(page==-3)return true;
 ivec3 texel=ivec3((page&31)*4+int(word&3u),((page>>5)&31)*4+int((word>>2u)&3u),(page>>10)*8+int(word>>4u));
 return (texelFetch(uStormCollisionAtlas,texel,0).r&(1u<<(cell&31u)))!=0u;
}
bool cmiStormKnown(vec3 p){return cmiStormPage(ivec3(floor(p))>>4)!=-1;}
bool cmiStormSolid(vec3 p){
 ivec3 block=ivec3(floor(p+vec3(.0001)));int page=cmiStormPage(block>>4);
 if(page==-1)cmiStormMissing=true;
 return cmiStormSolidInPage(block,page);
}
// Keep occupancy independent of coverage: a solid corner cannot hide an
// unknown neighbouring section. Feet are the origin of the vanilla Allay box.
struct CmiStormBody {bool occupied;bool complete;};
// An immutable AIR page certifies the entire swept body, including every
// axis-separated intermediate position. Border/mixed/unknown pages use the
// full query below. Conservative bounds keep touching faces on that path.
bool cmiStormAirSweep(vec3 start,vec3 end){
 ivec3 section=ivec3(floor(start/16.));if(cmiStormPage(section)!=-2)return false;
 vec3 origin=vec3(section*16),lo=min(start,end)-origin+vec3(-.175,0.,-.175),hi=max(start,end)-origin+vec3(.175,.6,.175);
 return all(greaterThanEqual(lo,vec3(0)))&&all(lessThan(hi,vec3(16)));
}
CmiStormBody cmiStormBody(vec3 feet){
 ivec3 lo=ivec3(floor(feet+vec3(-.17499,.00001,-.17499))),hi=ivec3(floor(feet+vec3(.17499,.59999,.17499)));
 bool same=all(equal(lo>>4,hi>>4));int sharedPage=same?cmiStormPage(lo>>4):-4;
 if(same&&sharedPage<0&&(sharedPage!=-3||cmiStormEditPage(lo>>4)<0))return CmiStormBody(sharedPage==-3,sharedPage!=-1);
 bool occupied=false,complete=true;
 for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
  ivec3 cell=ivec3(x,y,z);int page=same?sharedPage:cmiStormLookupPage(cell>>4);
  complete=complete&&page!=-1;
  if(cmiStormSolidInPage(cell,page))occupied=true;
 }
 return CmiStormBody(occupied,complete);
}
