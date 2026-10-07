// uint ancestor, float separation, float minimum centre Y, float support velocity Y.
// Set only after pointer jumping: the proposed lift was clipped by a known ceiling.
const uint SUPPORT_CEILING_CLIPPED=0xfffffffdu;
layout(std430,binding=6) readonly buffer SupportInput { uvec4 supportIn[]; };
layout(std430,binding=7) writeonly buffer SupportOutput { uvec4 supportOut[]; };
layout(std430,binding=8) buffer SupportControl { uvec4 supportStats; uvec4 supportCommand; };
