#version 150
uniform samplerBuffer Segments;
uniform mat4 View;
uniform mat4 Projection;
uniform vec4 Shape;
uniform vec2 ProjectionStyle;
out vec3 relativePosition;
void main() {
    vec4 segment=texelFetch(Segments,gl_InstanceID);
    vec2 point=(gl_VertexID%2)==0?segment.xy:segment.zw;
    float angle=float(gl_VertexID/2)*(6.28318530718/128.0);
    // Real axis points east; imaginary circle spans vertical/north axes. Rigid placement only.
    vec3 offset=Shape.w*vec3(point.x,point.y*cos(angle),point.y*sin(angle));
    // The original orbit/boundary stays unchanged. Only its projection removes ray divergence.
    // Use the camera-to-centre axis, not the camera's look direction: turning the head cannot tilt the Julia.
    vec3 centreRay=Shape.xyz/max(length(Shape.xyz),0.0001);
    relativePosition=Shape.xyz+ProjectionStyle.y*(offset-ProjectionStyle.x*centreRay*dot(offset,centreRay));
    gl_Position=Projection*View*vec4(relativePosition,1.0);
    gl_Position.z=min(gl_Position.z,gl_Position.w*.99999);
}
