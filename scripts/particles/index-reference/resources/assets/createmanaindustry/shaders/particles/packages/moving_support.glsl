// Pass-local sidecar, reset with a body upload. Never a network identity.
layout(std430,binding=11) buffer MovingSupport { vec4 movingSupport[]; };
const uint MOVING_ID_MASK=0x1fffffffu,MOVING_CARRIED=0x80000000u,MOVING_CONVERTED=0x40000000u;
const uint MOVING_FRICTION_DONE=0x20000000u;
