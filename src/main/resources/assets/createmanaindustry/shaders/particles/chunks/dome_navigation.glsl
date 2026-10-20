// Shared C1 Hermite curtains and cumulative arc tables. No frame-by-frame uploads.
uniform usamplerBuffer cmiDomeKeys;
float cmiDomeWord(uint base,uint word){return uintBitsToFloat(texelFetch(cmiDomeKeys,int(base*2u+word)).r);}
vec4 cmiDomeNode(uint base,uint node,uint portion){uint w=base*2u+node*4u,a=texelFetch(cmiDomeKeys,int(w)).r,b=texelFetch(cmiDomeKeys,int(w+1u)).r;
 if(portion==0u)return vec4(vec3(float(a&65535u),float(a>>16u),float(b&65535u))*(80./65535.)-40.,uintBitsToFloat(texelFetch(cmiDomeKeys,int(w+2u)).r));
 uint v=texelFetch(cmiDomeKeys,int(w+3u)).r;ivec3 signedVelocity=ivec3(int(v<<22u)>>22,int(v<<12u)>>22,int(v<<2u)>>22);return vec4(vec3(signedVelocity)*(6./511.),float(b>>16u)*.001);
}
vec3 cmiDomeCenter(uint b,float time){return cmiStarVec(b+24u).xyz;}
vec3 cmiDomeHermite(vec3 p,vec3 q,vec3 v,vec3 w,float u){float u2=u*u,u3=u2*u;return (2.*u3-3.*u2+1.)*p+(-2.*u3+3.*u2)*q+(u3-2.*u2+u)*v+(u3-u2)*w;}
vec3 cmiDomeDerivative(vec3 p,vec3 q,vec3 v,vec3 w,float u){float u2=u*u;return (6.*u2-6.*u)*p+(-6.*u2+6.*u)*q+(3.*u2-4.*u+1.)*v+(3.*u2-2.*u)*w;}
float cmiDomeArc(vec3 p,vec3 q,vec3 v,vec3 w,float u){const vec4 x=vec4(.9602898565,.7966664774,.5255324099,.1834346425),weights=vec4(.1012285363,.2223810345,.3137066459,.3626837834);float sum=0.;for(int i=0;i<4;i++)sum+=weights[i]*(length(cmiDomeDerivative(p,q,v,w,u*(1.-x[i])*.5))+length(cmiDomeDerivative(p,q,v,w,u*(1.+x[i])*.5)));return sum*u*.5;}
uint cmiDomeCachedAssignment=0u;float cmiDomeCachedTime=-1.;vec3 cmiDomeCachedPoint=vec3(0),cmiDomeCachedVelocity=vec3(0);
void cmiDomeSample(uint a,float time,out vec3 point,out vec3 velocity){
 uint b=cmiStarBase(cmiStarSlot(a));time=cmiStarTime(b,time);
 if(a==cmiDomeCachedAssignment&&time==cmiDomeCachedTime){point=cmiDomeCachedPoint;velocity=cmiDomeCachedVelocity;return;}
 vec4 ring=cmiDomeRing(a),times=cmiStarVec(b+4u);float angle;
 if(cmiDomePhase(b,0u,time)==1.){angle=ring.z+6.*(time-times.x)/ring.x;point=vec3(ring.x*cos(angle),ring.y,ring.x*sin(angle));velocity=vec3(-sin(angle),0,cos(angle))*6.;}
 else{
  vec4 record=cmiDomeRecord(a);uint base=uint(record.x),lo=0u,hi=uint(record.y)-1u;float distanceAlong=clamp((time-times.z)*6.,0.,record.z*6.);
  for(int k=0;k<12;k++){if(hi-lo<=1u)break;uint mid=(lo+hi)/2u;if(cmiDomeWord(base,mid*4u+2u)<distanceAlong)lo=mid;else hi=mid;}
  vec4 first=cmiDomeNode(base,lo,0u),last=cmiDomeNode(base,hi,0u),fv=cmiDomeNode(base,lo,1u),lv=cmiDomeNode(base,hi,1u);float dt=lv.w-fv.w,local=distanceAlong-first.w,u=clamp(local/max(last.w-first.w,.000001),0.,1.);vec3 v=fv.xyz*dt,w=lv.xyz*dt;
  float curveLength=last.w-first.w,total=cmiDomeArc(first.xyz,last.xyz,v,w,1.),lower=0.,upper=1.;
  for(int k=0;k<8;k++){float travelled=cmiDomeArc(first.xyz,last.xyz,v,w,u)*curveLength/max(total,.000001),error=local-travelled;if(abs(error)<.000001)break;if(error>0.)lower=u;else upper=u;float next=u+error/max(length(cmiDomeDerivative(first.xyz,last.xyz,v,w,u))*curveLength/max(total,.000001),.000001);u=next>lower&&next<upper?next:(lower+upper)*.5;}

  point=cmiDomeHermite(first.xyz,last.xyz,v,w,u);vec3 derivative=cmiDomeDerivative(first.xyz,last.xyz,v,w,u);float magnitude=length(derivative);velocity=magnitude>.000001?derivative*(6./magnitude):vec3(-sin(ring.z),0,cos(ring.z))*6.;
  // Cyclic ring permutation selects a nearby canonical channel. Only the entry
  // bends from its true moving-ring phase; the shared interior remains unrotated.
  float age=clamp(time-times.z,0.,1.);if(age<1.){
   angle=ring.z+6.*(times.z-times.x)/ring.x;vec3 entry=vec3(ring.x*cos(angle),ring.y,ring.x*sin(angle)),entryVelocity=vec3(-sin(angle),0,cos(angle))*6.;
   vec4 p0=cmiDomeNode(base,0u,0u),p1=cmiDomeNode(base,1u,0u),v0=cmiDomeNode(base,0u,1u),v1=cmiDomeNode(base,1u,1u);vec3 a0=v0.xyz*(v1.w-v0.w),a1=v1.xyz*(v1.w-v0.w);
   vec3 initialVelocity=normalize(a0)*(6.*cmiDomeArc(p0.xyz,p1.xyz,a0,a1,1.)/(p1.w-p0.w));vec3 offset=entry-p0.xyz,delta=entryVelocity-initialVelocity;
   float h=1.-3.*age*age+2.*age*age*age,g=age-2.*age*age+age*age*age;point+=offset*h+delta*g;
   velocity*=total/max(curveLength,.000001);velocity+=offset*(-6.*age+6.*age*age)+delta*(1.-4.*age+3.*age*age);if(length(velocity)>.000001)velocity=normalize(velocity)*6.;
  }

 }
 angle=cmiStarVec(b+20u).z;float c=cos(angle),s=sin(angle);point=vec3(c*point.x-s*point.z,point.y-.3,s*point.x+c*point.z)+cmiDomeCenter(b,time);velocity=vec3(c*velocity.x-s*velocity.z,velocity.y,s*velocity.x+c*velocity.z);
 cmiDomeCachedAssignment=a;cmiDomeCachedTime=time;cmiDomeCachedPoint=point;cmiDomeCachedVelocity=velocity;
}
vec3 cmiDomePoint(uint a,float seed,float time,vec3 home){vec3 point,velocity;cmiDomeSample(a,time,point,velocity);return point;}
vec3 cmiDomeVelocity(uint a,float time){vec3 point,velocity;cmiDomeSample(a,time,point,velocity);return velocity;}
