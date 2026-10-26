#version 150
#moj_import <fog.glsl>
#moj_import <createmanaindustry:allay_sky.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec4 CmiRift;
in vec2 texCoord0;
in float vertexDistance;
in vec4 vertexColor;
in vec3 cmiCloudPosition;
out vec4 fragColor;
void main() {
    float cloudDistance=length(cmiCloudPosition);
    float planeDistance=cmiRiftDistance(normalize(cmiCloudPosition),CmiRift);
    if(planeDistance>0.0 && cloudDistance>planeDistance)discard;
    vec4 color=texture(Sampler0,texCoord0)*vertexColor*ColorModulator;
    if(color.a<0.1)discard;
    fragColor=linear_fog(color,vertexDistance,FogStart,FogEnd,FogColor);
}
