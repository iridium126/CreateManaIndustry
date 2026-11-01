#include <cuda_runtime.h>
#include <mma.h>
#include <math_constants.h>
#include <optix_types.h>
#include "Query.h"
using namespace nvcuda;
__device__ unsigned bucket(int x,int y,int z,unsigned mask){return ((unsigned)x*73856093u^(unsigned)y*19349663u^(unsigned)z*83492791u)&mask;}
extern "C" __global__ void instances(Query p,OptixInstance* out,unsigned long long unit){
    unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.n)return;
    float4 v=p.points[i];float pad=fmaxf(1.e-4f,fmaxf(fmaxf(fabsf(v.x),fabsf(v.y)),fabsf(v.z))*0x1p-20f+p.radius*0x1p-20f),r=p.radius+pad;
    OptixInstance d{};d.transform[0]=d.transform[5]=d.transform[10]=r;d.transform[3]=v.x;d.transform[7]=v.y;d.transform[11]=v.z;d.instanceId=i;d.visibilityMask=255;d.traversableHandle=unit;out[i]=d;
}
template<bool Tensor> __device__ void tiled(Query p){
    const unsigned lane=threadIdx.x,base=blockIdx.x*16u;
    if(base>=p.q||(p.write&&p.header[0]>p.capacity))return;
    // One warp owns each query tile. Each hash bucket appears once even when
    // neighbouring cells collide or the sixteen query members are far apart.
    __shared__ unsigned hashes[4096],exhaustive;
    __shared__ unsigned members[16],used;
    __shared__ float4 queries[16],points[16];
    __shared__ __align__(32) signed char a[256],b[256];
    __shared__ __align__(32) int c[256],normA[16],normB[16];
    __shared__ float threshold;
    for(unsigned k=lane;k<4096;k+=32)hashes[k]=0;if(lane==0)exhaustive=0;
    if(lane<16)queries[lane]=base+lane<p.q?origin(p,base+lane):make_float4(0,0,0,0);__syncthreads();
    int3 lo{},hi{};
    if(lane<16&&base+lane<p.q){float4 q=queries[lane];float pad=fmaxf(1e-5f,fmaxf(fmaxf(fabsf(q.x),fabsf(q.y)),fabsf(q.z))*0x1p-20f);lo=make_int3((int)floorf((q.x-p.radius-pad)/p.radius),(int)floorf((q.y-p.radius-pad)/p.radius),(int)floorf((q.z-p.radius-pad)/p.radius));hi=make_int3((int)floorf((q.x+p.radius+pad)/p.radius),(int)floorf((q.y+p.radius+pad)/p.radius),(int)floorf((q.z+p.radius+pad)/p.radius));if((long long)(hi.x-lo.x+1)*(hi.y-lo.y+1)*(hi.z-lo.z+1)>128)atomicExch(&exhaustive,1u);}
    __syncthreads();
    if(exhaustive){if(lane<16&&base+lane<p.q){float4 q=queries[lane];unsigned count=0;for(unsigned j=0;j<p.n;j++){if(j==owner(p,base+lane))continue;float4 b=p.points[j];float x=q.x-b.x,y=q.y-b.y,z=q.z-b.z;if((x*x+y*y)+z*z<=queryRadius(p,base+lane)*queryRadius(p,base+lane)){if(p.write)p.ids[p.offsets[base+lane]+count]=resultId(p,j);++count;}}if(!p.write)p.counts[base+lane]=count;}return;}
    if(lane<16&&base+lane<p.q)for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++){unsigned key=bucket(x,y,z,p.hashMask)+1u,at=(key*2654435761u)&4095u;for(;;){unsigned old=atomicCAS(hashes+at,0u,key);if(old==0u||old==key)break;at=(at+1u)&4095u;}}
    __syncthreads();unsigned count=0;
    for(unsigned h=0;h<4096;h++){
        if(hashes[h]==0u)continue;
        int node=p.heads[hashes[h]-1u];
        while(node>=0){
            if(lane==0){used=0;for(;node>=0&&used<16;node=p.next[node])members[used++]=(unsigned)node;for(unsigned k=used;k<16;k++)members[k]=0;}
            // node is private per lane: only the owner advances the chain.
            node=__shfl_sync(0xffffffffu,node,0);__syncthreads();
            if(lane<16)points[lane]=p.points[members[lane]];__syncthreads();
            if(lane==0){
                float3 lo=make_float3(queries[0].x,queries[0].y,queries[0].z),hi=lo;
                for(unsigned k=0;k<16;k++){if(base+k<p.q){float4 v=queries[k];lo.x=fminf(lo.x,v.x);lo.y=fminf(lo.y,v.y);lo.z=fminf(lo.z,v.z);hi.x=fmaxf(hi.x,v.x);hi.y=fmaxf(hi.y,v.y);hi.z=fmaxf(hi.z,v.z);}if(k<used){float4 v=points[k];lo.x=fminf(lo.x,v.x);lo.y=fminf(lo.y,v.y);lo.z=fminf(lo.z,v.z);hi.x=fmaxf(hi.x,v.x);hi.y=fmaxf(hi.y,v.y);hi.z=fmaxf(hi.z,v.z);}}
                float3 origin=make_float3(lo.x+(hi.x-lo.x)*.5f,lo.y+(hi.y-lo.y)*.5f,lo.z+(hi.z-lo.z)*.5f);
                float extent=fmaxf(fmaxf(hi.x-lo.x,hi.y-lo.y),hi.z-lo.z)*.5f,scale=extent>0?126.f/extent:1.f;
                float world=fmaxf(fmaxf(fmaxf(fabsf(lo.x),fabsf(hi.x)),fmaxf(fabsf(lo.y),fabsf(hi.y))),fmaxf(fabsf(lo.z),fabsf(hi.z)));
                // Conservative packing/rounding bound. This gate may add pairs;
                // the final original FP32 predicate is evaluated below.
                float error=0.5f/scale+16.f*0x1p-23f*(world+extent+p.radius+1.f);
                float reach=nextafterf((p.radius+3.464102f*error)*scale,CUDART_INF_F);threshold=nextafterf(reach*reach,CUDART_INF_F);
                for(unsigned k=0;k<256;k++)a[k]=b[k]=0;
                for(unsigned k=0;k<16;k++){float4 x=queries[k],y=points[k];int qa[3]={__float2int_rn((x.x-origin.x)*scale),__float2int_rn((x.y-origin.y)*scale),__float2int_rn((x.z-origin.z)*scale)},qb[3]={__float2int_rn((y.x-origin.x)*scale),__float2int_rn((y.y-origin.y)*scale),__float2int_rn((y.z-origin.z)*scale)};normA[k]=normB[k]=0;for(unsigned dim=0;dim<3;dim++){if(abs(qa[dim])>127||abs(qb[dim])>127)threshold=CUDART_INF_F;qa[dim]=max(-127,min(127,qa[dim]));qb[dim]=max(-127,min(127,qb[dim]));a[k*16+dim]=(signed char)qa[dim];b[k*16+dim]=(signed char)qb[dim];normA[k]+=qa[dim]*qa[dim];normB[k]+=qb[dim]*qb[dim];}}
            }
            __syncthreads();
            if constexpr(Tensor){wmma::fragment<wmma::matrix_a,16,16,16,signed char,wmma::row_major> aa;wmma::fragment<wmma::matrix_b,16,16,16,signed char,wmma::col_major> bb;wmma::fragment<wmma::accumulator,16,16,16,int> cc;wmma::load_matrix_sync(aa,a,16);wmma::load_matrix_sync(bb,b,16);wmma::fill_fragment(cc,0);wmma::mma_sync(cc,aa,bb,cc);wmma::store_matrix_sync(c,cc,16,wmma::mem_row_major);}
            else{for(unsigned pair=lane;pair<256;pair+=32){unsigned row=pair/16,col=pair%16;int sum=0;for(unsigned k=0;k<3;k++)sum+=int(a[row*16+k])*int(b[col*16+k]);c[pair]=sum;}}
            __syncthreads();
            if(lane<16&&base+lane<p.q){float4 q=queries[lane];for(unsigned k=0;k<used;k++){unsigned j=members[k];if(j==owner(p,base+lane))continue;int squared=normA[lane]+normB[k]-2*c[lane*16+k];if(float(squared)>threshold)continue;float4 v=points[k];float dx=q.x-v.x,dy=q.y-v.y,dz=q.z-v.z;if((dx*dx+dy*dy)+dz*dz<=queryRadius(p,base+lane)*queryRadius(p,base+lane)){if(p.write)p.ids[p.offsets[base+lane]+count]=resultId(p,j);++count;}}}
            __syncthreads();
        }
    }
    if(lane<16&&base+lane<p.q&&!p.write)p.counts[base+lane]=count;
}
extern "C" __global__ void tensorQuery(Query p){tiled<true>(p);}
extern "C" __global__ void scalarTileQuery(Query p){tiled<false>(p);}
