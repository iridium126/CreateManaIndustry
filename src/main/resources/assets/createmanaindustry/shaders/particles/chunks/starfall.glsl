// Stable member roster, shared by compute, native MODEL and GLSL-330 Iris TBO readers.
uniform bool uStarfallEnabled;
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
uint cmiStarMember(float identity){return (uint(identity+.5)&262143u)-1u;}
uint cmiStarAssignment(float identity){uint member=cmiStarMember(identity);return uStarfallEnabled&&member<131072u?cmiStarWord(16u+member):0u;}
int cmiStarClaim(float identity,float time){
 uint a=cmiStarAssignment(identity);if(a==0u)return -1;uint slot=(a>>1u)&3u,b=cmiStarBase(slot);
 time=cmiStarTime(b,time);vec4 meta=cmiStarVec(b),times=cmiStarVec(b+4u);return meta.x>.5&&meta.y<2.5&&time>=times.x&&time<=times.w?int(slot):-1;
}
vec4 cmiStarLanding(uint b,uint column){return cmiStarVec(b+56u+column*8u);}
vec4 cmiStarColumn(uint b,uint column){return cmiStarVec(b+60u+column*8u);}
float cmiStarCarry(int slot,float time){uint b=cmiStarBase(uint(slot));time=cmiStarTime(b,time);vec4 t=cmiStarVec(b+4u);return min(clamp((time-t.x)/.25,0.,1.),clamp((t.w-time)/.25,0.,1.));}
float cmiStarTier(int slot){return cmiStarVec(cmiStarBase(uint(slot))+16u).x;}
bool cmiDome(uint b){float kind=cmiStarVec(b+20u).x;return kind>.5&&kind<1.5;}
#pragma cmi_include chunks/helix_rules.glsl
float cmiDomePhase(uint b,uint group,float time){
 vec4 t=cmiStarVec(b+4u),m=cmiStarVec(b),c=cmiStarColumn(b,group),l=cmiStarLanding(b,group);
 if(m.y>2.5||time>t.w)return 6.;if(time<t.y)return 1.;if(m.y<1.5||time<t.z)return 2.;
 if(group==6u)return time<cmiStarColumn(b,5u).w-1.?3.:5.;
 if(time<l.w)return 3.;if(time<c.x)return 4.;return 5.;
}
float cmiAttackCarry(float identity,float time){
 int slot=cmiStarClaim(identity,time);if(slot<0)return 0.;uint b=cmiStarBase(uint(slot));
 if(cmiHelix(b)){time=cmiStarTime(b,time);vec4 t=cmiHelixTimes(b);return min(clamp((time-t.x)/.25,0.,1.),clamp((t.z-time)/.2,0.,1.));}
 if(!cmiDome(b))return cmiStarCarry(slot,time);
 if(cmiStarVec(b).y<1.5)return 0.;
 uint group=(cmiStarAssignment(identity)>>3u)&31u;if(group>=6u)return 0.;time=cmiStarTime(b,time);
 vec4 c=cmiStarColumn(b,group);return min(clamp((time-c.w)/.25,0.,1.),clamp((c.x-time)/.2,0.,1.));
}
float cmiStarPhase(uint assignment,float time){
 uint b=cmiStarBase((assignment>>1u)&3u),column=(assignment>>3u)&31u;time=cmiStarTime(b,time);vec4 t=cmiStarVec(b+4u),m=cmiStarVec(b),l=cmiStarLanding(b,column),p=cmiStarColumn(b,column);
 if(cmiHelix(b))return cmiHelixPhase(b,time);
 if(cmiDome(b))return cmiDomePhase(b,column,time);
 if(m.y>2.5||time>t.w)return 6.;if(time<t.y)return 1.;if(m.y<1.5||time<t.z)return 2.;if(time<l.w)return 3.;if(time<p.x)return 4.;if(time<p.x+1.5)return 5.;return 6.;
}
float cmiStarMarkerFade(uint tag,float time){uint slot=tag&3u,column=(tag>>2u)&31u;uint b=cmiStarBase(slot);time=cmiStarTime(b,time);vec4 m=cmiStarVec(b),t=cmiStarVec(b+4u);
 if(cmiStarVec(b+20u).x>.5)return -1.;
 if(uint(m.x+.5)!=(tag>>7u))return -1.;if(m.y>2.5)return clamp(1.-(time-t.z)/.25,0.,1.);
 return clamp(1.-(time-cmiStarColumn(b,column).x)/.25,0.,1.);
}
