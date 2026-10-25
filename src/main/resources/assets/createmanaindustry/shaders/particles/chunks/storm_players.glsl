// One table: eyes.xyz / next, feet/centre offsets + stable entity ID, and attack eyes.
const float CMI_ALLAY_LOOK_RANGE=6.,CMI_ALLAY_LOOK_RANGE_SQ=36.;
vec4 cmiPlayerMeta(int i){int base=int(players.pl[uPlayerCount].y);return base>0?players.pl[base+i]:vec4(0,0,intBitsToFloat(i+1),0);}
vec3 cmiPlayerEyes(int i){return players.pl[i].xyz;}
vec3 cmiPlayerFeet(int i){return cmiPlayerEyes(i)+vec3(0,cmiPlayerMeta(i).x,0);}
vec3 cmiPlayerCenter(int i){return cmiPlayerEyes(i)+vec3(0,cmiPlayerMeta(i).y,0);}
int cmiPlayerId(int i){return floatBitsToInt(cmiPlayerMeta(i).z);}
vec4 cmiAttackEyes(uint slot){int base=int(players.pl[uPlayerCount].z);return base>0&&slot<68u?players.pl[base+int(slot)]:vec4(0);}
int cmiPlayerCellHead(ivec3 key){
 int table=int(players.pl[uPlayerCount].x),mask=table-1;
 int h=int((uint(key.x)*73856093u^uint(key.y)*19349663u^uint(key.z)*83492791u)&uint(mask));
 for(int probe=0;probe<table;probe++){vec4 entry=players.pl[uPlayerCount+1+h];int head=int(entry.w);if(head==0)return 0;if(all(equal(floatBitsToInt(entry.xyz),key)))return head;h=(h+1)&mask;}return 0;
}
// Feet distance, inclusive vanilla look boundary. Stable ID breaks equal-distance ties.
int cmiNearestPlayer(vec3 point){
 if(uPlayerCount<=0)return -1;int best=-1;float closest=CMI_ALLAY_LOOK_RANGE_SQ;
 if(uPlayerCount<=16){for(int i=0;i<uPlayerCount;i++){vec3 d=point-cmiPlayerFeet(i);float q=dot(d,d);if(q<=closest&&(best<0||q<closest||cmiPlayerId(i)<cmiPlayerId(best))){closest=q;best=i;}}return best;}
 ivec3 cell=ivec3(floor(point/6.));
 for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
  int head=cmiPlayerCellHead(cell+ivec3(x,y,z));for(int visits=0;head>0&&visits<uPlayerCount;visits++){int i=head-1;vec3 d=point-cmiPlayerFeet(i);float q=dot(d,d);if(q<=closest&&(best<0||q<closest||cmiPlayerId(i)<cmiPlayerId(best))){closest=q;best=i;}head=int(players.pl[i].w);}
 }return best;
}
bool cmiNearPlayerFeet(vec3 point){return cmiNearestPlayer(point)>=0;}
int cmiPlayerWithId(int id){if(id==0)return -1;for(int i=0;i<uPlayerCount;i++)if(cmiPlayerId(i)==id)return i;return -1;}
// Existing feedback/repulsion consumers retain their centre-distance semantics.
bool cmiNearPlayer(vec3 point,float radiusSquared){
 if(uPlayerCount<=0)return false;
 if(uPlayerCount<=16){for(int i=0;i<uPlayerCount;i++){vec3 d=point-cmiPlayerCenter(i);if(dot(d,d)<radiusSquared)return true;}return false;}
 ivec3 cell=ivec3(floor(point/6.));int horizontal=int(ceil(sqrt(radiusSquared)/6.)),vertical=int(ceil((sqrt(radiusSquared)+players.pl[uPlayerCount].w)/6.));
 for(int x=-horizontal;x<=horizontal;x++)for(int y=-vertical;y<=vertical;y++)for(int z=-horizontal;z<=horizontal;z++){
  int head=cmiPlayerCellHead(cell+ivec3(x,y,z));for(int visits=0;head>0&&visits<uPlayerCount;visits++){int i=head-1;vec3 d=point-cmiPlayerCenter(i);if(dot(d,d)<radiusSquared)return true;head=int(players.pl[i].w);}
 }return false;
}
vec3 cmiPlayerRepel(vec3 point,float radius,float weight){
 vec3 force=vec3(0);if(uPlayerCount<=0)return force;
 if(uPlayerCount<=16){for(int i=0;i<uPlayerCount;i++){vec3 d=point-cmiPlayerCenter(i);float q=dot(d,d);if(q>1e-6&&q<radius*radius){float l=sqrt(q);force+=d*(weight*(radius-l)/(radius*l));}}return force;}
 ivec3 cell=ivec3(floor(point/6.));int horizontal=int(ceil(radius/6.)),vertical=int(ceil((radius+players.pl[uPlayerCount].w)/6.));
 for(int x=-horizontal;x<=horizontal;x++)for(int y=-vertical;y<=vertical;y++)for(int z=-horizontal;z<=horizontal;z++){
  int head=cmiPlayerCellHead(cell+ivec3(x,y,z));for(int visits=0;head>0&&visits<uPlayerCount;visits++){int i=head-1;vec3 d=point-cmiPlayerCenter(i);float q=dot(d,d);if(q>1e-6&&q<radius*radius){float l=sqrt(q);force+=d*(weight*(radius-l)/(radius*l));}head=int(players.pl[i].w);}
 }return force;
}
