#version 150
in vec3 relativePosition;
uniform vec4 Shape;
uniform vec2 ProjectionStyle;
out float distanceToJulia;
void main() {
    distanceToJulia=length(relativePosition);
    gl_FragDepth=min(.99999,distanceToJulia/max(.001,length(Shape.xyz)+2.0*Shape.w*ProjectionStyle.y));
}
