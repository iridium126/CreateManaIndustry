// Isolated R6 variants only. The production shader never includes this file.
uniform uint uR6Base,uR6Capacity,uR6Buckets,uR6Stage;
uniform uvec2 uR6Generation;
uniform bool uR6Packed;
const float R6_CELL=R6_CELL_LITERAL;
uint r6Word(uint at){return uint(grid.next[uR6Base+at-uint(GRID_TABLE)]);}
uint r6Hash(ivec3 c,uint mask){return (uint(c.x)*92837111u^uint(c.y)*689287499u^uint(c.z)*283923481u)&mask;}
float r6Pad(vec3 p){return max(.00001,max(max(abs(p.x),abs(p.y)),abs(p.z))*0.000000476837158203125);}
bool r6Touch(vec3 p,ivec3 cell,float size,float radius){vec3 delta=p-clamp(p,vec3(cell)*size,vec3(cell+1)*size);float reach=radius+r6Pad(p);return dot(delta,delta)<=reach*reach;}
bool r6Valid(){return uR6Packed&&r6Word(0u)==1u&&r6Word(1u)==uR6Generation.x&&r6Word(2u)==uR6Generation.y&&r6Word(3u)==uR6Stage&&r6Word(4u)<=uR6Capacity&&r6Word(5u)==uR6Buckets;}
uint r6Counts(){return 16u;}uint r6Offsets(){return 16u+uR6Buckets;}
uint r6Cursors(){return 17u+2u*uR6Buckets;}uint r6Sums(){return 17u+3u*uR6Buckets;}
uint r6Entries(){return (r6Sums()+(uR6Buckets+255u)/256u+63u)&~63u;}
// Canonical bucket selection avoids duplicates even when apply has changed pool positions.
// Invert the odd Z multiplier modulo 2^14: only an XY search is necessary.
bool r6FirstBucket(ivec3 c,ivec3 lo,ivec3 hi,vec3 p,float radius,bool prune){
 uint hash=r6Hash(c,uint(GRID_TABLE)-1u);
 for(int x=lo.x;x<=c.x;x++)for(int y=lo.y;y<=(x==c.x?c.y:hi.y);y++){
  uint residue=((hash^uint(x)*92837111u^uint(y)*689287499u)*2089u)&(uint(GRID_TABLE)-1u);
  int z=lo.z+int((residue-uint(lo.z))&(uint(GRID_TABLE)-1u));
  if(z>hi.z||(x==c.x&&y==c.y&&z>=c.z))continue;
  if(!prune||r6Touch(p,ivec3(x,y,z),2.5,radius))return false;
 }return true;
}
#ifdef R6_UPDATE
vec3 cmiStormSeparationDelta(vec3 delta,uint mine,uint other);
vec3 r6Separation(vec3 pos,uint self,float radius,uint member){
 bool packedEnabled=r6Valid();float size=packedEnabled?R6_CELL:2.5;float pad=r6Pad(pos);
 ivec3 lo=ivec3(floor((pos-vec3(radius+pad))/size)),hi=ivec3(floor((pos+vec3(radius+pad))/size));
 vec3 sum=vec3(0);
 for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
  ivec3 c=ivec3(x,y,z);if(R6_PRUNE&&!r6Touch(pos,c,size,radius))continue;
  if(!packedEnabled&&!r6FirstBucket(c,lo,hi,pos,radius,R6_PRUNE))continue;
  uint hash=r6Hash(c,packedEnabled?uR6Buckets-1u:uint(GRID_TABLE)-1u);
  uint cursor=packedEnabled?r6Word(r6Offsets()+hash):0u,end=packedEnabled?r6Word(r6Offsets()+hash+1u):0u;
  int node=packedEnabled?0:grid.heads[hash];
  while(packedEnabled?cursor<end:node!=0){
   uint j;vec3 other;uint otherMember;
   if(packedEnabled){uint at=r6Entries()+cursor++*8u;ivec3 key=ivec3(r6Word(at+4u),r6Word(at+5u),r6Word(at+6u));if(any(notEqual(key,c)))continue;
    j=r6Word(at+7u);other=uintBitsToFloat(uvec3(r6Word(at),r6Word(at+1u),r6Word(at+2u)));otherMember=r6Word(at+3u);
   }else{j=uint(node-1);node=grid.next[j];other=readBuf.data[j*4u].xyz;otherMember=(uint(readBuf.data[j*4u].w+.5)&262143u)-1u;}
   if(j==self)continue;vec3 delta=pos-other;float d2=dot(delta,delta);if(d2>=radius*radius)continue;
   #ifdef R6_QUERY_TRACE
   r6Admit(member,otherMember);
   #endif
   if(d2<=1e-8){delta=cmiStormSeparationDelta(delta,member,otherMember);d2=dot(delta,delta);}
   if(d2<radius*radius&&d2>1e-8){float dl=sqrt(d2);sum+=delta*((radius-dl)/(dl*radius));}
  }
 }return sum;
}
#endif
