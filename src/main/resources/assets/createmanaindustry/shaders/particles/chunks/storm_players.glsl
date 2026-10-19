// Dynamic 6m player-cell hash, appended after player positions at binding 18.
bool cmiNearPlayer(vec3 point,float radiusSquared){
 if(uPlayerCount<=0)return false;
 if(uPlayerCount<=16){for(int i=0;i<uPlayerCount;i++){vec3 delta=point-players.pl[i].xyz;if(dot(delta,delta)<radiusSquared)return true;}return false;}ivec3 cell=ivec3(floor(point/6.));int table=int(players.pl[uPlayerCount].x),mask=table-1;
 for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
  ivec3 key=cell+ivec3(dx,dy,dz);uint hash=uint(key.x)*73856093u^uint(key.y)*19349663u^uint(key.z)*83492791u;int h=int(hash&uint(mask));
  for(int probe=0;probe<table;probe++){vec4 entry=players.pl[uPlayerCount+1+h];int head=int(entry.w);if(head==0)break;
   if(all(equal(floatBitsToInt(entry.xyz),key))){for(int visits=0;head>0&&visits<uPlayerCount;visits++){vec4 player=players.pl[head-1];vec3 delta=point-player.xyz;if(dot(delta,delta)<radiusSquared)return true;head=int(player.w);}break;}h=(h+1)&mask;
  }
 }return false;
}

vec3 cmiPlayerRepel(vec3 point,float radius,float weight){
 vec3 force=vec3(0);if(uPlayerCount<=16){for(int i=0;i<uPlayerCount;i++){vec3 d=point-players.pl[i].xyz;float q=dot(d,d);if(q>1e-6&&q<radius*radius){float l=sqrt(q);force+=d*(weight*(radius-l)/(radius*l));}}return force;}
 ivec3 cell=ivec3(floor(point/6.));int table=int(players.pl[uPlayerCount].x),mask=table-1;
 for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
  ivec3 key=cell+ivec3(dx,dy,dz);int h=int((uint(key.x)*73856093u^uint(key.y)*19349663u^uint(key.z)*83492791u)&uint(mask));
  for(int probe=0;probe<table;probe++){vec4 entry=players.pl[uPlayerCount+1+h];int head=int(entry.w);if(head==0)break;
   if(all(equal(floatBitsToInt(entry.xyz),key))){for(int visits=0;head>0&&visits<uPlayerCount;visits++){vec4 player=players.pl[head-1];vec3 d=point-player.xyz;float q=dot(d,d);if(q>1e-6&&q<radius*radius){float l=sqrt(q);force+=d*(weight*(radius-l)/(radius*l));}head=int(player.w);}break;}h=(h+1)&mask;
  }
 }return force;
}
