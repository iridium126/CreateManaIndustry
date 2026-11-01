#include <cuda_runtime.h>
#include "Query.h"
__device__ unsigned hashCell(int x,int y,int z,unsigned mask){return ((unsigned)x*73856093u^(unsigned)y*19349663u^(unsigned)z*83492791u)&mask;}
__device__ int3 cell(float4 p,float r){return make_int3((int)floorf(p.x/r),(int)floorf(p.y/r),(int)floorf(p.z/r));}
extern "C" __global__ void build(Query p){unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.n)return;int3 c=cell(p.points[i],p.radius);p.next[i]=atomicExch(p.heads+hashCell(c.x,c.y,c.z,p.hashMask),(int)i);}
extern "C" __global__ void query(Query p){
    unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.q||p.write&&p.header[0]>p.capacity)return;
    float4 a=p.points[i];int3 c=cell(a,p.radius);unsigned count=0;
    for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        int3 key=make_int3(c.x+x,c.y+y,c.z+z);
        for(int j=p.heads[hashCell(key.x,key.y,key.z,p.hashMask)];j>=0;j=p.next[j]){
            if((unsigned)j==i)continue;float4 b=p.points[j];int3 cb=cell(b,p.radius);
            if(cb.x!=key.x||cb.y!=key.y||cb.z!=key.z)continue;
            float dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
            if((dx*dx+dy*dy)+dz*dz<=p.radius*p.radius){if(p.write)p.ids[p.offsets[i]+count]=j;count++;}
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
    p.offsets[p.q]=total;p.header[0]=total;p.header[1]=total>p.capacity?1:0;
}
extern "C" __global__ void addSums(Query p,const unsigned long long* sums){unsigned i=blockIdx.x*256+threadIdx.x;if(i<p.q)p.offsets[i]+=sums[blockIdx.x];}
extern "C" __global__ void bounds(Query p,float* aabbs){unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.n)return;float4 v=p.points[i];float r=p.radius;float INFINITY=__int_as_float(0x7f800000);
    aabbs[i*6]=nextafterf(v.x-r,-INFINITY);aabbs[i*6+1]=nextafterf(v.y-r,-INFINITY);aabbs[i*6+2]=nextafterf(v.z-r,-INFINITY);
    aabbs[i*6+3]=nextafterf(v.x+r,INFINITY);aabbs[i*6+4]=nextafterf(v.y+r,INFINITY);aabbs[i*6+5]=nextafterf(v.z+r,INFINITY);
}
