vec3 cmiStarRoute(uint b,float fraction){
 float lengthTotal=0.;vec3 prev=cmiStarVec(b+32u).xyz;
 for(uint i=1u;i<6u;i++){vec4 point=cmiStarVec(b+32u+i*4u);if(point.w<.5)break;lengthTotal+=distance(prev,point.xyz);prev=point.xyz;}
 float seek=clamp(fraction,0.,1.)*lengthTotal;prev=cmiStarVec(b+32u).xyz;
 for(uint i=1u;i<6u;i++){vec4 point=cmiStarVec(b+32u+i*4u);if(point.w<.5)break;float len=distance(prev,point.xyz);if(seek<=len)return mix(prev,point.xyz,seek/max(len,1e-5));seek-=len;prev=point.xyz;}return prev;
}
#pragma cmi_include chunks/dome_navigation.glsl
#pragma cmi_include chunks/helix_navigation.glsl
vec3 cmiStarPoint(uint assignment,float seed,float time,vec3 home){
 uint slot=cmiStarSlot(assignment),col=(assignment>>3u)&31u,rank=assignment>>8u,b=cmiStarBase(slot);
 if(cmiDome(b))return cmiDomePoint(assignment,seed,time,home);
 time=cmiStarTime(b,time);vec4 meta=cmiStarVec(b),t=cmiStarVec(b+4u),crown=cmiStarVec(b+8u),aim=cmiStarVec(b+12u),landing=cmiStarLanding(b,col),column=cmiStarColumn(b,col);
 if(cmiHelix(b))return cmiHelixPoint(assignment,seed,time,home);
 float angle=6.2831853*(float(col)/max(meta.z,1.))+aim.w*.0001;
 vec3 crownOffset=vec3(cos(angle)*crown.w,sin(angle)*crown.w*.258819,sin(angle)*crown.w*.965926);
 float a=seed*6.2831853;float spread=sqrt(cmiHash1(seed*77.1))*column.z;
 vec3 cloud=vec3(cos(a)*spread,(cmiHash1(seed*61.3)-.5)*5.,sin(a)*spread);
 if(time<t.y)return crown.xyz+crownOffset+cloud;
 if(meta.y<1.5||time<t.z){float f=clamp((time-t.y)/max(t.z-t.y,1.),0.,1.);vec3 offset=mix(crownOffset,vec3(0),smoothstep(0.,1.,f));return cmiStarRoute(b,f)+offset+cloud;}
 vec3 top=vec3(landing.x,max(aim.y,landing.y+10.),landing.z);
 if(time<landing.w)return top+cloud;
 if(time<column.x){float stagger=.7*float(rank)/max(column.y,1.);float duration=max(column.x-landing.w-stagger,.1);float f=clamp((time-landing.w-stagger)/duration,0.,1.);
  float twist=a+(time-landing.w)*1.4;float r=spread*(1.-.35*f);return mix(top,landing.xyz,f)+vec3(cos(twist)*r,0,sin(twist)*r);
 }
 float after=time-column.x;
 if(after<1.5){float f=after/1.5;return landing.xyz+vec3(cos(a)*(spread+4.*f),3.*f,sin(a)*(spread+4.*f));}
 return home;
}
