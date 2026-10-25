// Candidate-only views. Image buffers avoid adding SSBO blocks to the movement kernel.
layout(r32ui,binding=2)coherent uniform uimageBuffer cmi_ModelMembersWrite;
layout(r32ui,binding=3)coherent uniform uimageBuffer cmi_ModelAllocator;
uniform bool uModelMembersEnabled;
uniform uint uPopulationModelCapacity,uPopulationStormCapacity;
const uint CMI_MODEL_LIMIT=262144u,CMI_MODEL_FREE=8u,CMI_MODEL_SUMMARY=8200u,CMI_MODEL_TOP=8456u,CMI_MODEL_ROOT=8464u,CMI_MODEL_RETIRED=8465u;
uint cmiModelWord(uint at){return imageAtomicOr(cmi_ModelAllocator,int(at),0u);}
void cmiModelRestore(uint slot){
 uint leaf=slot>>5u,summary=leaf>>5u,top=summary>>5u;
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_FREE+leaf),1u<<(slot&31u));memoryBarrierImage();
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_SUMMARY+summary),1u<<(leaf&31u));memoryBarrierImage();
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_TOP+top),1u<<(summary&31u));memoryBarrierImage();
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_ROOT),1u<<top);
}
void cmiModelPrune(uint parent,uint bit,uint child){
 imageAtomicAnd(cmi_ModelAllocator,int(parent),~(1u<<bit));memoryBarrierImage();
 // A failed main-pool reservation can return a bit while another invocation prunes it.
 if(cmiModelWord(child)!=0u)imageAtomicOr(cmi_ModelAllocator,int(parent),1u<<bit);
}
uint cmiModelAllocate(){
 if(!uModelMembersEnabled)return CMI_MODEL_LIMIT;
 for(;;){
  uint root=cmiModelWord(CMI_MODEL_ROOT);if(root==0u)return CMI_MODEL_LIMIT;
  uint top=uint(findLSB(root)),tv=cmiModelWord(CMI_MODEL_TOP+top);
  if(tv==0u){cmiModelPrune(CMI_MODEL_ROOT,top,CMI_MODEL_TOP+top);continue;}
  uint summary=top*32u+uint(findLSB(tv)),sv=cmiModelWord(CMI_MODEL_SUMMARY+summary);
  if(sv==0u){cmiModelPrune(CMI_MODEL_TOP+top,summary&31u,CMI_MODEL_SUMMARY+summary);continue;}
  uint leaf=summary*32u+uint(findLSB(sv)),lv=cmiModelWord(CMI_MODEL_FREE+leaf);
  if(lv==0u){cmiModelPrune(CMI_MODEL_SUMMARY+summary,leaf&31u,CMI_MODEL_FREE+leaf);continue;}
  uint bit=uint(findLSB(lv)),mask=1u<<bit;
  if((imageAtomicAnd(cmi_ModelAllocator,int(CMI_MODEL_FREE+leaf),~mask)&mask)!=0u)return leaf*32u+bit;
 }
}
void cmiModelRetire(uint slot){
 if(!uModelMembersEnabled||slot>=CMI_MODEL_LIMIT)return;
 // A committed retirement is merged into the next candidate, never reused in this frame.
 uint leaf=slot>>5u,summary=leaf>>5u,top=summary>>5u;
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_RETIRED+leaf),1u<<(slot&31u));
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_RETIRED+8192u+summary),1u<<(leaf&31u));
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_RETIRED+8448u+top),1u<<(summary&31u));
 imageAtomicOr(cmi_ModelAllocator,int(CMI_MODEL_RETIRED+8456u),1u<<top);
}
#ifdef CMI_MODEL_APPEND
// Reuse the existing candidate counter words until prepare_dispatch publishes the roster.
// SSBO atomics avoid serializing two image atomics on the same image-buffer cache line.
void cmiModelAppend(uint slot,uint animation,bool storm){
 uint row=atomicAdd(counter.translucentCensus,1u);
 imageStore(cmi_ModelMembersWrite,int(8u+row*2u),uvec4(slot));
 imageStore(cmi_ModelMembersWrite,int(9u+row*2u),uvec4(animation));
 if(storm){uint r=atomicAdd(counter.sortItems,1u);
  if(r<uPopulationStormCapacity)imageStore(cmi_ModelMembersWrite,int(8u+2u*uPopulationModelCapacity+r),uvec4(slot));}
}

#endif
