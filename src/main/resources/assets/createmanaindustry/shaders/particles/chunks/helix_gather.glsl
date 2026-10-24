// Immutable rigid reference segments. Same sample drives motion and readiness.
const uint CMI_HELIX_FRAMES=418068u,CMI_HELIX_FRAME_WORDS=292u;
uint cmiHelixFrameBase(uint b){return CMI_HELIX_FRAMES+((b-131088u)/312u)*CMI_HELIX_FRAME_WORDS;}
bool cmiHelixHasReference(uint b){return cmiStarVec(cmiHelixFrameBase(b)).x>.5;}
uint cmiHelixRecoveryBase(uint b){return 468388u+((b-131088u)/312u)*20u;}
float cmiHelixReferenceSeconds(uint b,float time){return cmiStarVec(b+20u).y>=3.?cmiStarVec(b+28u).w-cmiStarVec(b+4u).x:max(0.,(uDomePreciseClock?uDomeElapsed[(b-131088u)/312u]+cmiStormElapsed(time,uTimeSec):cmiStarTime(b,time)-cmiStarVec(b+4u).x)-.25);}
uint cmiHelixFrame(uint b,float time){uint base=cmiHelixFrameBase(b),count=uint(cmiStarVec(base).x+.5),frame=base+4u;float seconds=cmiHelixReferenceSeconds(b,time);for(uint i=1u;i<9u;i++){if(i>=count)break;uint next=base+4u+i*32u;if(cmiStarVec(next).y>seconds)break;frame=next;}return frame;}
bool cmiHelixReferenceValid(uint b,float time){if(!cmiHelixHasReference(b))return false;uint frame=cmiHelixFrame(b,time);float seconds=cmiHelixReferenceSeconds(b,time);vec4 recovery=cmiStarVec(cmiHelixRecoveryBase(b));return !(recovery.w>.5&&seconds<recovery.x+.25)&&seconds>=cmiStarVec(frame).y-.001&&seconds<=cmiStarVec(frame).y+.501;}
vec4 cmiHelixQMul(vec4 a,vec4 b){return vec4(a.w*b.xyz+b.w*a.xyz+cross(a.xyz,b.xyz),a.w*b.w-dot(a.xyz,b.xyz));}
vec4 cmiHelixQExp(vec3 v){float a=length(v);return vec4(v*(a<.00001?.5:sin(a*.5)/a),cos(a*.5));}
vec3 cmiHelixRotate(vec4 q,vec3 p){return p+2.*cross(q.xyz,cross(q.xyz,p)+q.w*p);}
void cmiHelixReference(uint b,float time,out vec3 center,out vec3 motion,out vec4 rotation,out vec3 omega){
 uint frame=cmiHelixFrame(b,time);vec4 meta=cmiStarVec(frame);float seconds=cmiHelixReferenceSeconds(b,time),t=clamp(seconds-meta.y,0.,.25),u=t/.25;
 vec3 v=cmiStarVec(frame+8u).xyz,dv=cmiStarVec(frame+12u).xyz-v,w=cmiStarVec(frame+20u).xyz,dw=cmiStarVec(frame+24u).xyz-w;
 vec3 origin=cmiStarVec(frame+4u).xyz,displacement=cmiStarVec(frame+28u).xyz;float u2=u*u,u3=u2*u;
 center=origin+v*(.25*(u3-2.*u2+u))+displacement*(-2.*u3+3.*u2)+(v+dv)*(.25*(u3-u2));
 motion=v*(3.*u2-4.*u+1.)+displacement*((-6.*u2+6.*u)/.25)+(v+dv)*(3.*u2-2.*u);
 vec3 r=w*t+dw*(t*t/.5),rd=w+dw*u;float a=length(r);
 omega=rd;if(a>.00001)omega+=cross(r,rd)*((1.-cos(a))/(a*a))+cross(r,cross(r,rd))*((a-sin(a))/(a*a*a));
 rotation=normalize(cmiHelixQMul(cmiHelixQExp(r),cmiStarVec(frame+16u)));
 float extra=clamp(seconds-meta.y-.25,0.,.25),travel=extra-extra*extra/.5;
 center+=motion*travel;rotation=normalize(cmiHelixQMul(cmiHelixQExp(omega*travel),rotation));motion*=1.-extra/.25;omega*=1.-extra/.25;
 uint recovery=cmiHelixRecoveryBase(b);vec4 restore=cmiStarVec(recovery);
 if(restore.w>.5&&seconds<restore.x+.25){
  float z=clamp((seconds-restore.x)/.25,0.,1.),z2=z*z,z3=z2*z,h=2.*z3-3.*z2+1.,g=.25*(z3-2.*z2+z),dh=(6.*z2-6.*z)/.25,dg=3.*z2-4.*z+1.;
  vec3 offset=cmiStarVec(recovery+4u).xyz,delta=cmiStarVec(recovery+8u).xyz,angle=cmiStarVec(recovery+12u).xyz,tangent=cmiStarVec(recovery+16u).xyz;
  vec3 r=angle*h+tangent*g,rd=angle*dh+tangent*dg;float a=length(r);vec3 w=rd;if(a>.00001)w+=cross(r,rd)*((1.-cos(a))/(a*a))+cross(r,cross(r,rd))*((a-sin(a))/(a*a*a));
  vec4 q=cmiHelixQExp(r);center+=offset*h+delta*g;motion+=offset*dh+delta*dg;rotation=normalize(cmiHelixQMul(q,rotation));omega=w+cmiHelixRotate(q,omega);
 }
}
void cmiHelixGatherSample(uint a,float time,out vec3 point,out vec3 velocity){
 uint b=cmiStarBase(cmiStarSlot(a));vec3 center,motion,omega;vec4 rotation;cmiHelixReference(b,time,center,motion,rotation,omega);
 vec3 offset=cmiHelixRotate(rotation,cmiHelixLocal(a));point=center+offset;velocity=motion+cross(omega,offset);
}
