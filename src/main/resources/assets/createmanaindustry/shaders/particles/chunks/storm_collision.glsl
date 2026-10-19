// Dedicated section-bit atlas. Ordinary MODEL / sprite particles retain their eight-slice bake.
uniform bool uStormCollisionEnabled;
uniform bool uStormCollisionOn;
layout(binding=1) uniform usampler3D uStormCollisionAtlas;
layout(binding=2) uniform isamplerBuffer uStormCollisionTable;
uniform uint uStormCollisionMask;
bool cmiStormVoxel=false,cmiStormMissing=false;
ivec3 cmiStormCached=ivec3(2147483647);int cmiStormCachedPage=-1;
uint cmiStormSectionHash(ivec3 key){uint h=uint(key.x)*0x8da6b343u^uint(key.y)*0xd8163841u^uint(key.z)*0xcb1ab31fu;return h^(h>>16u);}
int cmiStormPage(ivec3 key){
 if(all(equal(key,cmiStormCached)))return cmiStormCachedPage;
 int page=-1;
 if(uStormCollisionOn){uint row=cmiStormSectionHash(key)&uStormCollisionMask;
  for(int i=0;i<8;i++,row=(row+1u)&uStormCollisionMask){ivec4 entry=texelFetch(uStormCollisionTable,int(row));if(entry.w==-1)break;if(all(equal(entry.xyz,key))){page=entry.w;break;}}
 }
 cmiStormCached=key;cmiStormCachedPage=page;return page;
}
bool cmiStormKnown(vec3 p){return cmiStormPage(ivec3(floor(p))>>4)!=-1;}
bool cmiStormSolid(vec3 p){
 ivec3 block=ivec3(floor(p+vec3(.0001)));int page=cmiStormPage(block>>4);
 if(page<0){if(page==-1)cmiStormMissing=true;return page==-3;}
 uint cell=uint((block.x&15)|((block.z&15)<<4)|((block.y&15)<<8)),word=cell>>5u;
 ivec3 texel=ivec3((page&31)*4+int(word&3u),((page>>5)&31)*4+int((word>>2u)&3u),(page>>10)*8+int(word>>4u));
 return (texelFetch(uStormCollisionAtlas,texel,0).r&(1u<<(cell&31u)))!=0u;
}
