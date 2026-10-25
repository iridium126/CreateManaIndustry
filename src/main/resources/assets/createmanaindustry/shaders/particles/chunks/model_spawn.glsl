#define CMI_MODEL_APPEND
#pragma cmi_include chunks/model_members_write.glsl
#pragma cmi_include chunks/pool_reservation.glsl
uint cmiSpawnAnimationSlot=CMI_MODEL_LIMIT;
uint cmiReserveEmitterParticle(uint emitter){
 bool model=emitters.u[emitter*VEC4_PER_EMITTER+7u].x>=1.5&&emitters.u[emitter*VEC4_PER_EMITTER+7u].x<2.5;
 cmiSpawnAnimationSlot=CMI_MODEL_LIMIT;
 if(model&&uModelMembersEnabled){cmiSpawnAnimationSlot=cmiModelAllocate();if(cmiSpawnAnimationSlot==CMI_MODEL_LIMIT)return uCapacity;}
 uint slot=cmiReserveParticle();
 if(slot>=uCapacity&&cmiSpawnAnimationSlot<CMI_MODEL_LIMIT){cmiModelRestore(cmiSpawnAnimationSlot);cmiSpawnAnimationSlot=CMI_MODEL_LIMIT;}
 return slot;
}
void cmiRegisterModelSpawn(uint slot,vec3 position,vec3 velocity,float seed,bool storm){
 if(uModelMembersEnabled){
  cmiModelAppend(slot,cmiSpawnAnimationSlot,storm);
  cmiInitializeModelAnimation(cmiSpawnAnimationSlot,position,velocity,seed);
  if(uAllayAnimationCountWrites)imageAtomicAdd(cmi_ModelAllocator,0,2u);
 }else cmiInitializeAllayAnimation(slot,position,velocity,seed);
}
