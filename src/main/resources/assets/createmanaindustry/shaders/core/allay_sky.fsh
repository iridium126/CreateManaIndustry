#version 150
#moj_import <createmanaindustry:allay_sky.glsl>
uniform sampler2D CmiTargetSky;
uniform int CmiSkyDimension;
uniform vec4 CmiSkyContext;
uniform vec4 CmiRift;
uniform mat4 CmiView;
uniform mat4 CmiProjection;
in vec3 cmiRay;
out vec4 fragColor;
void main() {
    gl_FragDepth = 1.0;
    if (CmiSkyDimension == 0) {
        vec3 direction = normalize(cmiRay);
        float distanceToPlane = cmiRiftDistance(direction, CmiRift);
        if (distanceToPlane <= 0.0) discard;
        vec4 clip = CmiProjection * CmiView * vec4(direction * distanceToPlane, 1.0);
        if (clip.w <= 0.0) discard;
        gl_FragDepth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
    }
    vec4 clip=CmiSkyViewProjection*vec4(cmiRay,0.0);
    fragColor=vec4(texture(CmiTargetSky,clip.xy/clip.w*.5+.5).rgb,1.0);
}
