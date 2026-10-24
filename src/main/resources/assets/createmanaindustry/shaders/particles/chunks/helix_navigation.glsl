float cmiHelixParameter(uint b,float arc){
 float total=cmiStarVec(b+296u).x;if(arc<=0.)return 0.;if(arc>=total)return 1.;
 uint low=0u,high=47u;for(int step=0;step<6;step++){if(high-low<=1u)break;uint mid=(low+high)/2u;if(cmiStarVec(b+72u+mid*4u).w<arc)low=mid;else high=mid;}
 float a=cmiStarVec(b+72u+low*4u).w,z=cmiStarVec(b+72u+high*4u).w;return (float(low)+(arc-a)/max(z-a,1e-5))/47.;
}
void cmiHelixCurve(uint b,float parameter,out vec3 point,out vec3 tangent){
 int count=int(cmiStarVec(b+296u).y+.5);float t=clamp(parameter,0.,1.)*float(count-1);int segment=min(count-2,int(t));float u=t-float(segment);
 vec3 p0=cmiStarVec(b+264u+uint(max(0,segment-1))*4u).xyz,p1=cmiStarVec(b+264u+uint(segment)*4u).xyz;
 vec3 p2=cmiStarVec(b+264u+uint(segment+1)*4u).xyz,p3=cmiStarVec(b+264u+uint(min(count-1,segment+2))*4u).xyz;
 vec3 m1=segment==0?p2-p1:(p2-p0)*.5,m2=segment==count-2?p2-p1:(p3-p1)*.5;
 point=(2.*u*u*u-3.*u*u+1.)*p1+(u*u*u-2.*u*u+u)*m1+(-2.*u*u*u+3.*u*u)*p2+(u*u*u-u*u)*m2;
 tangent=normalize((6.*u*u-6.*u)*p1+(3.*u*u-4.*u+1.)*m1+(-6.*u*u+6.*u)*p2+(3.*u*u-2.*u)*m2);
}
float cmiHelixU(float fraction){return fraction<.3?.2*pow(fraction/.3,1.5):.2+.8*(fraction-.3)/.7;}
vec3 cmiHelixOffset(vec3 tangent,float u,uint group,vec2 scatter,float radius,float length){
 vec3 side=normalize(cross(vec3(0,1,0),tangent)),up=cross(tangent,side);
 float r=mix(.15,radius,smoothstep(0.,.2,u)),angle=u*15.707963+float(group)*3.14159265;
 vec3 radial=side*cos(angle)+up*sin(angle);float v=clamp(u/.2,0.,1.),dr=u>0.&&u<.2?(radius-.15)*6.*v*(1.-v)/.2:0.;
 vec3 strand=normalize(-tangent*length+radial*dr+(-side*sin(angle)+up*cos(angle))*r*15.707963);
 vec3 normal=normalize(radial-strand*dot(radial,strand)),binormal=normalize(cross(strand,normal));
 return radial*r+(normal*scatter.x+binormal*scatter.y)*(.2*radius);
}
vec3 cmiHelixPoint(uint assignment,float seed,float time,vec3 home){
 uint b=cmiStarBase(cmiStarSlot(assignment));
 if(cmiHelixHasReference(b)){
  if(cmiHelixPhase(b,time)==1.){vec3 point,velocity;cmiHelixGatherSample(assignment,time,point,velocity);return point;}
  vec3 origin,motion,omega;vec4 rotation;cmiHelixReference(b,time,origin,motion,rotation,omega);
  float progress=cmiHelixProgress(b,time),angle=-cmiHelixPitch()*progress;vec3 local=cmiHelixLocal(assignment);
  vec2 radial=mat2(cos(angle),sin(angle),-sin(angle),cos(angle))*local.yz;
  return origin+cmiHelixRotate(rotation,vec3(local.x+progress,radial));
 }
 vec3 axis=normalize(cmiStarVec(b+308u).xyz);
 vec3 center=cmiStarVec(b+24u).xyz;
 if(cmiStarVec(b+20u).y<3.){float t=clamp((time-cmiStarVec(b+28u).w)/.25,0.,1.);axis=normalize(mix(cmiStarVec(b+300u).xyz,axis,t));center=mix(cmiStarVec(b+28u).xyz,center,t);}
 vec3 side=cmiStarVec(b+304u).xyz;side=normalize(side-axis*dot(axis,side));vec3 up=cross(axis,side),local=cmiHelixLocal(assignment);
 float progress=cmiHelixPhase(b,time)==1.?0.:cmiHelixProgress(b,time),angle=-cmiHelixPitch()*progress;
 vec2 radial=mat2(cos(angle),sin(angle),-sin(angle),cos(angle))*local.yz;
 return center+axis*(local.x+progress)+side*radial.x+up*radial.y;
}
vec3 cmiHelixVelocity(uint assignment,float time){
 uint base=cmiStarBase(cmiStarSlot(assignment));vec3 axis=normalize(cmiStarVec(base+308u).xyz),side=cmiStarVec(base+304u).xyz;
 if(cmiHelixHasReference(base)){vec3 center,motion,omega;vec4 rotation;cmiHelixReference(base,time,center,motion,rotation,omega);axis=cmiHelixRotate(rotation,vec3(1,0,0));side=cmiHelixRotate(rotation,vec3(0,1,0));}
 side=normalize(side-axis*dot(axis,side));vec3 up=cross(axis,side),local=cmiHelixLocal(assignment);
 float angle=-cmiHelixPitch()*cmiHelixProgress(base,time),speed=cmiHelixAdvanceSpeed(base,time);
 vec2 rotated=mat2(cos(angle),sin(angle),-sin(angle),cos(angle))*local.yz;
 return axis*speed+cross(axis,side*rotated.x+up*rotated.y)*(-cmiHelixPitch()*speed);
}
