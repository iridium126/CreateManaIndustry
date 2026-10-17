// Native ConjureParticle advances at logical ticks; ordinary effects keep their own continuous clock.
uniform float uHexDt=-1.;
#pragma cmi_include chunks/hex_tick.glsl
bool updateHexParticle(uint emitterBase,vec4 p0,vec4 p1,vec4 p2,vec4 p3){
    float style=emitters.u[emitterBase+17u].y;
    if(style<3.5||style>5.5)return false;
    float dt=uHexDt<0.?uDt:uHexDt;
    float age=p3.x+max(0.,dt);
    int before=int(floor(p3.x*20.+.0001)),after=int(floor(age*20.+.0001));
    int ticks=after-before;
    if(uHexTickReady){ticks=int(uHexCurrentTick-floatBitsToUint(p3.z));after=before+max(0,ticks);age=float(after)*.05;}
    int lifetime=int(round(p3.y*20.));
    if(after>lifetime)return true;
    vec3 position=p0.xyz,velocity=p1.xyz;
    advanceHexTicks(position,velocity,p2.w<0.,max(0,ticks));
    uint slot=atomicAdd(counter.writeSlot,1u);
    if(slot<uCapacity){
        uint base=slot*4u;
        writeBuf.data[base]=vec4(position,p0.w);writeBuf.data[base+1u]=vec4(velocity,p1.w);
        writeBuf.data[base+2u]=p2;writeBuf.data[base+3u]=vec4(age,p3.y,uHexTickReady?uintBitsToFloat(uHexCurrentTick):p3.z,p3.w);
    }
    return true;
}
