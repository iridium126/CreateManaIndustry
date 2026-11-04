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
bool cmiContactHit(vec3 before,vec3 after,vec3 target,float radius,vec2 interval,out float alpha){
 vec3 step=after-before,offset=before-target;float a=dot(step,step);
 vec3 first=offset+step*interval.x;
 if(dot(first,first)<=radius*radius){alpha=interval.x;return true;}
 if(a<=1e-12)return false;
 float b=dot(offset,step),c=dot(offset,offset)-radius*radius,disc=b*b-a*c;
 if(disc<0.)return false;
 float entry=(-b-sqrt(max(0.,disc)))/a,exit=(-b+sqrt(max(0.,disc)))/a;
 alpha=max(interval.x,entry);return alpha<=interval.y&&exit>=interval.x;
}
