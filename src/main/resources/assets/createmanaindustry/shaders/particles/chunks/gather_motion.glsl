// Physical budgets are shared by gathering and temporary navigation, not readiness targets.
vec3 cmiGatherVelocity(vec3 velocity,vec3 desired,float cap,float dt){
 float oldSpeed=length(velocity),speed=clamp(min(cap,length(desired)),max(0.,oldSpeed-18.*dt),oldSpeed+18.*dt);
 vec3 direction=length(desired)>.000001?normalize(desired):(oldSpeed>.000001?velocity/oldSpeed:vec3(0,0,1));
 if(oldSpeed>.0001&&speed>.0001){float cosine=clamp((oldSpeed*oldSpeed+speed*speed-324.*dt*dt)/(2.*oldSpeed*speed),-1.,1.);direction=cmiDomeTurn(velocity/oldSpeed,direction,dt,.0001,min(3.141592654,acos(cosine)/max(dt,.0001)));}
 return direction*speed;
}
vec3 cmiGatherFollowing(vec3 pos,vec3 previous,vec3 referenceVelocity){vec3 error=previous-pos;return referenceVelocity+error*min(6.,.6/max(length(error),.000001));}
