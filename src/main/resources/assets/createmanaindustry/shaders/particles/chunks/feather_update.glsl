#pragma cmi_include chunks/feather_record.glsl
#pragma cmi_include chunks/feather_state.glsl
bool cmiFeatherEarly(vec4 p0,vec4 p1,vec4 p2,vec4 p3){
    if(p3.x>=p3.y){return true;}
    cmiFeatherRequest(p0.xyz);cmiFeatherRequest(p0.xyz+p1.xyz*uDt+vec3(0,-1,0));
    if(p0.w>=0.)return false;
    uint slot=cmiReserveParticle();if(slot>=uCapacity)return true;uint b=slot*4u;
    writeBuf.data[b]=p0;writeBuf.data[b+1u]=p1;writeBuf.data[b+2u]=p2;writeBuf.data[b+3u]=vec4(p3.x+uDt,p3.yzw);return true;
}
