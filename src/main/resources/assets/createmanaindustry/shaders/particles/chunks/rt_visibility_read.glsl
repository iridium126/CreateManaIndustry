// Unknown groups and changed light coverage retain the complete draw.
struct CmiRtGroupProof {uvec4 flags;vec4 occluder,halfExtent;};
layout(std430,binding=29) buffer RtVisibleGroups {vec4 light;uvec4 counts;CmiRtGroupProof rows[];} rtVisibleGroups;
uniform bool uRtVisibleGroups;
uniform bool uRtVisibilityMeasure;
void cmiCountRtRejection(){if(uRtVisibilityMeasure)atomicAdd(rtVisibleGroups.counts.w,1u);}
uint cmiRtGroup(vec3 point){ivec3 cell=ivec3(floor(point/8.));return (uint(cell.x)*92837111u^uint(cell.y)*689287499u^uint(cell.z)*283923481u)&1023u;}
uint cmiRtVisibility(vec3 point){return uRtVisibleGroups?rtVisibleGroups.rows[cmiRtGroup(point)].flags.x:3u;}
bool cmiRtShadowVisible(vec3 point,vec3 ray,vec3 camera,vec4 shadowPlanes[6],bool shadowViewValid){
 if(!uRtVisibleGroups||rtVisibleGroups.light.w<.5||!shadowViewValid)return true;
 CmiRtGroupProof proof=rtVisibleGroups.rows[cmiRtGroup(point)];
 if((proof.flags.x&2u)!=0u||any(greaterThan(abs(ray-rtVisibleGroups.light.xyz),uintBitsToFloat(proof.flags.yzw))))return true;
 // A wall outside the current shadow map cannot suppress a caster even
 // if the infinite light ray intersects it. Retain anything outside the proof.
 vec3 center=proof.occluder.xyz-camera,halfExtent=proof.halfExtent.xyz;
 if(!all(greaterThan(halfExtent,vec3(0))))return true;
 for(int i=0;i<6;i++){
  vec4 plane=shadowPlanes[i];
  if(!(dot(plane.xyz,center)+plane.w>dot(abs(plane.xyz),halfExtent)+.001))return true;
 }
 return false;
}
