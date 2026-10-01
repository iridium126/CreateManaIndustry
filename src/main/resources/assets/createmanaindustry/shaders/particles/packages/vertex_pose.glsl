// Shared by the ordinary renderer and injected shaderpack vertices. Inputs are committed
// pool/attachment values; this module has no buffer bindings, matrices or lightmap policy.
struct PackageVertexPose { vec3 position; vec3 normal; mat3 rotation; uint flags; uint packedLight; mat3 tangentRotation; };
mat3 packageRx(float a) { float c=cos(a),s=sin(a);return mat3(1,0,0,0,c,s,0,-s,c); }
mat3 packageRy(float a) { float c=cos(a),s=sin(a);return mat3(c,0,-s,0,1,0,s,0,c); }
mat3 packageRz(float a) { float c=cos(a),s=sin(a);return mat3(c,s,0,-s,c,0,0,0,1); }
float packageAngleDelta(float a,float b) { return mod(b-a+180.0,360.0)-180.0; }
PackageVertexPose packageVertexPose(vec3 vertex,vec3 normal,uvec2 instance,float partialTick,
        vec4 current,vec4 previous,vec4 targetLight,vec4 yawHook,vec4 previousTarget,vec4 nudge) {
    uint light=floatBitsToUint(targetLight.w),flags=light>>24u;
    float yaw=yawHook.y+packageAngleDelta(yawHook.y,yawHook.x)*partialTick;
    vec3 position=mix(previous.xyz,current.xyz,partialTick);mat3 rotation;vec3 local;
    if((flags&1u)!=0u) {
        vec3 target=mix(previousTarget.xyz,targetLight.xyz,partialTick);
        vec3 d=packageRy(radians(-yaw))*(target+vec3(0,.5,0)-position);
        float zr=dot(d.xy,d.xy)>1e-12?(mod(degrees(atan(-d.x,d.y))+180.0,360.0)-180.0)*.5:0.0;
        float xr=dot(d.yz,d.yz)>1e-12?(mod(degrees(atan(d.z,d.y))+180.0,360.0)-180.0)*.5:0.0;
        rotation=packageRy(radians(yaw))*packageRz(radians(clamp(zr,-25.0,25.0)))*packageRx(radians(clamp(xr,-25.0,25.0)));
        if((instance.y&1u)==1u && (flags&2u)!=0u)rotation=rotation*packageRy(radians(180.0));
        local=rotation*(vertex-vec3(.5)+vec3(0,-yawHook.z+7.0/16.0,0));
        position=target+vec3(0,10.0/16.0,0);
    } else {
        rotation=packageRy(radians(-yaw-90.0));
        local=rotation*(vertex-vec3(.5))+vec3(0,.5,0);position+=nudge.xyz;
    }
    return PackageVertexPose(position+local,rotation*normal,rotation,flags,light,rotation);
}
PackageVertexPose packageFramedVertexPose(vec3 vertex,vec3 normal,uvec2 instance,float partialTick,
        vec4 current,vec4 previous,vec4 targetLight,vec4 yawHook,vec4 previousTarget,vec4 nudge,
        vec4 rx,vec4 ry,vec4 rz,vec4 localCurrent,vec4 localPrevious,vec4 localTarget) {
    PackageVertexPose p=packageVertexPose(vertex,normal,instance,partialTick,
        vec4(localCurrent.xyz,current.w),vec4(localPrevious.xyz,previous.w),vec4(localTarget.xyz,targetLight.w),yawHook,previousTarget,nudge);
    mat3 m=transpose(mat3(rx.xyz,ry.xyz,rz.xyz));
    mat3 normals=mat3(m[0]/dot(m[0],m[0]),m[1]/dot(m[1],m[1]),m[2]/dot(m[2],m[2]));
    // Anchoring at the already imported world body avoids reconstructing a huge plot origin.
    p.position=mix(previous.xyz,current.xyz,partialTick)+m*(p.position-mix(localPrevious.xyz,localCurrent.xyz,partialTick));
    p.normal=dot(p.normal,p.normal)>1e-12?normalize(normals*p.normal):vec3(0);
    p.tangentRotation=m*p.tangentRotation;p.rotation=normals*p.rotation;
    return p;
}
