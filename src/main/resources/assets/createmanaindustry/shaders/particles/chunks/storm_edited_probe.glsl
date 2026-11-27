bool cmiEditedProbe(vec3 start,vec3 end){
 if(!uStormTerrainEdited)return false;
 ivec3 lo=ivec3(floor(min(start,end)))>>4,hi=ivec3(floor(max(start,end)))>>4;
 for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++)
  if(cmiStormEditPage(ivec3(x,y,z))>=0)return true;
 return false;
}

bool cmiEditedProbeHit(vec3 origin,vec3 direction,float reach,out float distance,out vec3 normal){
 vec3 start=origin+direction*.0001;
 ivec3 cell=ivec3(floor(start)),step=ivec3(sign(direction));
 vec3 stride=1./max(abs(direction),vec3(.000001));
 vec3 edge=(vec3(cell)+vec3(greaterThan(step,ivec3(0)))-start)*vec3(step)*stride;
 edge=mix(edge,vec3(1e9),equal(step,ivec3(0)));
 for(int i=0;i<16;i++){
  int axis=edge.x<=edge.y&&edge.x<=edge.z?0:edge.y<=edge.z?1:2;
  distance=edge[axis]+.0001;
  if(distance>reach)break;
  cell[axis]+=step[axis];edge[axis]+=stride[axis];
  if(cmiStormSolidInPage(cell,cmiStormPage(cell>>4))){
   normal=vec3(0);normal[axis]=-float(step[axis]);return true;
  }
 }
 return false;
}
