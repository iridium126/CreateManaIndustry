#ifndef CMI_FEATHER_STATE
#define CMI_FEATHER_STATE
// Binding 20 is the feedback slot outside storm-position capture. Restored per pass.
#ifdef CMI_FEATHER_GRID
uniform uint uFeatherGridBase;
#define CMI_FEATHER_WORD(i) grid.next[uFeatherGridBase+uint(i)]
#else
layout(std430,binding=20) buffer FeatherState {int words[];} feather;
#define CMI_FEATHER_WORD(i) feather.words[i]
#endif
uniform bool uFeatherEnabled;
uniform bool uFeatherRequestFrame;
uint cmiFeatherCellHash(ivec3 key){uint h=uint(key.x)*0x8da6b343u^uint(key.y)*0xd8163841u^uint(key.z)*0xcb1ab31fu;return h^(h>>16u);}
bool cmiFeatherReserve(){
    if(!uFeatherEnabled)return false;
    int prior=CMI_FEATHER_WORD(4),slot=atomicAdd(CMI_FEATHER_WORD(6),1);
    return prior+slot<int(CMI_FEATHER_MAX);
}
bool cmiGoldReserve(){
    if(!uFeatherEnabled)return false;
    int prior=CMI_FEATHER_WORD(5),slot=atomicAdd(CMI_FEATHER_WORD(7),1);
    return prior+slot<int(CMI_GOLD_MAX);
}
void cmiFeatherRequest(vec3 p){
    if(!uFeatherEnabled||!uFeatherRequestFrame)return;ivec3 key=ivec3(floor(p/16.));uint hash=cmiFeatherCellHash(key);
    for(uint probe=0u;probe<8u;probe++){
        uint row=CMI_FEATHER_REQUEST_HASH+((hash+probe)&8191u)*4u;
        int state=atomicCompSwap(CMI_FEATHER_WORD(row),0,-1);
        if(state==-1)return; // Retry a contended request next batch; never insert a duplicate behind it.
        if(state==0){
            int n=atomicAdd(CMI_FEATHER_WORD(22),1);
            if(n>=4096){atomicAdd(CMI_FEATHER_WORD(23),1);atomicExchange(CMI_FEATHER_WORD(row),0);return;}
            CMI_FEATHER_WORD(row+1u)=key.x;CMI_FEATHER_WORD(row+2u)=key.y;CMI_FEATHER_WORD(row+3u)=key.z;
            uint outRow=CMI_FEATHER_REQUESTS+uint(n)*4u;
            CMI_FEATHER_WORD(outRow)=key.x;CMI_FEATHER_WORD(outRow+1u)=key.y;CMI_FEATHER_WORD(outRow+2u)=key.z;CMI_FEATHER_WORD(outRow+3u)=1;
            memoryBarrierBuffer();atomicExchange(CMI_FEATHER_WORD(row),1);return;
        }
        if(state==1&&all(equal(ivec3(CMI_FEATHER_WORD(row+1u),CMI_FEATHER_WORD(row+2u),CMI_FEATHER_WORD(row+3u)),key)))return;
    }
}
void cmiFeatherDisturb(vec3 position,float strength){
    if(!uFeatherEnabled||strength<=0.)return;
    ivec3 key=ivec3(floor(position/2.));uint hash=cmiFeatherCellHash(key);
    for(uint probe=0u;probe<8u;probe++){
        uint row=CMI_FEATHER_IMPULSES+((hash+probe)&16383u)*8u;
        int state=atomicCompSwap(CMI_FEATHER_WORD(row),0,1);bool fresh=state==0;
        if(!fresh){
            if(state!=2||any(notEqual(ivec3(CMI_FEATHER_WORD(row+1u),CMI_FEATHER_WORD(row+2u),CMI_FEATHER_WORD(row+3u)),key)))continue;
            if(atomicCompSwap(CMI_FEATHER_WORD(row),2,1)!=2)continue;
        }
        if(fresh||strength>intBitsToFloat(CMI_FEATHER_WORD(row+7u))){
            CMI_FEATHER_WORD(row+1u)=key.x;CMI_FEATHER_WORD(row+2u)=key.y;CMI_FEATHER_WORD(row+3u)=key.z;
            CMI_FEATHER_WORD(row+4u)=floatBitsToInt(position.x);CMI_FEATHER_WORD(row+5u)=floatBitsToInt(position.y);CMI_FEATHER_WORD(row+6u)=floatBitsToInt(position.z);
            CMI_FEATHER_WORD(row+7u)=floatBitsToInt(min(strength,3.5));
        }
        memoryBarrierBuffer();atomicExchange(CMI_FEATHER_WORD(row),2);
        if(fresh)atomicAdd(CMI_FEATHER_WORD(15),1);
        atomicMin(CMI_FEATHER_WORD(16),key.x);atomicMin(CMI_FEATHER_WORD(17),key.y);atomicMin(CMI_FEATHER_WORD(18),key.z);
        atomicMax(CMI_FEATHER_WORD(19),key.x);atomicMax(CMI_FEATHER_WORD(20),key.y);atomicMax(CMI_FEATHER_WORD(21),key.z);return;
    }
}
vec3 cmiFeatherLift(vec3 position){
    if(CMI_FEATHER_WORD(15)==0)return vec3(0);ivec3 key=ivec3(floor(position/2.));
    if(any(lessThan(key,ivec3(CMI_FEATHER_WORD(16),CMI_FEATHER_WORD(17),CMI_FEATHER_WORD(18))-1))||any(greaterThan(key,ivec3(CMI_FEATHER_WORD(19),CMI_FEATHER_WORD(20),CMI_FEATHER_WORD(21))+1)))return vec3(0);
    vec3 lift=vec3(0);
    for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
        ivec3 cell=key+ivec3(x,y,z);uint hash=cmiFeatherCellHash(cell);
        for(uint probe=0u;probe<8u;probe++){
            uint row=CMI_FEATHER_IMPULSES+((hash+probe)&16383u)*8u;int state=CMI_FEATHER_WORD(row);if(state==0)break;
            if(state!=2||any(notEqual(ivec3(CMI_FEATHER_WORD(row+1u),CMI_FEATHER_WORD(row+2u),CMI_FEATHER_WORD(row+3u)),cell)))continue;
            vec3 d=position-vec3(intBitsToFloat(CMI_FEATHER_WORD(row+4u)),intBitsToFloat(CMI_FEATHER_WORD(row+5u)),intBitsToFloat(CMI_FEATHER_WORD(row+6u)));float q=dot(d,d);
            if(q<4.){float force=intBitsToFloat(CMI_FEATHER_WORD(row+7u))*(1.-q/4.);lift+=vec3(d.x*.65,1.,d.z*.65)*force;}break;
        }
    }
    return lift.y>3.5?lift*(3.5/lift.y):lift;
}
#endif
