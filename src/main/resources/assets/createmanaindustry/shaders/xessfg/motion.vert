#version 450 core

layout(location = 0) in vec4 currentClip;
layout(location = 1) in vec4 previousClip;
out vec4 previousPosition;

void main() {
    gl_Position = currentClip;
    previousPosition = previousClip;
}
