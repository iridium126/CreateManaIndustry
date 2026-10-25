#pragma cmi_include chunks/allay_animation_math.glsl
// Stable animation slot * 4:
// 0: previous/current body yaw+head pitch, previous/current relative head yaw+walk amount.
// 1: previous/current walk phase, previous/current carry, dance ticks/spin/first-cycle flag.
// 2: stable observed entity ID, look deadline, stable-head yaw/counter + valid/dead, sampled tick.
// 3: sampled tick position.xyz (float bits), reserved. Independent of physical pool compaction.
// Reserve a distinct unit even before the lazy allocation; sampler types may not alias.
layout(binding=3)uniform usamplerBuffer cmi_AllayAnimationRead;
layout(rgba32ui,binding=0)coherent uniform uimageBuffer cmi_AllayAnimationWrite;
layout(rgba32ui,binding=1)coherent uniform uimageBuffer cmi_AllayAnimationInitial;
uniform bool uAllayAnimationWriteEnabled,uAllayAnimationClockEnabled,uAllayAnimationCountWrites;
uniform uint uAllayAnimationTick;
uniform float uAllayAnimationFraction,uAllayAnimationAlpha=-1.;
uvec4 cmiAnimationReadRow(uint i){return texelFetch(cmi_AllayAnimationRead,int(i));}
uvec4 cmiAnimationWrittenRow(uint i){return imageLoad(cmi_AllayAnimationWrite,int(i));}
void cmiAnimationStore(uint i,uvec4 value){imageStore(cmi_AllayAnimationWrite,int(i),value);}
void cmiInitializeAllayAnimation(uint slot,vec3 position,vec3 velocity,float seed){
 if(!uAllayAnimationWriteEnabled||slot*4u+3u>=uint(imageSize(cmi_AllayAnimationWrite)))return;
 float yaw=length(velocity.xz)>.0001?atan(-velocity.x,velocity.z):seed*CMI_ANIM_TAU;
 uint angles=cmiAnimationAngles(yaw,0.),head=cmiAnimationHead(0.,0.);
 cmiAnimationStore(slot*4u,uvec4(angles,angles,head,head));
 cmiAnimationStore(slot*4u+1u,uvec4(0,0,0,63u|(63u<<6u)));
 cmiAnimationStore(slot*4u+2u,uvec4(0,(uAllayAnimationClockEnabled?uAllayAnimationTick:0u)+30u+uint(fract(abs(seed))*31.),0x80000000u,uAllayAnimationClockEnabled?uAllayAnimationTick:0u));
 cmiAnimationStore(slot*4u+3u,uvec4(floatBitsToUint(position),0));
}

// Only slots absent from the committed MODEL list may be initialized in both banks.
void cmiInitializeModelAnimation(uint animation,vec3 position,vec3 velocity,float seed){
 cmiInitializeAllayAnimation(animation,position,velocity,seed);memoryBarrierImage();
 for(uint k=0u;k<4u;k++)imageStore(cmi_AllayAnimationInitial,int(animation*4u+k),cmiAnimationWrittenRow(animation*4u+k));
}
