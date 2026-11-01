#include <cuda_runtime.h>
#include "Query.h"
__device__ unsigned hashCell(int x,int y,int z,unsigned mask){return ((unsigned)x*73856093u^(unsigned)y*19349663u^(unsigned)z*83492791u)&mask;}
__device__ int3 cell(float4 p,float r){return make_int3((int)floorf(p.x/r),(int)floorf(p.y/r),(int)floorf(p.z/r));}
extern "C" __global__ void build(Query p){unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.n)return;int3 c=cell(p.points[i],p.radius);p.next[i]=atomicExch(p.heads+hashCell(c.x,c.y,c.z,p.hashMask),(int)i);}
extern "C" __global__ void query(Query p){
    unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.q||p.write&&p.header[0]>p.capacity)return;
    float4 a=origin(p,i);int3 c=cell(a,p.radius);unsigned count=0;
    float pad=fmaxf(1e-5f,fmaxf(fmaxf(fabsf(a.x),fabsf(a.y)),fabsf(a.z))*0x1p-20f);
    int3 lo=cell(make_float4(a.x-p.radius-pad,a.y-p.radius-pad,a.z-p.radius-pad,0),p.radius),hi=cell(make_float4(a.x+p.radius+pad,a.y+p.radius+pad,a.z+p.radius+pad,0),p.radius);
    if((long long)(hi.x-lo.x+1)*(hi.y-lo.y+1)*(hi.z-lo.z+1)>128){for(unsigned j=0;j<p.n;j++){if(j==owner(p,i))continue;float4 b=p.points[j];float x=a.x-b.x,y=a.y-b.y,z=a.z-b.z;if((x*x+y*y)+z*z<=queryRadius(p,i)*queryRadius(p,i)){if(p.paged){if(!appendPage(p,i,count,j))return;}else if(p.write)p.ids[p.offsets[i]+count]=resultId(p,j);++count;}}if(!p.write)p.counts[i]=count;return;}
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++){
        int3 key=make_int3(x,y,z);
        for(int j=p.heads[hashCell(key.x,key.y,key.z,p.hashMask)];j>=0;j=p.next[j]){
            if((unsigned)j==owner(p,i))continue;float4 b=p.points[j];int3 cb=cell(b,p.radius);
            if(cb.x!=key.x||cb.y!=key.y||cb.z!=key.z)continue;
            float dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
            if((dx*dx+dy*dy)+dz*dz<=queryRadius(p,i)*queryRadius(p,i)){if(p.paged){if(!appendPage(p,i,count,j))return;}else if(p.write)p.ids[p.offsets[i]+count]=resultId(p,j);count++;}
        }
    }
    if(!p.write)p.counts[i]=count;
}
extern "C" __global__ void scan(Query p,unsigned long long* sums){
    __shared__ unsigned long long values[256];unsigned t=threadIdx.x,i=blockIdx.x*256+t;
    values[t]=i<p.q?p.counts[i]:0;__syncthreads();
    for(unsigned step=1;step<256;step*=2){unsigned long long v=t>=step?values[t-step]:0;__syncthreads();values[t]+=v;__syncthreads();}
    if(i<p.q)p.offsets[i]=values[t]-(unsigned long long)p.counts[i];if(t==255)sums[blockIdx.x]=values[255];
}
extern "C" __global__ void scanSums(Query p,unsigned long long* sums,unsigned blocks){
    if(threadIdx.x!=0||blockIdx.x!=0)return;unsigned long long total=0;
    for(unsigned i=0;i<blocks;i++){unsigned long long n=sums[i];sums[i]=total;total+=n;}
    p.offsets[p.q]=total;p.header[0]=total;atomicOr(p.header+1,total>p.capacity?1ull:0ull);
}
extern "C" __global__ void addSums(Query p,const unsigned long long* sums){unsigned i=blockIdx.x*256+threadIdx.x;if(i<p.q)p.offsets[i]+=sums[blockIdx.x];}
extern "C" __global__ void bounds(Query p,float* aabbs){unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.n)return;float4 v=p.points[i];float r=p.radius;float INFINITY=__int_as_float(0x7f800000);
    aabbs[i*6]=nextafterf(v.x-r,-INFINITY);aabbs[i*6+1]=nextafterf(v.y-r,-INFINITY);aabbs[i*6+2]=nextafterf(v.z-r,-INFINITY);
    aabbs[i*6+3]=nextafterf(v.x+r,INFINITY);aabbs[i*6+4]=nextafterf(v.y+r,INFINITY);aabbs[i*6+5]=nextafterf(v.z+r,INFINITY);
}
extern "C" __global__ void sealResult(Query p,unsigned* envelope,unsigned nonceLo,unsigned nonceHi,unsigned live,unsigned idsOffset,unsigned epochLo,unsigned epochHi,unsigned stage,unsigned lookupOffset){
    if(blockIdx.x||threadIdx.x)return;
    envelope[9]=0;
    envelope[0]=0x52375131u;envelope[1]=nonceLo;envelope[2]=nonceHi;envelope[3]=stage;
    envelope[4]=p.q;envelope[5]=live;envelope[6]=idsOffset;envelope[7]=p.capacity;
    envelope[8]=unsigned(p.header[1]);envelope[10]=epochLo;envelope[11]=epochHi;envelope[12]=lookupOffset;envelope[13]=lookupOffset?131072u:0u;envelope[14]=p.paged?*p.pageCounter:0u;envelope[15]=p.paged?p.pageCapacity:0u;
    __threadfence_system();envelope[9]=1u;
}
// Diagnostic equivalence only: normal native queries retain their traversal
// order. Sorting restores the scalar reference reduction order in physics tests.
extern "C" __global__ void sortRows(Query p){unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.q||p.header[1])return;unsigned long long from=p.offsets[i],to=p.offsets[i+1];for(auto k=from+1;k<to;k++){unsigned key=p.ids[k];auto at=k;while(at>from&&p.ids[at-1]>key){p.ids[at]=p.ids[at-1];--at;}p.ids[at]=key;}}

extern "C" __global__ void flattenPages(Query p){
 unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.q||p.header[1])return;
 unsigned page=p.pageHeads[i],count=p.counts[i],written=0;
 while(written<count){if(page>=p.pageCapacity){atomicOr(p.header+1,4ull);return;}unsigned length=min(32u,count-written);for(unsigned k=0;k<length;k++)p.ids[p.offsets[i]+written+k]=p.pages[page*36u+1u+k];written+=length;page=p.pages[page*36u];}
}
