// Stable member roster, shared by compute, native MODEL and GLSL-330 Iris TBO readers.
uniform bool uStarfallEnabled;
#pragma cmi_include chunks/storm_speeds.glsl
uniform bool uDomePreciseClock;
uniform vec4 uDomeElapsed;
#ifdef CMI_STARFALL_TBO
uniform usamplerBuffer cmi_Starfall;
uint cmiStarWord(uint i){return texelFetch(cmi_Starfall,int(i)).r;}
#else
layout(std430,binding=26) readonly buffer StarfallData {uint words[];} starfall;
uint cmiStarWord(uint i){return starfall.words[i];}
#endif
vec4 cmiStarVec(uint word){return vec4(uintBitsToFloat(cmiStarWord(word)),uintBitsToFloat(cmiStarWord(word+1u)),uintBitsToFloat(cmiStarWord(word+2u)),uintBitsToFloat(cmiStarWord(word+3u)));}
uint cmiStarBase(uint slot){return 131088u+slot*312u;}
float cmiStarTime(uint b,float now){float start=cmiStarVec(b+4u).x,d=now-start;if(d< -52428.8)d+=104857.6;else if(d>52428.8)d-=104857.6;return start+d;}
// Other instants must use cmiDomeSampleOffset, never a large-world float subtraction.
float cmiDomeElapsed(uint b,float time){return uDomePreciseClock?uDomeElapsed[(b-131088u)/312u]:cmiStarTime(b,time)-cmiStarVec(b+4u).x;}
vec4 cmiStarLanding(uint b,uint column){return cmiStarVec(b+56u+column*8u);}
vec4 cmiStarColumn(uint b,uint column){return cmiStarVec(b+60u+column*8u);}
#pragma cmi_include chunks/column_rules.glsl
uint cmiStarMember(float identity){return (uint(identity+.5)&262143u)-1u;}
uint cmiStarAssignment(float identity){uint member=cmiStarMember(identity);return uStarfallEnabled&&member<131072u?cmiStarWord(16u+member):0u;}
int cmiStarClaim(float identity,float time){
 uint a=cmiStarAssignment(identity);if(a==0u)return -1;uint slot=cmiStarSlot(a),b=cmiStarBase(slot);
 float kind=cmiStarVec(b+20u).x;if(kind>.5&&kind<1.5&&cmiStarWord(2u)==0u)return -1;
 if(kind>.5&&kind<1.5&&uDomePreciseClock){vec4 meta=cmiStarVec(b),times=cmiStarVec(b+4u);float e=cmiDomeElapsed(b,time);return meta.x>.5&&meta.y<2.5&&e>=0.&&e<=times.w-times.x?int(slot):-1;}
 time=cmiStarTime(b,time);vec4 meta=cmiStarVec(b),times=cmiStarVec(b+4u);return meta.x>.5&&meta.y<2.5&&time>=times.x&&time<=times.w?int(slot):-1;
}
float cmiStarCarry(int slot,float time){uint b=cmiStarBase(uint(slot));time=cmiStarTime(b,time);vec4 t=cmiStarVec(b+4u);return min(clamp((time-t.x)/.25,0.,1.),clamp((t.w-time)/.25,0.,1.));}
float cmiStarTier(int slot){return cmiStarVec(cmiStarBase(uint(slot))+16u).x;}
bool cmiDome(uint b){float kind=cmiStarVec(b+20u).x;return kind>.5&&kind<1.5;}
#pragma cmi_include chunks/helix_rules.glsl
uint cmiMajorRank(uint a){uint b=cmiStarBase(cmiStarSlot(a));return cmiStarRank(a)+(cmiHelix(b)?cmiStarGroup(a)*2048u:0u);}
const uint CMI_COMPLETIONS=721344u,CMI_COMPLETION_WORDS=1664u,CMI_GATHER_STREAK=723008u,CMI_GATHER_COUNTS=854080u,CMI_GATHER_READY=854096u,CMI_GATHER_ELIGIBLE=855632u,CMI_GATHER_BLOCKED=857168u;
// Per-attack spatial history; independent of the narrower centre-passage latch.
const uint CMI_DOME_INSIDE_BOUND=0x10000000u;
uint cmiCompletionOffset(uint slot){return slot<4u?slot*384u:1536u+(slot-4u)*2u;}
float cmiDomePhase(uint b,uint group,float time){vec4 t=cmiStarVec(b+4u);float elapsed=cmiDomeElapsed(b,time);return cmiStarVec(b).y>2.5||elapsed>t.w-t.x?6.:cmiStarVec(b+20u).y<3.||elapsed<t.z-t.x?1.:4.;}
const uint CMI_HELIX_BLOCK_WINDOW=988240u;
const uint CMI_DOME_DATA=205552u,CMI_DOME_JOIN=1053776u;
vec4 cmiDomeRing(uint a){return cmiStarVec(CMI_DOME_DATA+8u+cmiMajorRank(a)*16u);}
// Per-flow clocks are tiny; rank-to-flow lookup is loaded once with v4 metadata.
const uint CMI_DOME_FLOW_MAP=405252u,CMI_DOME_FLOW_SOURCE=417540u,CMI_DOME_FLOW_STATE=2364504u,CMI_DOME_GATHER_WINDOW=CMI_DOME_JOIN;
uint cmiDomeFlow(uint a){return cmiStarWord(CMI_DOME_FLOW_MAP+cmiMajorRank(a));}
uint cmiDomePauseAssignment=0u;float cmiDomePauseCached=0.;
float cmiDomePause(uint a){
 if(a==cmiDomePauseAssignment)return cmiDomePauseCached;
 uint slot=cmiStarSlot(a),f=slot*66u+cmiDomeFlow(a),b=cmiStarBase(slot),id=uint(cmiStarVec(b).x+.5),source=CMI_DOME_FLOW_SOURCE+f*2u;
 float pause=cmiStarWord(source)==id?uintBitsToFloat(cmiStarWord(source+1u)):uintBitsToFloat(0x7f800000u);
 #ifdef CMI_DOME_RUNTIME_FLOW
 if(isinf(pause)&&cmiStarVec(b+20u).y<3.&&cmiDomeFlowWord(CMI_DOME_FLOW_STATE+f*2u)==id)pause=min(pause,uintBitsToFloat(cmiDomeFlowWord(CMI_DOME_FLOW_STATE+f*2u+1u)));
 #endif
 cmiDomePauseAssignment=a;cmiDomePauseCached=pause;return pause;
}
float cmiDomeFlowSeconds(uint a,float seconds){return min(seconds,cmiDomePause(a));}
uint cmiDomeMappedRank(uint a){uint b=cmiStarBase(cmiStarSlot(a)),rank=cmiMajorRank(a);vec4 ring=cmiDomeRing(a),r=cmiStarVec(CMI_DOME_DATA+4u+rank*16u);float dt=cmiDomeFlowSeconds(a,cmiStarVec(b+4u).z-cmiStarVec(b+4u).x),shift=floor(cmiStarVec(CMI_DOME_DATA+12u+rank*16u).y*dt/cmiStarVec(CMI_DOME_DATA+12u+rank*16u).z*ring.w+.5);// GLSL does not define a negative integer remainder. Reduce only
 // nonnegative operands, then restore floor-mod semantics explicitly.
 int shifted=int(rank)-int(r.w)+int(shift);uint count=uint(ring.w),offset=uint(abs(shifted))%count;if(shifted<0&&offset!=0u)offset=count-offset;return uint(r.w)+offset;}
vec4 cmiDomeRecord(uint a){return cmiStarVec(CMI_DOME_DATA+4u+cmiDomeMappedRank(a)*16u);}
// Individual completion is published from the final, collision-resolved position.
// A reference clock reaching its endpoint cannot release a blocked member.
float cmiDomeMemberPhase(uint a,float time){return cmiDomePhase(cmiStarBase(cmiStarSlot(a)),0u,time);}

float cmiAttackCarry(float identity,float time){
 int slot=cmiStarClaim(identity,time);if(slot<0)return 0.;uint b=cmiStarBase(uint(slot));
 if(cmiColumn(b))return 0.;
 if(cmiHelix(b)){time=cmiStarTime(b,time);vec4 t=cmiHelixTimes(b);return min(clamp((time-t.x)/.25,0.,1.),clamp((t.z-time)/.2,0.,1.));}
 if(!cmiDome(b))return cmiStarCarry(slot,time);
 return 0.; // Regional attacks require the actual submitted nearby-player marker.
}
float cmiStarPhase(uint assignment,float time){
 uint b=cmiStarBase(cmiStarSlot(assignment)),column=cmiStarGroup(assignment);time=cmiStarTime(b,time);vec4 t=cmiStarVec(b+4u),m=cmiStarVec(b),l=cmiStarLanding(b,column),p=cmiStarColumn(b,column);
 if(cmiColumn(b)){uint rank=cmiStarRank(assignment);float touch=t.z+cmiColumnDelay(rank);return m.y>2.5||time>t.w?6.:time<t.y?1.:time<touch?2.:time<touch+3.5?4.:5.;}
 if(cmiHelix(b))return cmiHelixMemberPhase(assignment,time);
 if(cmiDome(b))return cmiDomeMemberPhase(assignment,time);
 if(m.y>2.5||time>t.w)return 6.;if(time<t.y)return 1.;if(m.y<1.5||time<t.z)return 2.;if(time<l.w)return 3.;if(time<p.x)return 4.;if(time<p.x+1.5)return 5.;return 6.;
}
float cmiAttackCarry(float identity,float time,float marker){
 uint a=cmiStarAssignment(identity);if(a!=0u&&(cmiHelix(cmiStarBase(cmiStarSlot(a)))||cmiDome(cmiStarBase(cmiStarSlot(a)))))return marker< -3.5&&marker> -4.5&&cmiStarPhase(a,time)==4.&&cmiStarClaim(identity,time)>=0?1.:0.;if(a!=0u&&cmiColumnAssignment(a))return cmiStarClaim(identity,time)<0?0.:cmiColumnCarry(marker);
 return cmiAttackCarry(identity,time);
}
uint cmiMarkerSlot(uint tag){return (tag&0x80000000u)!=0u?4u+(tag&63u):(tag&3u);}
uint cmiMarkerColumn(uint tag){return (tag&0x80000000u)!=0u?0u:((tag>>2u)&31u);}
uint cmiMarkerId(uint tag){return (tag&0x80000000u)!=0u?((tag&0x3fffffffu)>>6u):(tag>>7u);}
uint cmiMarkerCensus(uint tag){return (tag&0x80000000u)!=0u?262528u+(tag&63u):262400u+(tag&3u)*32u+((tag>>2u)&31u);}
float cmiStarMarkerFade(uint tag,float time){uint slot=cmiMarkerSlot(tag),column=cmiMarkerColumn(tag);uint b=cmiStarBase(slot);time=cmiStarTime(b,time);vec4 m=cmiStarVec(b),t=cmiStarVec(b+4u);
 if(!cmiColumn(b)&&cmiStarVec(b+20u).x>.5)return -1.;
 if(uint(m.x+.5)!=cmiMarkerId(tag))return -1.;if(m.y>2.5)return clamp(1.-(time-t.z)/.25,0.,1.);
 return clamp(1.-(time-cmiStarColumn(b,column).x)/.25,0.,1.);
}
