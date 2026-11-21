#version 450 core

layout(binding = 0) uniform sampler2D sceneDepth;
layout(binding = 1) uniform sampler2D particleAtlas;
uniform bool translucent;
uniform vec2 extent;
uniform vec2 sourceScale;
uniform vec2 jitter;
in vec4 previousPosition;
in vec3 sprite;
layout(location = 0) out vec4 motionDepth;

void main() {
    vec2 uv = gl_FragCoord.xy / extent;
    vec2 size = vec2(textureSize(sceneDepth, 0)) * sourceScale;
    ivec2 pixel = clamp(ivec2(uv * size + jitter), ivec2(0), max(ivec2(size) - 1, ivec2(0)));
    float visible = texelFetch(sceneDepth, pixel, 0).r;
    // The geometry and source depth grids may sample slightly different positions on a plane.
    float tolerance = max(0.000002, fwidth(gl_FragCoord.z) * 0.5);
    if (previousPosition.w <= 0.00001) discard;
    if (translucent) {
        if (gl_FragCoord.z > visible + tolerance || texture(particleAtlas, sprite.xy).a * sprite.z < 0.1) discard;
    } else if (abs(visible - gl_FragCoord.z) > tolerance) discard;
    vec2 displacement = (previousPosition.xy / previousPosition.w * 0.5 + 0.5 - uv) * extent;
    motionDepth = vec4(clamp(displacement * vec2(1, -1), -extent, extent), visible, 1.0);
}
