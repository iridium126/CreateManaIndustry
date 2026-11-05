// Query metadata comes from motion; the committed pool remains the position owner.
#if defined(CMI_UPDATE_DOMAIN) && CMI_UPDATE_DOMAIN == 3
layout(rgba32ui,binding=5) writeonly uniform uimageBuffer cmiSpatialSteering;
#else
layout(rgba32ui,binding=4) uniform uimageBuffer cmiSpatialWrite;
layout(rgba32ui,binding=5) uniform uimageBuffer cmiSpatialMeta;
#endif
layout(rgba32ui,binding=6) readonly uniform uimageBuffer cmiSpatialHints;
uniform bool uSpatialEnabled,uSpatialHintsEnabled;
uniform uint uSpatialHintMembers;
uniform uvec2 uSpatialEpoch,uSpatialScene,uSpatialTick,uSpatialNonce;
vec3 cmiSpatialDesired=vec3(0);
bool cmiSpatialAnalytic=false;
vec3 cmiSpatialPreference(uint member,vec3 position,vec3 velocity,uint token,uint emitter,uint attack,uint phase){
 if(!uSpatialEnabled||!uSpatialHintsEnabled||member>=uSpatialHintMembers)return vec3(0);
 uvec4 header=imageLoad(cmiSpatialHints,0),source=imageLoad(cmiSpatialHints,1);
 uvec4 target=imageLoad(cmiSpatialHints,2),publication=imageLoad(cmiSpatialHints,4);
 if(header.x!=0x4131314fu||header.y!=12u||any(notEqual(header.zw,uSpatialNonce))||
    any(notEqual(source.xy,uSpatialEpoch))||any(notEqual(target.xy,uSpatialTick))||target.w!=0u||
    any(notEqual(publication.xy,uSpatialScene))||publication.z!=1u)return vec3(0);
 int at=int(8u+member*4u);
 uvec4 id=imageLoad(cmiSpatialHints,at+1);
 if(any(notEqual(id.xyz,uvec3(member,token,emitter)))||(id.w&0x8000ff01u)!=(1u|(phase<<8u)))return vec3(0);
 uvec4 snapshot=imageLoad(cmiSpatialHints,at+2);
 if(snapshot.w!=attack||any(notEqual(snapshot.xyz,floatBitsToUint(position))))return vec3(0);
 vec3 hint=uintBitsToFloat(imageLoad(cmiSpatialHints,at).xyz);
 return any(isnan(hint))||any(isinf(hint))?vec3(0):hint*min(6.,length(velocity));
}
void cmiSpatialStore(uint member,uint slot,vec3 origin,vec3 position,vec3 velocity,float limit,uint token,uint emitter,uint attack,uint phase){
 if(!uSpatialEnabled||member>=131072u)return;
#if defined(CMI_UPDATE_DOMAIN) && CMI_UPDATE_DOMAIN == 3
 imageStore(cmiSpatialSteering,int(member),floatBitsToUint(vec4(cmiSpatialDesired,cmiSpatialAnalytic?-limit:limit)));
#else
 int at=int(member*4u);
 imageStore(cmiSpatialWrite,at,floatBitsToUint(vec4(position,.34)));imageStore(cmiSpatialWrite,at+1,floatBitsToUint(vec4(velocity,limit)));
 imageStore(cmiSpatialWrite,at+2,uvec4(member,token,emitter,1u|(phase<<8u)|(cmiSpatialAnalytic?0x80000000u:0u)));imageStore(cmiSpatialWrite,at+3,uvec4(floatBitsToUint(cmiSpatialDesired),attack));
 imageStore(cmiSpatialMeta,int(member),uvec4(floatBitsToUint(origin),slot+1u));
#endif
}
