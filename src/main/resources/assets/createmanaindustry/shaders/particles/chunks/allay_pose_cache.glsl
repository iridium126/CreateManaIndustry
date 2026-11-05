// Animation prefix and frozen affine poses share the existing animation TBO.
uniform bool uAllayPoseEnabled;
uniform uint uAllayPoseStamp;
uniform int uAllayPoseBase;
uint cmiAllayLoadedSlot=0xffffffffu;
uvec4 cmiAllayCachedMeta=uvec4(0);
bool cmiAllayCachedPose=false;
bool cmiLoadCachedAllayPose(uint slot){
 cmiAllayCachedPose=false;cmiAllayLoadedSlot=slot;
#ifndef CMI_POSE_PRODUCER
 if(!uAllayPoseEnabled||slot>=262144u)return false;
 int at=uAllayPoseBase+int(slot)*25;
 if(at+24>=textureSize(cmi_AllayAnimation))return false;
 cmiAllayCachedMeta=texelFetch(cmi_AllayAnimation,at);
 cmiAllayCachedPose=cmiAllayCachedMeta.y==uAllayPoseStamp;
#endif
 return cmiAllayCachedPose;
}
bool cmiCachedAllayPart(int part,out mat4 transform,out float yaw){
 transform=mat4(1);yaw=0.;
 if(!cmiAllayCachedPose||part<0||part>7)return false;
 int at=uAllayPoseBase+int(cmiAllayLoadedSlot)*25+1+part*3;
 vec4 a=uintBitsToFloat(texelFetch(cmi_AllayAnimation,at));
 vec4 b=uintBitsToFloat(texelFetch(cmi_AllayAnimation,at+1));
 vec4 c=uintBitsToFloat(texelFetch(cmi_AllayAnimation,at+2));
 transform=mat4(vec4(a.x,b.x,c.x,0),vec4(a.y,b.y,c.y,0),vec4(a.z,b.z,c.z,0),vec4(a.w,b.w,c.w,1));
 yaw=uintBitsToFloat(cmiAllayCachedMeta.x);return true;
}
