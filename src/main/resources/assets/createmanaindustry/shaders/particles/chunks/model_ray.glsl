// Uses the caller's emitter block; shared by continuous picking and click-time validation.
uint cmiModelRayDistance(vec4 p0,vec4 p3,uint hb,vec3 eye,vec3 look,float reach){
    if(p3.y<=0.0||emitters.u[hb+7u].x<1.5||emitters.u[hb+7u].x>=2.5)return 0xffffffffu;
    float life=clamp(p3.x/max(p3.y,1e-5),0.0,1.0);
    float size=mix(emitters.u[hb+5u].z,emitters.u[hb+5u].w,pow(life,max(emitters.u[hb+6u].x,.001)))
        *(emitters.u[hb+18u].x>.5?1.0:p0.w);
    float scale=2.0*size/MODEL_ABOVE_FEET,halfW=.175*scale+HIT_INFLATE;
    vec3 lo=vec3(p0.x-halfW,p0.y-HIT_INFLATE,p0.z-halfW),hi=vec3(p0.x+halfW,p0.y+.6*scale+HIT_INFLATE,p0.z+halfW);
    float enter=0.0,exit=reach;
    for(int axis=0;axis<3;axis++){
        if(abs(look[axis])<1e-8){if(eye[axis]<lo[axis]||eye[axis]>hi[axis])return 0xffffffffu;}
        else {float a=(lo[axis]-eye[axis])/look[axis],b=(hi[axis]-eye[axis])/look[axis];enter=max(enter,min(a,b));exit=min(exit,max(a,b));}
    }
    if(enter>exit||reach<0.0)return 0xffffffffu;
    return floatBitsToUint(enter);
}
