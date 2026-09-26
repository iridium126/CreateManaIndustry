layout(std430, binding = 24) readonly buffer HexInputs { vec4 v[]; } hexInput;
layout(std430, binding = 25) buffer HexLive { uint index[]; } hexLive;
const uint HEX_META_ROWS = 5u;
const uint HEX_ANCHORS = 20481u;
const uint HEX_VERTEX_STRIDE = 262144u;
uint hexBase(uint slot) { return 1u + slot * HEX_META_ROWS; }
bool hexValid(uint handle) {
    uint slot = handle & 4095u;
    return handle != 0u && slot < uint(hexInput.v[0].x)
        && floatBitsToUint(hexInput.v[hexBase(slot)].x) == handle
        && hexInput.v[hexBase(slot) + 1u].x > 0.0;
}
