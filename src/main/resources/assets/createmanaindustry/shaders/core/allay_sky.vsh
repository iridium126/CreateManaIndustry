#version 150
in vec3 Position;
uniform mat4 CmiView;
uniform mat4 CmiProjection;
uniform mat4 CmiInverseView;
uniform mat4 CmiInverseProjection;
uniform int CmiSkyDimension;
out vec3 cmiRay;
void main() {
    gl_Position = vec4(Position.xy, 1.0, 1.0);
    cmiRay = mat3(CmiInverseView) * (CmiInverseProjection * gl_Position).xyz;
}
