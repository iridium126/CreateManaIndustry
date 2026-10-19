// Shared arc tables; interval lookup is corrected against actual, stored chord lengths.
vec3 cmiDomeCenter(uint b,float time){return cmiStarVec(b+24u).xyz;}
void cmiDomeSample(uint a,float time,out vec3 point,out vec3 velocity){
 uint b=cmiStarBase(cmiStarSlot(a));vec4 r=cmiDomeRecord(a);float arc=clamp(r.y+(cmiDomePhase(b,0u,time)!=1.?max(0.,time-cmiStarVec(b+4u).z)*6.:0.),0.,r.z);
 uint base=CMI_DOME_DATA+4u+8192u*4u+uint(r.x)*4096u,i=min(1022u,uint(arc/r.z*1023.));vec4 first=cmiStarVec(base+i*4u),last=cmiStarVec(base+(i+1u)*4u);
 if(arc<first.w||arc>last.w){uint lo=0u,hi=1023u;for(int k=0;k<10;k++){uint mid=(lo+hi)/2u;if(cmiStarVec(base+mid*4u).w<arc)lo=mid;else hi=mid;}i=lo;first=cmiStarVec(base+i*4u);last=cmiStarVec(base+(i+1u)*4u);}
 point=mix(first.xyz,last.xyz,clamp((arc-first.w)/max(last.w-first.w,.000001),0.,1.));velocity=normalize(last.xyz-first.xyz)*6.;
}
vec3 cmiDomePoint(uint a,float seed,float time,vec3 home){uint b=cmiStarBase(cmiStarSlot(a));vec3 point,velocity;cmiDomeSample(a,time,point,velocity);float angle=cmiStarVec(b+20u).z,c=cos(angle),s=sin(angle);return cmiDomeCenter(b,time)+vec3(c*point.x-s*point.z,point.y-.3,s*point.x+c*point.z);}
vec3 cmiDomeVelocity(uint a,float time){uint b=cmiStarBase(cmiStarSlot(a));if(cmiDomeMemberPhase(a,time)!=4.)return vec3(0);vec3 point,velocity;cmiDomeSample(a,time,point,velocity);float angle=cmiStarVec(b+20u).z,c=cos(angle),s=sin(angle);return vec3(c*velocity.x-s*velocity.z,velocity.y,s*velocity.x+c*velocity.z);}
