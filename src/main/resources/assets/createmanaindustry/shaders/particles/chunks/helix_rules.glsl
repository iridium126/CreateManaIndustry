bool cmiHelix(uint b){return cmiStarVec(b+20u).x>1.5;}
vec4 cmiHelixInfo(uint b){return cmiStarVec(b+288u);}
vec4 cmiHelixTimes(uint b){return cmiStarVec(b+292u);}
float cmiHelixPhase(uint b,float time){
 vec4 t=cmiStarVec(b+4u),h=cmiHelixTimes(b),m=cmiStarVec(b);
 if(m.y>2.5||time>t.w)return 6.;if(time<t.y)return 1.;if(m.y<1.5||time<t.z)return 2.;if(time<h.y)return 3.;return time<h.z?4.:5.;
}
float cmiHelixHeading(uint b,float time){
 vec4 frame=cmiStarVec(b+20u);if(frame.y>=3.)return frame.z;
 return mix(cmiStarVec(b+16u).z,frame.z,clamp((time-cmiStarVec(b+28u).w)/.5,0.,1.));
}
float cmiAttackSpeed(float identity,float time,float base){
 int slot=cmiStarClaim(identity,time);if(slot<0)return base;uint b=cmiStarBase(uint(slot));
 return cmiHelix(b)&&cmiHelixPhase(b,cmiStarTime(b,time))==4.?cmiHelixInfo(b).w:base;
}
float cmiHelixProgress(uint b,float time){
 vec4 h=cmiHelixTimes(b),info=cmiHelixInfo(b);float total=cmiStarVec(b+296u).x;
 float duration=h.z-h.y,elapsed=clamp(time-h.y,0.,duration),distance;
 if(elapsed<.5)distance=info.z*elapsed*elapsed;
 else if(elapsed>duration-.5){float remaining=duration-elapsed;distance=total-info.x-info.z*remaining*remaining;}
 else distance=info.z*(elapsed-.25);
 return info.x+clamp(distance,0.,total-info.x);
}
