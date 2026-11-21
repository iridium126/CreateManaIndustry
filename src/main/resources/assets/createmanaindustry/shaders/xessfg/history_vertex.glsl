struct CmiFgVertexEntry {
    vec4 clip;
    uvec4 identity;
};
layout(std430, binding = 32) readonly buffer CmiFgPreviousVertices { CmiFgVertexEntry cmiFgPrevious[]; };
layout(std430, binding = 33) buffer CmiFgCurrentVertices { CmiFgVertexEntry cmiFgCurrent[]; };

out vec4 cmiFgPriorClip;
out float cmiFgMatched;

uint cmiFgHash(uvec3 key) {
    uint h = key.x * 0x9e3779b1u ^ key.y * 0x85ebca6bu ^ key.z * 0xc2b2ae35u;
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    return h ^ (h >> 16);
}

uvec3 cmiFgWorldKey(vec3 relativePosition) {
    return uvec3(ivec3(round((relativePosition + cmiFgCameraFraction.xyz) * 2048.0)))
           + uvec3(cmiFgCameraBlock.xyz) * 2048u;
}

void cmiFgRecord(uvec3 key, vec4 clip, bool deforming) {
    if (cmiFgFrame.x == 0u || clip.w <= 0.0 || any(isnan(clip)) || any(isinf(clip))) return;
    uint slot = cmiFgHash(key) & uint(cmiFgCurrent.length() - 1);
    if (cmiFgFrame.y != 0u && all(equal(cmiFgPrevious[slot].identity, uvec4(key, cmiFgFrame.y)))) {
        cmiFgPriorClip = cmiFgPrevious[slot].clip;
        cmiFgMatched = 1.0;
    }
    if (!deforming && cmiFgMatched == 0.0) return;
    // The first owner of this cell writes it. Hash collisions cause misses, never a cross-object match.
    if (atomicExchange(cmiFgCurrent[slot].identity.w, cmiFgFrame.x) != cmiFgFrame.x) {
        cmiFgCurrent[slot].identity.xyz = key;
        cmiFgCurrent[slot].clip = clip;
    }
}
