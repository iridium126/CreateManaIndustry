// Read the two simulation publications by attack and arc identity. The
// analytic reference supplies only the curve residual; terrain corrections
// and any local positional offset are retained from the simulation endpoints.
layout(std430,binding=27) readonly buffer PreviousStormDisplay {uint words[];} previousStormDisplay;
layout(std430,binding=28) readonly buffer CurrentStormDisplay {uint words[];} currentStormDisplay;
uniform bool uColumnDisplayHistory;
float cmiColumnState(uint at){return uintBitsToFloat(CMI_STATE(currentStormDisplay,at));}
vec3 cmiColumnState3(uint at){return vec3(cmiColumnState(at),cmiColumnState(at+1u),cmiColumnState(at+2u));}
#pragma cmi_include chunks/column_curve.glsl

vec3 cmiColumnDisplayResidual(uint assignment,float alpha){
 if(!uColumnDisplayHistory)return vec3(0);
 uint slot=cmiStarSlot(assignment),rank=cmiStarRank(assignment),base=cmiStarBase(slot);
 uint index=(slot-4u)*64u+rank,state=CMI_COLUMN_STATE_BASE+index*16u,arc=CMI_COLUMN_ARC_BASE+index*CMI_COLUMN_ARC_WORDS;
 uint id=uint(cmiStarVec(base).x+.5),phase=CMI_STATE(currentStormDisplay,arc+CMI_COLUMN_PHASE);
 if(id==0u||CMI_STATE(previousStormDisplay,state)!=id||CMI_STATE(currentStormDisplay,state)!=id||
    CMI_STATE(previousStormDisplay,arc+CMI_COLUMN_PHASE)!=phase)return vec3(0);
 float before=uintBitsToFloat(CMI_STATE(previousStormDisplay,state+15u)),after=cmiColumnState(state+15u);
 if(!(before>=0.&&after>=before))return vec3(0);
 float sampleTime=mix(before,after,alpha);
 if(phase==2u){
  CmiColumnCurve curve=cmiColumnCurveParameters(arc,base,rank);
  vec3 first=cmiColumnCurvePoint(curve,cmiColumnParameter(arc,before,curve.arcWarp));
  vec3 last=cmiColumnCurvePoint(curve,cmiColumnParameter(arc,after,curve.arcWarp));
  return cmiColumnCurvePoint(curve,cmiColumnParameter(arc,sampleTime,curve.arcWarp))-mix(first,last,alpha);
 }
 if(phase==4u){
  float origin=cmiColumnArc(cmiColumnState(arc+CMI_COLUMN_BLOOM_RADIUS)),angle=cmiColumnState(arc+CMI_COLUMN_BLOOM_ANGLE);
  float r0=cmiColumnRadius(origin+before),r1=cmiColumnRadius(origin+after),r=cmiColumnRadius(origin+sampleTime);
  float radial=r-mix(r0,r1,alpha);
  return vec3(cos(angle)*radial,cmiColumnHeight(r)-mix(cmiColumnHeight(r0),cmiColumnHeight(r1),alpha),sin(angle)*radial);
 }
 return vec3(0);
}
