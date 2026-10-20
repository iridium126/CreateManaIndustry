// Immutable, globally shared quantized trajectory keys. Only compute navigation reads this sampler.
uniform usamplerBuffer cmiDomeKeys;
vec4 cmiDomeKey(uint key){uint a=texelFetch(cmiDomeKeys,int(key*2u)).r,b=texelFetch(cmiDomeKeys,int(key*2u+1u)).r;return vec4(vec3(float(a&65535u),float(a>>16u),float(b&65535u))*(80./65535.)-40.,float(b>>16u)*.001);}
vec3 cmiDomeCenter(uint b,float time){return cmiStarVec(b+24u).xyz;}
uint cmiDomeCachedAssignment=0u;float cmiDomeCachedAge=-1.;vec3 cmiDomeCachedPoint=vec3(0),cmiDomeCachedVelocity=vec3(0);
void cmiDomeSample(uint a,float time,out vec3 point,out vec3 velocity){
 uint b=cmiStarBase(cmiStarSlot(a));vec4 record=cmiDomeRecord(a);float age=cmiDomePhase(b,0u,time)==1.?0.:clamp(time-cmiStarVec(b+4u).z,0.,record.z);
 if(a==cmiDomeCachedAssignment&&age==cmiDomeCachedAge){point=cmiDomeCachedPoint;velocity=cmiDomeCachedVelocity;return;}
 uint base=uint(record.x),lo=0u,hi=uint(record.y)-1u;
 if(age<=0.)hi=1u;else for(int k=0;k<11;k++){if(hi-lo<=1u)break;uint mid=(lo+hi)/2u;if(cmiDomeKey(base+mid).w<age)lo=mid;else hi=mid;}
 vec4 first=cmiDomeKey(base+lo),last=cmiDomeKey(base+hi);point=mix(first.xyz,last.xyz,clamp((age-first.w)/max(last.w-first.w,.000001),0.,1.));velocity=normalize(last.xyz-first.xyz)*6.;
 cmiDomeCachedAssignment=a;cmiDomeCachedAge=age;cmiDomeCachedPoint=point;cmiDomeCachedVelocity=velocity;
}
vec3 cmiDomePoint(uint a,float seed,float time,vec3 home){uint b=cmiStarBase(cmiStarSlot(a));vec3 point,velocity;cmiDomeSample(a,time,point,velocity);float angle=cmiStarVec(b+20u).z,c=cos(angle),s=sin(angle);return cmiDomeCenter(b,time)+vec3(c*point.x-s*point.z,point.y-.3,s*point.x+c*point.z);}
vec3 cmiDomeVelocity(uint a,float time){uint b=cmiStarBase(cmiStarSlot(a));if(cmiDomeMemberPhase(a,time)!=4.)return vec3(0);vec3 point,velocity;cmiDomeSample(a,time,point,velocity);float angle=cmiStarVec(b+20u).z,c=cos(angle),s=sin(angle);return vec3(c*velocity.x-s*velocity.z,velocity.y,s*velocity.x+c*velocity.z);}
