#pragma cmi_include chunks/feather_record.glsl
#pragma cmi_include chunks/feather_state.glsl
void cmiFeatherDissolve(vec4 p0,vec4 p3){
    for(int i=0;i<12;i++){
        if(!cmiFeatherReserve(true))return;uint slot=cmiReserveParticle();if(slot>=uCapacity)return;
        float seed=p3.x*91.31+float(i)*7.31+float(cmiFeatherFrame(p3))*17.17;
        float angle=cmiFeatherHash(seed)*6.2831853,speed=mix(.2,.7,cmiFeatherHash(seed+1.));uint b=slot*4u;
        writeBuf.data[b]=vec4(p0.xyz, mix(.7,1.3,cmiFeatherHash(seed+2.)));
        writeBuf.data[b+1u]=vec4(cos(angle)*speed,mix(.5,1.6,cmiFeatherHash(seed+3.)),sin(angle)*speed,0);
        writeBuf.data[b+2u]=vec4(1);writeBuf.data[b+3u]=vec4(0,mix(.8,1.6,cmiFeatherHash(seed+4.)),seed,uintBitsToFloat(uFeatherSparkEmitter));
    }
}
bool cmiFeatherEarly(vec4 p0,vec4 p1,vec4 p2,vec4 p3){
    if(p3.x>=p3.y){cmiFeatherDissolve(p0,p3);return true;}
    cmiFeatherRequest(p0.xyz);cmiFeatherRequest(p0.xyz+p1.xyz*uDt+vec3(0,-1,0));
    if(p0.w>=0.)return false;
    uint slot=cmiReserveParticle();if(slot>=uCapacity)return true;uint b=slot*4u;
    writeBuf.data[b]=p0;writeBuf.data[b+1u]=p1;writeBuf.data[b+2u]=p2;writeBuf.data[b+3u]=vec4(p3.x+uDt,p3.yzw);return true;
}
