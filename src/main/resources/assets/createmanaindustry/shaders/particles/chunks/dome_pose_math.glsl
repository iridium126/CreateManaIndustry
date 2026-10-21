// Stable-member navigation orientation, independent of vertex count and draw pass.
uint cmiDomePackPose(vec2 angles){
 float yaw=mod(angles.x+3.141592654,6.28318530718)/6.28318530718;
 uvec2 encoded=uvec2(round(clamp(vec2(yaw,angles.y/3.141592654+.5),0.,1.)*65535.));return encoded.x|(encoded.y<<16u);
}
vec2 cmiDomeUnpackPose(uint encoded){return vec2(float(encoded&65535u)*(6.28318530718/65535.)-3.141592654,float(encoded>>16u)*(3.141592654/65535.)-1.570796327);}
vec3 cmiDomePoseDirection(vec2 angles){
 return vec3(sin(angles.x)*cos(angles.y),sin(angles.y),cos(angles.x)*cos(angles.y));
}
vec3 cmiDomeTurn(vec3 from,vec3 to,float dt,float response,float rate){
 float angle=acos(clamp(dot(from,to),-1.,1.));if(angle<.00001)return to;
 float step=min(angle*(1.-exp(-dt/max(response,.0001))),rate*dt);
 vec3 perpendicular=to-from*dot(from,to);
 if(length(perpendicular)<.00001)perpendicular=cross(from,abs(from.y)<.9?vec3(0,1,0):vec3(1,0,0));
 return normalize(from*cos(step)+normalize(perpendicular)*sin(step));
}
vec2 cmiDomePoseAngles(vec2 previous,float targetYaw,float targetPitch,float dt,float response){
 float yaw=previous.x,pitch=previous.y;
 vec2 delta=vec2(mod(targetYaw-yaw+3.141592654,6.28318530718)-3.141592654,targetPitch-pitch)*(response>0.?1.-exp(-dt/response):1.);
 // Reserve one encoded angular unit so rounding cannot exceed the slew cap.
 delta*=min(1.,max(0.,3.141592654*dt-.00011)/max(length(delta),.000001));yaw+=delta.x;pitch+=delta.y;
 return vec2(yaw,pitch);
}
vec2 cmiDomeAdvancePose(vec2 previous,vec3 navigation,float dt){
 float speed=length(navigation);if(speed<.05)return previous;
 float yaw=previous.x;
 float targetYaw=length(navigation.xz)>max(.05,speed*.1)?atan(navigation.x,navigation.z):yaw;
 float targetPitch=asin(clamp(navigation.y/speed,-1.,1.));
 return cmiDomePoseAngles(previous,targetYaw,targetPitch,dt,.12);
}
