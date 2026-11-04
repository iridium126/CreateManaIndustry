// Fixed-layout motion state. Current gameplay rules never consume a display publication.
layout(rgba32ui,binding=4) uniform uimageBuffer cmiSpatialWrite;
layout(rgba32ui,binding=5) uniform uimageBuffer cmiSpatialMeta;
layout(rgba32ui,binding=6) readonly uniform uimageBuffer cmiSpatialHints;
layout(rgba32ui,binding=7) readonly uniform uimageBuffer cmiSpatialRead;
uniform bool uSpatialEnabled,uSpatialHintsEnabled;
uniform uint uSpatialHintMembers;
uniform uvec2 uSpatialEpoch,uSpatialScene,uSpatialTick,uSpatialNonce;
vec3 cmiSpatialDesired=vec3(0);
bool cmiSpatialAnalytic=false;
uint cmiSpatialWord(uint at){return imageLoad(cmiSpatialHints,int(at/4u))[at%4u];}
void cmiSpatialRestore(uint member,uint token,uint emitter,inout vec3 position,inout vec3 velocity){
 if(!uSpatialEnabled||member>=131072u)return;int at=int(member*4u);uvec4 id=imageLoad(cmiSpatialRead,at+2);
 if(id.x==member&&id.y==token&&id.z==emitter&&(id.w&1u)!=0u){position=uintBitsToFloat(imageLoad(cmiSpatialRead,at)).xyz;velocity=uintBitsToFloat(imageLoad(cmiSpatialRead,at+1)).xyz;}
}
vec3 cmiSpatialPreference(uint member,vec3 position,vec3 velocity,uint token,uint emitter,uint attack,uint phase){
 if(!uSpatialEnabled||!uSpatialHintsEnabled||member>=uSpatialHintMembers)return vec3(0);
 int at=int(8u+member*4u);vec4 hint=uintBitsToFloat(imageLoad(cmiSpatialHints,at));
 return hint.xyz*min(6.,length(velocity));
}
void cmiSpatialStore(uint member,uint slot,vec3 origin,vec3 position,vec3 velocity,float limit,uint token,uint emitter,uint attack,uint phase){
 if(!uSpatialEnabled||member>=131072u)return;int at=int(member*4u);
 imageStore(cmiSpatialWrite,at,floatBitsToUint(vec4(position,.34)));imageStore(cmiSpatialWrite,at+1,floatBitsToUint(vec4(velocity,limit)));
 imageStore(cmiSpatialWrite,at+2,uvec4(member,token,emitter,1u|(phase<<8u)|(cmiSpatialAnalytic?0x80000000u:0u)));imageStore(cmiSpatialWrite,at+3,uvec4(floatBitsToUint(cmiSpatialDesired),attack));
 imageStore(cmiSpatialMeta,int(member),uvec4(floatBitsToUint(origin),slot+1u));
}
