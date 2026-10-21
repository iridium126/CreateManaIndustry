#pragma cmi_include chunks/dome_pose_math.glsl
uniform usamplerBuffer cmi_DomePose;
uniform bool uDomePoseEnabled;
uniform int cmi_DomePoseBase;
bool cmiDomeCommittedPose(float identity,out vec3 direction,out float carry,out float speed,out float yaw){
 direction=vec3(0,0,1);carry=0.;speed=0.;yaw=0.;uint member=cmiStarMember(identity);
 if(!uDomePoseEnabled||member>=131072u)return false;
 uint flags=texelFetch(cmi_DomePose,cmi_DomePoseBase+int(member)*2+1).r;
 if((flags&0x80000000u)==0u)return false;
 vec2 angles=cmiDomeUnpackPose(texelFetch(cmi_DomePose,cmi_DomePoseBase+int(member)*2).r);direction=cmiDomePoseDirection(angles);yaw=angles.x;carry=float(flags&65535u)/65535.;speed=float((flags>>16u)&32767u)*(64./32767.);return true;
}
bool cmiDomeCommittedPose(float identity,out vec3 direction,out float carry){float speed,yaw;return cmiDomeCommittedPose(identity,direction,carry,speed,yaw);}
