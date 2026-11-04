// A single convex opaque occluder must cover the entire model bound. Keeping
// unknowns visible includes camera/target overlap and insufficient FP32 margin.
bool cmiWholeBoundOccluded(vec3 eye,vec3 center,float radius,vec3 middle,vec3 halfSize){
 float magnitude=max(max(max(abs(eye.x),abs(eye.y)),abs(eye.z)),max(max(abs(center.x),abs(center.y)),abs(center.z)));
 float margin=max(.001,magnitude*exp2(-20.));radius+=margin;halfSize-=vec3(margin);
 if(radius<=0.||any(lessThanEqual(halfSize,vec3(0))))return false;
 if(all(lessThanEqual(abs(eye-middle),halfSize+margin)))return false;
 if(all(lessThanEqual(abs(center-middle),halfSize+radius)))return false;
 for(uint corner=0u;corner<8u;corner++){
  vec3 target=center+vec3((corner&1u)!=0u?radius:-radius,(corner&2u)!=0u?radius:-radius,(corner&4u)!=0u?radius:-radius);
  vec3 direction=target-eye;float enter=0.,leave=1.;
  for(int k=0;k<3;k++){
   if(abs(direction[k])<1e-12){if(eye[k]<middle[k]-halfSize[k]||eye[k]>middle[k]+halfSize[k])return false;continue;}
   float a=(middle[k]-halfSize[k]-eye[k])/direction[k],b=(middle[k]+halfSize[k]-eye[k])/direction[k];
   enter=max(enter,min(a,b));leave=min(leave,max(a,b));
  }
  if(enter>=leave||enter<=0.||leave>=1.)return false;
 }
 return true;
}
