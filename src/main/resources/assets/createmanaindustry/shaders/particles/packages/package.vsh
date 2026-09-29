layout(location=0) in vec3 aPosition;
layout(location=1) in vec2 aUv;
layout(location=2) in vec3 aNormal;
layout(location=3) in vec4 aColor;
layout(location=4) in uvec2 aInstance;
layout(std430,binding=0) readonly buffer Pool { vec4 pool[]; };
layout(std430,binding=6) readonly buffer Attachment { vec4 attachment[]; };
uniform mat4 ModelViewMat,ProjMat;
uniform vec3 uCamPos;
uniform float uPartialTick;
uniform sampler2D uLightmap;
uniform int uLightingMode,uConstantAmbient;
uniform vec3 uLight0,uLight1;
out vec2 vUv;
out vec4 vColor;
flat out uint vCutout;
mat3 rx(float a) { float c=cos(a),s=sin(a);return mat3(1,0,0,0,c,s,0,-s,c); }
mat3 ry(float a) { float c=cos(a),s=sin(a);return mat3(c,0,-s,0,1,0,s,0,c); }
mat3 rz(float a) { float c=cos(a),s=sin(a);return mat3(c,s,0,-s,c,0,0,0,1); }
float angleDelta(float a,float b) { return mod(b-a+180.0,360.0)-180.0; }
void main() {
    uint s=aInstance.x,p=4u*s,i=aInstance.y>>1u;
    vec4 pose=pool[p+3u];uint packedLight=floatBitsToUint(pool[p+2u].w),flags=packedLight>>24u;
    float yaw=pose.y+angleDelta(pose.y,pose.x)*uPartialTick;
    vec3 position=mix(pool[p+1u].xyz,pool[p].xyz,uPartialTick);
    mat3 rotation;
    vec3 local;
    if((flags&1u)!=0u) {
        vec3 target=mix(attachment[2u*i].xyz,pool[p+2u].xyz,uPartialTick);
        vec3 d=ry(radians(-yaw))*(target+vec3(0,.5,0)-position);
        // Avoid undefined atan(0,0) for the exact vertical/tether origin case.
        float zr=dot(d.xy,d.xy)>1e-12?(mod(degrees(atan(-d.x,d.y))+180.0,360.0)-180.0)*.5:0.0;
        float xr=dot(d.yz,d.yz)>1e-12?(mod(degrees(atan(d.z,d.y))+180.0,360.0)-180.0)*.5:0.0;
        rotation=ry(radians(yaw))*rz(radians(clamp(zr,-25.0,25.0)))*rx(radians(clamp(xr,-25.0,25.0)));
        if((aInstance.y&1u)==1u && (flags&2u)!=0u)rotation=rotation*ry(radians(180.0));
        local=rotation*(aPosition-vec3(.5)+vec3(0,-pose.z+7.0/16.0,0));
        position=target+vec3(0,10.0/16.0,0);
        vCutout=1u;
    } else {
        rotation=ry(radians(-yaw-90.0));
        local=rotation*(aPosition-vec3(.5))+vec3(0,.5,0);
        position+=attachment[2u*i+1u].xyz;
        vCutout=0u;
    }
    vec3 normal=rotation*aNormal;
    // Create's ordinary SBB uses Minecraft light directions. Flywheel's chain model
    // explicitly uses chunk diffuse. Unshaded quads use a zero normal in the cache.
    vec3 n2=normal*normal;
    bool unshaded=dot(n2,n2)<1e-8;
    float shade;
    if(uLightingMode==1 && (flags&1u)!=0u) {
        float yFactor=uConstantAmbient!=0?.9:(3.0+normal.y)*.25;
        shade=unshaded?(uConstantAmbient!=0?.9:1.0):min(n2.x*.6+n2.z*.8+n2.y*yFactor,1.0);
    } else {
        vec3 n=unshaded?vec3(0,1,0):mat3(ModelViewMat)*normal;
        shade=min(1.0,(max(dot(uLight0,n),0.0)+max(dot(uLight1,n),0.0))*.6+.4);
    }
    ivec2 light=ivec2(int((packedLight>>4u)&15u),int((packedLight>>20u)&15u));
    vColor=aColor*vec4(texelFetch(uLightmap,light,0).rgb*shade,1);
    vUv=aUv;
    gl_Position=ProjMat*ModelViewMat*vec4(position+local-uCamPos,1);
}
