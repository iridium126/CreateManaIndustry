// Shared ALLVR node SSBO layout + decode (doc §13 阶段 4a, corrected layout).
// Single-sourced across the traversal / cmdgen / revalidate kernels via
// #pragma cmi_include. 32 B per node:
//   a.x = absBlockX (signed int32)    b.x = quadCount
//   a.y = absBlockY (signed int32)    b.y = visibleFrameId
//   a.z = absBlockZ (signed int32)    b.z = quadStart (arena quad index)
//   a.w = childPtr (0 = none, 4c)     b.w = slot(18b)|flags(8b)<<18
// The original 21-bit-biased packing could not fit 42 bit of y+z in a 32-bit
// uint (the fields overlapped at bit 32 and corrupted every position) — the
// smoke-test node dump caught it; absolute signed coords need no bit tricks
// (±30M < 2^31). The legacy LOD level field left with the legacy LOD path —
// every node is one full-res near cell. Mirrors AllvrNodeStore.packWord's
// b.w bit assignment.
struct Node {
    uvec4 a;
    uvec4 b;
};
layout(std430, binding = BIND_NODES) buffer NodeBuf {
    Node nodes[];
};

bool nodeLive(Node n) {
    uint f = n.b.w >> 18;
    return (f & NODE_FLAG_DEAD) == 0u && (f & NODE_FLAG_HAS_MESH) != 0u;
}

/** Absolute block-space origin of the node's min corner (signed). */
ivec3 nodeAbsOrigin(Node n) {
    return ivec3(int(n.a.x), int(n.a.y), int(n.a.z));
}
