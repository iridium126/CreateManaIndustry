const uint CMI_COLUMN_ARC_WORDS=96u;
// Column assignment uses a separate high-bit tag, preserving the four legacy attack slots.
bool cmiColumnAssignment(uint a){return (a&0x40000000u)!=0u;}
uint cmiStarSlot(uint a){return cmiColumnAssignment(a)?4u+((a>>1u)&63u):((a>>1u)&3u);}
uint cmiStarGroup(uint a){return cmiColumnAssignment(a)?0u:((a>>3u)&31u);}
uint cmiStarRank(uint a){return (a&0x3fffffffu)>>8u;}
bool cmiColumn(uint b){return cmiStarVec(b+20u).x>2.5;}
float cmiColumnSmooth(float t){t=clamp(t,0.,1.);return t*t*t*(10.+t*(-15.+6.*t));}
float cmiColumnHeight(float r){return .0625*r*r;}
float cmiColumnArc(float r);
float cmiColumnRadius(float arc);
float cmiColumnDelay(uint rank){return float(rank)*.06;}
float cmiColumnAngle(uint b,uint rank){return float((rank*27u)&63u)*.09817477042+cmiStarVec(b+12u).w*.0001;}
float cmiColumnRoot(uint rank){return sqrt(mix(.04,1.96,(float(rank)+.5)/64.));}
float cmiColumnWrap(float angle){return mod(angle+3.14159265,6.2831853)-3.14159265;}
vec3 cmiColumnBloom(uint b,uint rank,float age){
 float r=cmiColumnRadius(cmiColumnArc(cmiColumnRoot(rank))+max(age,0.)*6.),a=cmiColumnAngle(b,rank);
 return cmiStarLanding(b,0u).xyz+vec3(cos(a)*r,cmiColumnHeight(r),sin(a)*r);
}
vec3 cmiColumnBloomFrom(uint b,uint rank,float age,vec3 touchdown){
 vec3 landing=cmiStarLanding(b,0u).xyz;vec2 offset=touchdown.xz-landing.xz;
 float r=cmiColumnRadius(cmiColumnArc(length(offset))+max(age,0.)*6.),a=atan(offset.y,offset.x);
 return landing+vec3(cos(a)*r,cmiColumnHeight(r),sin(a)*r);
}
float cmiColumnOrbitRadius(uint rank);
float cmiColumnOrbitAngle(uint b,uint rank,float time){
 return float(rank&3u)*1.570796327+float(rank/4u)*.35+cmiStarVec(b+12u).w*.0001+(CMI_COLUMN_ORBIT_SPEED/cmiColumnOrbitRadius(rank))*(time-cmiStarVec(b+4u).x);
}
float cmiColumnOrbitRadius(uint rank){return .9+.3*float(rank/4u)/15.;}
vec3 cmiColumnGatherAt(uint b,uint rank,float time){
 float a=cmiColumnOrbitAngle(b,rank,time),r=cmiColumnOrbitRadius(rank);
 return cmiStarVec(b+8u).xyz+vec3(cos(a)*r,(float(rank/4u)-7.5)*.7,sin(a)*r);
}
vec3 cmiColumnGather(uint b,uint rank){return cmiColumnGatherAt(b,rank,cmiStarVec(b+4u).y);}
vec3 cmiColumnOrbitVelocity(uint b,uint rank,float time){
 float a=cmiColumnOrbitAngle(b,rank,time),speed=CMI_COLUMN_ORBIT_SPEED;
 return vec3(-sin(a)*speed,0,cos(a)*speed);
}
vec3 cmiColumnDescentLocalAt(uint b,uint rank,float time,float begin){
 vec4 t=cmiStarVec(b+4u);float duration=max(t.z+cmiColumnDelay(rank)-t.y,.1),u=clamp((time-begin)/duration,0.,1.),s=cmiColumnSmooth(u);
 float first=cmiColumnOrbitAngle(b,rank,begin),last=first+.7*duration;
 last+=cmiColumnWrap(cmiColumnAngle(b,rank)-last);
 float angle=mix(first+(CMI_COLUMN_ORBIT_SPEED/cmiColumnOrbitRadius(rank))*clamp(time-begin,0.,duration),last,s),r=mix(cmiColumnOrbitRadius(rank),cmiColumnRoot(rank),s);
 vec3 top=cmiColumnGatherAt(b,rank,begin),foot=cmiColumnBloom(b,rank,0.);
 vec3 landing=cmiStarLanding(b,0u).xyz;
 return vec3(cos(angle)*r,mix(top.y-landing.y,cmiColumnHeight(cmiColumnRoot(rank)),s),sin(angle)*r);
}
vec3 cmiColumnDescentAt(uint b,uint rank,float time,float begin){return cmiStarLanding(b,0u).xyz+cmiColumnDescentLocalAt(b,rank,time,begin);}
vec3 cmiColumnDescent(uint b,uint rank,float time){return cmiColumnDescentAt(b,rank,time,cmiStarVec(b+4u).y);}
// Alive MODEL roll is otherwise unused. Negative roll carries confirmed bloom age to all render paths.
float cmiColumnCarry(float marker){
 if(marker>-.999||marker< -2.001)return 0.;float progress=-marker-1.;
 return cmiColumnSmooth(progress/.12)*cmiColumnSmooth((1.-progress)/.15);
}
// Parabola arc primitive. Two Newton corrections are bounded and independent of frame rate.
float cmiColumnArc(float r){float x=r*r/64.;return r*(1.+x*(1./6.+x*(-1./40.+x*(1./112.+x*(-5./1152.+x*(7./2816.))))));}
float cmiColumnRadius(float arc){float r=clamp(arc,0.,4.);for(int i=0;i<2;i++)r=clamp(r-(cmiColumnArc(r)-arc)/sqrt(1.+r*r/64.),0.,4.);return r;}
vec3 cmiColumnTangent(float angle,float r){return normalize(vec3(cos(angle),r*.125,sin(angle)));}
