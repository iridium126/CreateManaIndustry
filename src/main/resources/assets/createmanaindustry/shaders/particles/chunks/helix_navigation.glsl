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
vec3 cmiHelixOffset(vec3 tangent,float u,uint group,float seed,float radius){
 vec3 side=normalize(cross(vec3(0,1,0),tangent)),up=cross(tangent,side);
 float r=mix(.15,radius,smoothstep(0.,.2,u)),angle=u*15.707963+float(group)*3.14159265;
 float width=min(.6,.2+radius*.12)*smoothstep(0.,.2,u);float noise=(cmiHash1(seed*37.)-.5)*width;
 return side*(cos(angle)*(r+noise))+up*(sin(angle)*(r+noise))-vec3(0,radius-r,0);
}
vec3 cmiHelixPoint(uint assignment,float seed,float time,vec3 home){
 uint b=cmiStarBase((assignment>>1u)&3u),group=(assignment>>3u)&31u,rank=assignment>>8u;
 vec4 times=cmiStarVec(b+4u),h=cmiHelixTimes(b),info=cmiHelixInfo(b),column=cmiStarColumn(b,group);
 float u=cmiHelixU((float(rank)+.5)/max(column.y,1.)),heading=cmiHelixHeading(b,time);vec3 forward=vec3(cos(heading),0,sin(heading));
 vec3 center=cmiStarVec(b+24u).xyz;
 if(cmiStarVec(b+20u).y<3.)center=mix(cmiStarVec(b+28u).xyz,center,clamp((time-cmiStarVec(b+28u).w)/.5,0.,1.));
 vec3 line=center-forward*(u*info.x)+cmiHelixOffset(forward,u,group,seed,info.y);
 if(time<times.y)return cmiStarVec(b+8u).xyz-forward*(u*info.x)+cmiHelixOffset(forward,u,group,seed,info.y);
 if(cmiStarVec(b).y<1.5||time<times.z){
  if(time>=h.x)return line;float f=clamp((time-times.y)/max(h.x-times.y,1.),0.,1.);vec3 origin=cmiStarRoute(b,f);
  origin=mix(origin,center,smoothstep(.7,1.,f));return origin-forward*(u*info.x)+cmiHelixOffset(forward,u,group,seed,info.y);
 }
 float arc=cmiHelixProgress(b,time)-u*info.x;vec3 curve,tangent;cmiHelixCurve(b,cmiHelixParameter(b,arc),curve,tangent);
 vec3 frozen=curve+cmiHelixOffset(tangent,u,group,seed,info.y);
 if(time<h.y)return mix(line,frozen,smoothstep(times.z,h.y,time));
 if(time<h.z)return frozen;
 float age=time-h.z;vec3 side=normalize(cross(vec3(0,1,0),tangent));float signSide=group==0u?1.:-1.;
 vec3 split=frozen+side*(signSide*(info.y+4.)*smoothstep(0.,2.,age));
 if(age<2.)return split;
 float duration=max(1.,times.w-h.z-2.),f=clamp((age-2.)/duration,0.,1.);
 vec3 entry=cmiStarVec(b+12u).xyz,reverse=cmiStarRoute(b,1.-f);
 vec3 bridge=mix(split,reverse,smoothstep(0.,.35,f));bridge+=side*signSide*4.*sin(3.14159265*f)*(1.-f);
 return mix(bridge,home,smoothstep(.7,1.,f));
}
