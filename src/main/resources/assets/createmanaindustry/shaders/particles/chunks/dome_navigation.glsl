// Seven explicit identity groups; geometry is analytic, with no frame uploads.
vec3 cmiDomeCenter(uint b,float time){
 vec4 center=cmiStarVec(b+24u),previous=cmiStarVec(b+28u);
 if(cmiStarVec(b+20u).y>=3.)return center.xyz;
 return mix(previous.xyz,center.xyz,clamp((time-previous.w)/.5,0.,1.));
}
vec3 cmiDomeRibbon(uint b,uint group,uint rank,float seed,float time){
 float radius=cmiStarVec(b+8u).w;vec4 frame=cmiStarVec(b+20u),times=cmiStarVec(b+4u),column=cmiStarColumn(b,group);
 float order=float(rank)/max(column.y,1.);
 // Cosine endpoints have zero velocity. A radius-dependent period stays below the 6 b/s cap.
 float flow=(time-times.z)*6.2831853/max(12.,radius*1.8)+order*6.2831853;
 float theta=.30+1.2707963*(.5-.5*cos(flow));
 float jitter=(cmiHash1(seed*61.3)-.5)*.06;
 float angle=frame.z+frame.w*float(group)*1.04719755+(cmiHash1(seed*77.1)-.5)*.31415927;
 return vec3(cos(angle)*radius*sin(theta),radius*cos(theta),sin(angle)*radius*sin(theta))+vec3(jitter,0.,jitter);
}
vec3 cmiDomePoint(uint assignment,float seed,float time,vec3 home){
 uint b=cmiStarBase((assignment>>1u)&3u),group=(assignment>>3u)&31u,rank=assignment>>8u;
 vec4 times=cmiStarVec(b+4u),crown=cmiStarVec(b+8u),meta=cmiStarVec(b),column=cmiStarColumn(b,group),landing=cmiStarLanding(b,group),frame=cmiStarVec(b+20u);
 vec3 center=cmiDomeCenter(b,time);float radius=crown.w;
 float angle=frame.z+frame.w*float(group)*1.04719755;
 vec3 launchOffset=vec3(cos(angle)*radius*.7,(cmiHash1(seed*23.)-.5)*2.,sin(angle)*radius*.7);
 if(group==6u){float a=6.2831853*float(rank)/max(column.y,1.);launchOffset=vec3(cos(a)*radius*.3,1.,sin(a)*radius*.3);}
 if(time<times.y)return crown.xyz+launchOffset;
 vec3 offset;
 if(group==6u){float a=6.2831853*float(rank)/max(column.y,1.)+(time-times.z)*.08;float r=radius*.29+(cmiHash1(seed*61.)-.5)*.5;offset=vec3(cos(a)*r,radius*.96+(cmiHash1(seed*17.)-.5)*.6,sin(a)*r);}
 else offset=cmiDomeRibbon(b,group,rank,seed,time);
 if(meta.y<1.5||time<times.z){
  float f=clamp((time-times.y)/max(times.z-times.y,1.),0.,1.);
  vec3 route=cmiStarRoute(b,f);
  route=mix(route,center+vec3(0,radius,0),smoothstep(.75,1.,f));
  return route+mix(launchOffset,offset-vec3(0,radius,0),smoothstep(0.,1.,f));
 }
 if(group==6u){
  float age=time-(cmiStarColumn(b,5u).w-1.);
  if(age<=0.)return center+offset;
  float rise=smoothstep(0.,4.,age);
  vec3 escape=center+vec3(offset.x*(1.+rise),mix(offset.y,min(28.,radius+8.),rise),offset.z*(1.+rise));
  return mix(escape,home,smoothstep(5.,9.,age));
 }
 if(time<column.w)return center+offset;
 // Freeze each petal's flow at warning start; warning and closing join continuously.
 vec3 frozen=cmiDomeRibbon(b,group,rank,seed,column.w);
 vec3 radial=vec3(cos(angle),0,sin(angle));
 float width=dot(frozen,vec3(-sin(angle),0,cos(angle)));
 float gather=smoothstep(0.,1.5,time-column.w);
 frozen-=vec3(-sin(angle),0,cos(angle))*width*.45*gather;
 frozen.y-=.35*gather;
 if(time<landing.w)return center+frozen;
 float f=smoothstep(0.,1.,(time-landing.w)/max(column.x-landing.w,.1));
 // Upper ribbon stays near its crown hinge until the inward fold progresses.
 vec3 foot=radial*(2.+cmiHash1(seed*39.)*1.5)+vec3(0,.65,0);
 float hinge=radius*.95,turn=f*1.04719755;
 vec3 tangent=vec3(-sin(angle),0,cos(angle));
 float along=dot(frozen,radial),down=frozen.y-hinge;
 float foldedRadius=max(2.,along*cos(turn)+down*sin(turn));
 float foldedY=hinge-along*sin(turn)+down*cos(turn);
 foldedY=mix(foldedY,max(.65,foldedY),smoothstep(0.,.1,f));
 vec3 folded=radial*foldedRadius+tangent*dot(frozen,tangent)*cos(turn)+vec3(0,foldedY,0);
 folded=mix(folded,foot,smoothstep(.5,1.,f));
 if(time<column.x)return center+folded;
 float age=time-column.x,ascent=max(4.,min(28.,radius+8.)/4.5);
 float a=angle+(cmiHash1(seed*11.)-.5)*.7;
 vec3 lane=vec3(cos(a),0,sin(a))*(2.+cmiHash1(seed*97.)*2.5);
 vec3 escape=center+mix(foot,lane+vec3(0,min(28.,radius+8.),0),smoothstep(0.,ascent,age));
 return mix(escape,home,smoothstep(ascent+1.,ascent+5.,age));
}
