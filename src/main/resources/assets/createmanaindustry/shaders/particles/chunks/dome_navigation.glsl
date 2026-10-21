// C2 inverse-arc tables. Integration and inverse construction happen offline.
uniform usamplerBuffer cmiDomeKeys;
float cmiDomeValue(uint address){return uintBitsToFloat(texelFetch(cmiDomeKeys,int(address)).r);}
vec3 cmiDomeVector(uint base,uint node,uint component){uint at=(base+node)*12u+component;return vec3(cmiDomeValue(at),cmiDomeValue(at+1u),cmiDomeValue(at+2u));}
float cmiDomeArcNode(uint base,uint node){return cmiDomeValue((base+node)*12u+3u);}
vec3 cmiDomeCenter(uint b,float time){return cmiStarVec(b+24u).xyz;}
void cmiDomeLookup(uint base,uint count,float distanceAlong,out vec3 point,out vec3 tangent,out vec3 curvature){
 uint lo=0u,hi=count-1u;
 for(int k=0;k<12;k++){if(hi-lo<=1u)break;uint mid=(lo+hi)/2u;if(cmiDomeArcNode(base,mid)<distanceAlong)lo=mid;else hi=mid;}
 float first=cmiDomeArcNode(base,lo),length=cmiDomeArcNode(base,hi)-first,u=clamp((distanceAlong-first)/max(length,.000001),0.,1.);
 vec3 p=cmiDomeVector(base,lo,0u),q=cmiDomeVector(base,hi,0u),v=cmiDomeVector(base,lo,4u)*length,w=cmiDomeVector(base,hi,4u)*length,a=cmiDomeVector(base,lo,8u)*length*length,b=cmiDomeVector(base,hi,8u)*length*length,d=q-p;
 vec3 c3=10.*d-6.*v-4.*w-1.5*a+.5*b,c4=-15.*d+8.*v+7.*w+1.5*a-b,c5=6.*d-3.*v-3.*w-.5*a+.5*b;
 point=(((((c5*u+c4)*u+c3)*u+a*.5)*u+v)*u+p);
 tangent=((((5.*c5*u+4.*c4)*u+3.*c3)*u+a)*u+v)/length;
 curvature=(((20.*c5*u+12.*c4)*u+6.*c3)*u+a)/(length*length);
}
void cmiDomeGather(uint a,float seconds,out vec3 point,out vec3 velocity,out vec3 acceleration){
 uint rank=cmiMajorRank(a);vec4 ring=cmiDomeRing(a),record=cmiStarVec(CMI_DOME_DATA+4u+rank*16u),flow=cmiStarVec(CMI_DOME_DATA+12u+rank*16u),extra=cmiStarVec(CMI_DOME_DATA+16u+rank*16u);
 float phase=(float(rank)-record.w)/ring.w+flow.y*seconds/flow.z;vec3 tangent,curvature;
 cmiDomeLookup(uint(flow.x),uint(extra.x),fract(phase)*flow.z,point,tangent,curvature);velocity=tangent*flow.y;acceleration=curvature*flow.y*flow.y;
}
float cmiDomeDistance(float seconds,float initialSpeed){float t=max(seconds,0.),u=min(t/.5,1.);return initialSpeed*t+(6.-initialSpeed)*.5*(u*u*u*u*u*u-3.*u*u*u*u*u+2.5*u*u*u*u)+max(t-.5,0.)*(6.-initialSpeed);}
float cmiDomeSpeed(float seconds,float initialSpeed){float u=clamp(seconds/.5,0.,1.);return initialSpeed+(6.-initialSpeed)*u*u*u*(10.-15.*u+6.*u*u);}
uint cmiDomeCachedAssignment=0u;float cmiDomeCachedTime=-1.;vec3 cmiDomeCachedPoint=vec3(0),cmiDomeCachedVelocity=vec3(0);
void cmiDomeSampleOffset(uint a,float deltaTime,out vec3 point,out vec3 velocity){
 uint b=cmiStarBase(cmiStarSlot(a));float time=cmiDomeElapsed(b,uTimeSec)+deltaTime;
 if(a==cmiDomeCachedAssignment&&time==cmiDomeCachedTime){point=cmiDomeCachedPoint;velocity=cmiDomeCachedVelocity;return;}
 vec4 times=cmiStarVec(b+4u);vec3 acceleration;
 if(cmiStarVec(b+20u).y<3.||time<times.z-times.x)cmiDomeGather(a,time,point,velocity,acceleration);
 else{
  uint mapped=cmiDomeMappedRank(a);vec4 record=cmiDomeRecord(a),flow=cmiStarVec(CMI_DOME_DATA+12u+mapped*16u);float age=max(0.,time-(times.z-times.x)),speed=abs(flow.y);vec3 tangent,curvature;
  cmiDomeLookup(uint(record.x),uint(record.y),min(cmiDomeDistance(age,speed),flow.w),point,tangent,curvature);velocity=tangent*cmiDomeSpeed(age,speed);
  float u=clamp(age/CMI_DOME_HANDOFF_SECONDS,0.,1.);if(u<1.){
   vec3 entry,entryVelocity,entryAcceleration;cmiDomeGather(a,times.z-times.x,entry,entryVelocity,entryAcceleration);
   vec3 offset=entry-cmiDomeVector(uint(record.x),0u,0u),delta=entryVelocity-cmiDomeVector(uint(record.x),0u,4u)*speed,da=entryAcceleration-cmiDomeVector(uint(record.x),0u,8u)*speed*speed;
   float u2=u*u,u3=u2*u,u4=u3*u,u5=u4*u;
   point+=offset*(1.-10.*u3+15.*u4-6.*u5)+delta*(CMI_DOME_HANDOFF_SECONDS*(u-6.*u3+8.*u4-3.*u5))+da*(CMI_DOME_HANDOFF_SECONDS*CMI_DOME_HANDOFF_SECONDS*.5*(u2-3.*u3+3.*u4-u5));
   velocity+=offset*((-30.*u2+60.*u3-30.*u4)/CMI_DOME_HANDOFF_SECONDS)+delta*(1.-18.*u2+32.*u3-15.*u4)+da*(CMI_DOME_HANDOFF_SECONDS*.5*(2.*u-9.*u2+12.*u3-5.*u4));
  }
 }
 float angle=cmiStarVec(b+20u).z,c=cos(angle),s=sin(angle);point=vec3(c*point.x-s*point.z,point.y-.3,s*point.x+c*point.z)+cmiDomeCenter(b,time);velocity=vec3(c*velocity.x-s*velocity.z,velocity.y,s*velocity.x+c*velocity.z);
 cmiDomeCachedAssignment=a;cmiDomeCachedTime=time;cmiDomeCachedPoint=point;cmiDomeCachedVelocity=velocity;
}
void cmiDomeSample(uint a,float time,out vec3 point,out vec3 velocity){cmiDomeSampleOffset(a,time-uTimeSec,point,velocity);}
vec3 cmiDomePoint(uint a,float seed,float time,vec3 home){vec3 point,velocity;cmiDomeSample(a,time,point,velocity);return point;}
vec3 cmiDomeVelocity(uint a,float time){vec3 point,velocity;cmiDomeSample(a,time,point,velocity);return velocity;}
