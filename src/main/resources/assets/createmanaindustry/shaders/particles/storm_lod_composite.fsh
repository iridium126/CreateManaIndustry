uniform sampler2D uColors[8];
uniform sampler2D uLodDepth,uSceneDepth;
uniform mat4 uInverseLodProjection,uInverseSceneProjection,uSceneProjection;
uniform int uDepthOnly;
in vec2 texCoord;
layout(location=0) out vec4 color0;
layout(location=1) out vec4 color1;
layout(location=2) out vec4 color2;
layout(location=3) out vec4 color3;
layout(location=4) out vec4 color4;
layout(location=5) out vec4 color5;
layout(location=6) out vec4 color6;
layout(location=7) out vec4 color7;
void main(){
    float depth=texture(uLodDepth,texCoord).r;
    if(depth>=1.)discard;
    vec4 hit=uInverseLodProjection*vec4(texCoord*2.-1.,depth*2.-1.,1);hit/=hit.w;
    float scene=texture(uSceneDepth,texCoord).r;
    if(scene<1.){
        vec4 obstacle=uInverseSceneProjection*vec4(texCoord*2.-1.,scene*2.-1.,1);obstacle/=obstacle.w;
        if(dot(obstacle.xyz,obstacle.xyz)<=dot(hit.xyz,hit.xyz))discard;
    }
    vec4 projected=uSceneProjection*hit;
    float sceneDepth=projected.z/projected.w*.5+.5;
    // Far colors remain visible, but an unrepresentable hit must never become
    // a false surface at the main camera's far plane.
    if(uDepthOnly!=0 && (projected.w<=0. || sceneDepth<0. || sceneDepth>=1.))discard;
    gl_FragDepth=clamp(sceneDepth,0.,1.);
    color0=texture(uColors[0],texCoord);color1=texture(uColors[1],texCoord);
    color2=texture(uColors[2],texCoord);color3=texture(uColors[3],texCoord);
    color4=texture(uColors[4],texCoord);color5=texture(uColors[5],texCoord);
    color6=texture(uColors[6],texCoord);color7=texture(uColors[7],texCoord);
}
