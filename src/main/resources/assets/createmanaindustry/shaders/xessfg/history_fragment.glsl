layout(std430, binding = 34) buffer CmiFgFragmentWinners { uint64_t cmiFgSurface[]; };
in vec4 cmiFgPriorClip;
in float cmiFgMatched;

uniform vec2 cmiFgLodDepth;

void cmiFgPublishDepth(float coverage, bool translucent, bool jittered, float depthValue) {
    if (cmiFgFrame.x == 0u || cmiFgMatched < 0.999 || cmiFgPriorClip.w <= 0.0 || coverage < 0.10) return;
    vec2 extent = vec2(cmiFgFrame.zw);
    vec2 jitter = jittered || cmiFgCameraFraction.w != 0.0 ? cmiFgSource.zw : vec2(0.0);
    // At a half-pixel tie the export's floor picks the right source pixel.
    // The inverse mapping must assign that pixel to the left geometry cell.
    ivec2 cell = clamp(ivec2(ceil((gl_FragCoord.xy - jitter) * extent / cmiFgSource.xy)) - 1,
                       ivec2(0), ivec2(extent) - 1);
    ivec2 center = clamp(ivec2((vec2(cell) + 0.5) * cmiFgSource.xy / extent + jitter),
                         ivec2(0), ivec2(cmiFgSource.xy) - 1);
    if (ivec2(gl_FragCoord.xy) != center) return;
    vec2 currentUv = (gl_FragCoord.xy - jitter) / cmiFgSource.xy;
    vec2 precedingUv = cmiFgPriorClip.xy / cmiFgPriorClip.w * 0.5 + 0.5;
    vec2 motion = clamp((precedingUv - currentUv) * vec2(extent.x, -extent.y), -extent, extent);
    if (any(isnan(motion)) || any(isinf(motion))) return;
    uint depth = uint(round(clamp(depthValue, 0.0, 1.0) * 16777215.0));
    uint strength = uint(round(clamp(coverage, 0.0, 1.0) * 254.0)) + 1u;
    // Within each layer, prefer the nearest eligible sample. Coverage breaks depth ties.
    uint rank = ((0xffffffu - depth) << 8) | strength;
    uint address = uint(cell.y) * cmiFgFrame.z + uint(cell.x);
    if (translucent) address += cmiFgFrame.z * cmiFgFrame.w;
    atomicMax(cmiFgSurface[address], (uint64_t(rank) << 32) | uint64_t(packHalf2x16(motion)));
}

void cmiFgPublish(float coverage, bool translucent, bool jittered) {
    cmiFgPublishDepth(coverage, translucent, jittered, gl_FragCoord.z);
}

void cmiFgPublishLod(float coverage) {
    cmiFgPublishDepth(coverage, false, false, gl_FragCoord.z * cmiFgLodDepth.x + cmiFgLodDepth.y);
}
