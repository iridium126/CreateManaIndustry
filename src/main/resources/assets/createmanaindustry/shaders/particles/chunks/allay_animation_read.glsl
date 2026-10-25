#pragma cmi_include chunks/allay_animation_math.glsl
uniform usamplerBuffer cmi_AllayAnimation;
uniform bool uAllayAnimationEnabled;
uniform float uAllayAnimationAlpha=-1.;
bool cmiAllayAnimationValid=false;
float cmiAnimationBodyYaw=0.,cmiAnimationPitch=0.,cmiAnimationHeadYaw=0.,cmiAnimationAmount=0.,cmiAnimationPhase=0.,cmiAnimationCarry=0.,cmiAnimationDance=-1.,cmiAnimationSpinProgress=0.;
void cmiLoadAllayAnimation(uint slot,float age){
 cmiAllayAnimationValid=false;if(!uAllayAnimationEnabled)return;
 int at=int(slot)*4;if(at+3>=textureSize(cmi_AllayAnimation))return;
 uvec4 a=texelFetch(cmi_AllayAnimation,at),b=texelFetch(cmi_AllayAnimation,at+1),c=texelFetch(cmi_AllayAnimation,at+2);
 if((c.z&0x80000000u)==0u)return;
 cmiAllayAnimationValid=true;float alpha=uAllayAnimationAlpha>=0.?uAllayAnimationAlpha:clamp(max(0.,age)*20.-float(c.w),0.,1.);
 vec2 previous=cmiAnimationAngles(a.x),current=cmiAnimationAngles(a.y),ph=cmiAnimationHead(a.z),ch=cmiAnimationHead(a.w);
 cmiAnimationBodyYaw=previous.x+cmiAnimationAngle(previous.x,current.x)*alpha;
 cmiAnimationPitch=mix(previous.y,current.y,alpha);
 cmiAnimationHeadYaw=ph.x+cmiAnimationAngle(ph.x,ch.x)*alpha;
 cmiAnimationAmount=mix(ph.y,ch.y,alpha);cmiAnimationPhase=mix(uintBitsToFloat(b.x),uintBitsToFloat(b.y),alpha);
 vec2 carry=cmiAnimationUnpack(b.z);cmiAnimationCarry=mix(carry.x,carry.y,alpha);
 uint ct=(b.w>>6u)&63u;
 cmiAnimationDance=ct==63u?-1.:float(ct);
 cmiAnimationSpinProgress=mix(float((b.w>>12u)&15u),float((b.w>>16u)&15u),alpha)/15.;
}
