layout(std430,binding=0) readonly buffer Pool { vec4 pool[]; };
layout(std430,binding=1) readonly buffer Admission { uvec4 admitted[]; };
layout(std430,binding=2) buffer Commands { uvec4 commands[]; };
layout(std430,binding=3) buffer Instances { uvec2 instances[]; };
layout(std430,binding=4) buffer Cursors { uint cursors[]; };
layout(std430,binding=5) readonly buffer Meshes { uvec4 meshes[]; };
layout(std430,binding=6) readonly buffer DrawAttachment { vec4 attachment[]; };
#ifdef CMI_SPLIT_DRAW
layout(std430,binding=7) readonly buffer PackageCullBounds { vec4 packageCullBounds[]; };
layout(std430,binding=8) readonly buffer ChainVisibility { uint chainVisibility[]; };
uniform float uEntityViewScale;
uniform uint uNativeCull;
#endif
uniform uint uCount,uMeshCount,uCapacity;
#ifdef CMI_SPLIT_DRAW
const uint PACKAGE_DRAW_LAYERS=2u;
#else
const uint PACKAGE_DRAW_LAYERS=1u;
#endif
uniform vec3 uCamPos;
#ifdef CMI_SPLIT_DRAW
uniform vec4 uFrustum[13];
uniform uint uFrustumCount;
uniform vec2 uCullBounds; // distance AND (safe box OR planes); negative disables a box
#else
uniform vec4 uFrustum[6];
#endif
bool visible(uint slot,uint candidate,uint flags) {
#ifdef CMI_SPLIT_DRAW
    if(uNativeCull!=0u) {
        // Create renders chain packages through their owning block entity. Match that
        // parent's native visibility decision instead of testing a per-package box.
        if((flags&1u)!=0u)return (chainVisibility[candidate>>5u]&(1u<<(candidate&31u)))!=0u;
        // PackageRenderer delegates to EntityRenderer.shouldRender: first the entity
        // render-distance test, then Iris tests getBoundingBoxForCulling().inflate(.5).
        uint p=4u*slot;
        float width=packageCullBounds[candidate].x,height=packageCullBounds[candidate].y;
        if(!(width>0.0) || !(height>0.0))return false;
        vec3 center=pool[p].xyz-uCamPos;
        float entityDistance=max(width,height)*64.0*uEntityViewScale;
        if(!(dot(center,center)<entityDistance*entityDistance))return false;
        vec3 lo=center-vec3(width*.5+.5,.5,width*.5+.5);
        vec3 hi=center+vec3(width*.5+.5,height+.5,width*.5+.5);
        if(uCullBounds.x>=0.0 && (any(greaterThan(lo,vec3(uCullBounds.x))) || any(lessThan(hi,vec3(-uCullBounds.x)))))return false;
        if(uCullBounds.y>=0.0 && !any(greaterThan(lo,vec3(uCullBounds.y))) && !any(lessThan(hi,vec3(-uCullBounds.y))))return true;
        for(uint j=0u;j<uFrustumCount;j++) {
            vec4 plane=uFrustum[j];
            vec3 outside=vec3(plane.x<0.0?lo.x:hi.x,plane.y<0.0?lo.y:hi.y,plane.z<0.0?lo.z:hi.z);
            if(dot(plane.xyz,outside)+plane.w<0.0)return false;
        }
        return true;
    }
#endif
    // Includes hook, sway, rigging and both interpolation endpoints.
    vec3 a=pool[4u*slot].xyz-uCamPos, b=pool[4u*slot+1u].xyz-uCamPos;
    uint box=floatBitsToUint(pool[4u*slot].w), rig=floatBitsToUint(pool[4u*slot+1u].w);
    float r=box<uMeshCount?uintBitsToFloat(meshes[box].z):0.0;
    if(rig<uMeshCount)r=max(r,uintBitsToFloat(meshes[rig].z));
    r+=abs(pool[4u*slot+3u].z)+2.0;
    if((flags&8u)!=0u) {
        uint a=2u*uint(attachment.length()/11)+9u*candidate;
        mat3 m=transpose(mat3(attachment[a].xyz,attachment[a+1u].xyz,attachment[a+2u].xyz));
        // Orthogonal parent columns may carry positive, nonuniform Sable scales.
        // The sphere contains both native interpolation endpoints and the entire rig.
        r*=sqrt(max(dot(m[0],m[0]),max(dot(m[1],m[1]),dot(m[2],m[2]))));
    }
#ifdef CMI_SPLIT_DRAW
    vec3 lo=min(a,b)-vec3(r),hi=max(a,b)+vec3(r);
    if(uCullBounds.x>=0.0 && (any(greaterThan(lo,vec3(uCullBounds.x))) || any(lessThan(hi,vec3(-uCullBounds.x)))))return false;
    if(uCullBounds.y>=0.0 && !any(greaterThan(lo,vec3(uCullBounds.y))) && !any(lessThan(hi,vec3(-uCullBounds.y))))return true;
    for(uint j=0u;j<uFrustumCount;j++)if(max(dot(uFrustum[j].xyz,a),dot(uFrustum[j].xyz,b))+uFrustum[j].w < -r)return false;
#else
    for(int j=0;j<6;j++)if(max(dot(uFrustum[j].xyz,a),dot(uFrustum[j].xyz,b))+uFrustum[j].w < -r)return false;
#endif
    return true;
}
uint mesh(uint slot,bool rig) { return floatBitsToUint(pool[4u*slot+(rig?1u:0u)].w); }
uint drawGroup(uint m,uint flags) {
#ifdef CMI_SPLIT_DRAW
    return m+((flags&1u)!=0u?uMeshCount:0u);
#else
    return m;
#endif
}
