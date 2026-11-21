#version 450 core

layout(location = 0) in vec4 currentClip;
layout(location = 1) in vec4 previousClip;
layout(location = 2) in vec3 coverage;
out vec4 previousPosition;
out vec3 sprite;

void main() {
    gl_Position = currentClip;
    previousPosition = previousClip;
    sprite = coverage;
}
