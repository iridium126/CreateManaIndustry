layout(std430, binding = BIND_HEX_INPUT) readonly buffer HexInputs { vec4 v[]; } hexInput;
layout(std430, binding = BIND_HEX_LIVE) buffer HexLive { uint index[]; } hexLive;
const uint HEX_META_ROWS = CMI_HEX_META_ROWS;
const uint HEX_ANCHORS = CMI_HEX_ANCHORS;
const uint HEX_VERTEX_STRIDE = CMI_HEX_VERTEX_STRIDE;
uint hexBase(uint slot) { return 1u + slot * HEX_META_ROWS; }
bool hexValid(uint handle) {
    uint slot = handle & 4095u;
    return handle != 0u && slot < uint(hexInput.v[0].x)
        && floatBitsToUint(hexInput.v[hexBase(slot)].x) == handle
        && hexInput.v[hexBase(slot) + 1u].x > 0.0;
}
