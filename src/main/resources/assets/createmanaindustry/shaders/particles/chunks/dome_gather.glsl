// Gather-only body queries. Unknown sections never latch a flow pause.
bool cmiDomeNavigationBlocked=false,cmiDomeNavigationUnknown=false,cmiDomeProbeUnknown=false;
int cmiDomeBodyTerrain(vec3 feet){
 ivec3 lo=ivec3(floor(feet+vec3(-.175,.001,-.175))),hi=ivec3(floor(feet+vec3(.175,.599,.175)));
 // Whole-air/solid section markers are common; avoid eight voxel reads.
 if(all(equal(lo>>4,hi>>4))){int page=cmiStormPage(lo>>4);if(page==-2)return 0;if(page==-3)return 1;if(page==-1)return -1;}
 bool unknown=false,occupied=false;
 for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
  vec3 cell=vec3(x,y,z)+.5;if(!cmiStormKnown(cell)){unknown=true;continue;}occupied=occupied||cmiStormSolid(cell);
 }
 return occupied?1:unknown?-1:0;
}
float cmiDomeCorridor(vec3 pos,vec3 direction,float reach){
 cmiDomeProbeUnknown=false;float clear=0.;for(int k=1;k<=8;k++){float d=min(float(k)*.25,reach);int terrain=cmiDomeBodyTerrain(pos+direction*d);if(terrain!=0){cmiDomeProbeUnknown=terrain<0;break;}clear=d;if(d>=reach)break;}return clear;
}
vec3 cmiDomeGatherAvoid(vec3 pos,vec3 direction,float reach){
 float forward=cmiDomeCorridor(pos,direction,reach);bool unknown=cmiDomeProbeUnknown;if(forward>=reach)return direction;
 vec3 side=cross(direction,vec3(0,1,0));if(length(side)<.001)side=vec3(1,0,0);else side=normalize(side);
 vec3 best=direction;float bestClear=forward,bestScore=forward+.3;
 // A short, deterministic fan; upward tangent first on slopes. No global search.
 for(int k=0;k<6;k++){
  vec3 candidate=k==0?normalize(direction+vec3(0,1.5,0)):k==1?vec3(0,1,0):k==2?normalize(direction+side*1.5):k==3?normalize(direction-side*1.5):k==4?side:-side;
  float depth=cmiDomeCorridor(pos,candidate,reach),score=depth+.3*dot(candidate,direction);
  unknown=unknown||cmiDomeProbeUnknown;
  if(score>bestScore){bestScore=score;bestClear=depth;best=candidate;}
 }
 cmiDomeNavigationUnknown=bestClear<.25&&unknown;
 cmiDomeNavigationBlocked=bestClear<.25&&!cmiDomeNavigationUnknown;
 return bestClear<.25?vec3(0):best;
}
