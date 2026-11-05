// Sweep the actual simulation segment, clipped to the attack's active part of this tick.
bool cmiContactInterval(float now,float dt,float start,float end,out vec2 interval){
 if(dt<=0.||end<=start)return false;
 interval=vec2(max(0.,(start-now+dt)/dt),min(1.,(end-now+dt)/dt));
 return interval.x<=interval.y&&interval.y>=0.&&interval.x<=1.&&now-dt<end;
}
vec3 cmiContactClosest(vec3 before,vec3 after,vec3 target,vec2 interval,out float alpha){
 vec3 step=after-before;float distance2=dot(step,step);
 alpha=clamp(distance2>1e-12?dot(target-before,step)/distance2:1.,interval.x,interval.y);
 return before+step*alpha;
}
// Intersect the active time window with the interval spent inside a sphere.
// The projection form avoids subtracting two large terms in the discriminant
// when a target crosses a very short attack interval at high speed.
bool cmiContactClipSphere(vec3 before,vec3 after,float radius,inout vec2 interval){
 vec3 step=after-before;float a=dot(step,step),rr=radius*radius;
 if(a<=1e-12)return dot(before,before)<=rr;
 float nearest=-dot(before,step)/a;
 vec3 offset=before+nearest*step;
 float margin=rr-dot(offset,offset);
 if(margin<0.)return false;
 float reach=sqrt(margin/a);
 interval=vec2(max(interval.x,nearest-reach),min(interval.y,nearest+reach));
 return interval.x<=interval.y;
}
bool cmiContactHit(vec3 before,vec3 after,vec3 target,float radius,vec2 interval,out float alpha){
 bool hit=cmiContactClipSphere(before-target,after-target,radius,interval);alpha=interval.x;return hit;
}
bool cmiContactMovingHit(vec3 before,vec3 after,vec3 targetBefore,vec3 targetAfter,float radius,vec2 interval,out float alpha){
 bool hit=cmiContactClipSphere(before-targetBefore,after-targetAfter,radius,interval);alpha=interval.x;return hit;
}
