#include createmanaindustry:world_effect_space
uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D ExposureSampler;
uniform int ExposureBound;
uniform int ExposureMode;
uniform vec4 ExposureParams;
uniform int OutputMode;
uniform vec4 ProbeAnchor;
uniform float ProbeTime;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 scene=texture(DiffuseSampler0,texCoord);
    vec3 eye=CmiWorldCamera.CameraPosition;
    vec3 ray=normalize(viewDirFromUv(texCoord));
    // Bound the world-space footprint before clipping. Near the horizon the
    // plane intersection and its derivatives diverge; an unbounded fwidth
    // would turn the finite ring into a colored horizon-wide band.
    float safeY=abs(ray.y)<1e-6?(ray.y<0.0?-1e-6:1e-6):ray.y;
    float t=(ProbeAnchor.y-eye.y)/safeY;
    vec2 p=(eye+ray*t-ProbeAnchor.xyz).xz;
    float r=length(p);
    float maxWidth=max(0.12,min(0.5,ProbeAnchor.w*0.04));
    float width=clamp(fwidth(r)*1.5,0.12,maxWidth);
    if(abs(ray.y)<1e-6||t<=0.0||r>ProbeAnchor.w+2.0*maxWidth){fragColor=scene;return;}
    float depth=texture(DiffuseDepthSampler,texCoord).r;
    float endDistance=depth<0.999999?distance(screenToWorldSpace(texCoord,depth).xyz,eye):1e8;
    if(t<=0.0||t>endDistance-0.02){fragColor=scene;return;}
    float ring=1.0-smoothstep(width,width*2.0,abs(r-ProbeAnchor.w));
    float axis=(1.0-smoothstep(width,width*2.0,abs(p.x)))*step(0.0,p.y)*step(r,ProbeAnchor.w);
    float crossAxis=(1.0-smoothstep(width,width*2.0,abs(p.y)))*step(0.0,p.x)*step(r,ProbeAnchor.w);
    vec3 light=vec3(0.12,0.7,0.85)*ring*(0.65+0.15*sin(ProbeTime*2.0))
              +vec3(0.3,0.9,0.25)*axis*0.6+vec3(0.9,0.2,0.3)*crossAxis*0.6;
    if(OutputMode==2){fragColor=vec4(scene.rgb+light,scene.a);return;}
    if(OutputMode==1){
        float exposure=ExposureBound==1?texelFetch(ExposureSampler,ivec2(10,37),0).r:1.0;
        light*=0.1*clamp(1.0/max(exposure,0.001),0.125,32.0);
    } else if(ExposureMode==2)light*=ExposureParams.x;
    fragColor=vec4(cmiEncodeScene(cmiDecodeScene(scene.rgb)+light),scene.a);
}
