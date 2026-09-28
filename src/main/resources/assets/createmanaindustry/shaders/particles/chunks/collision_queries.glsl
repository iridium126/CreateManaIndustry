// Included in update.comp; shares its declared simulation state.
bool solidAt(vec3 wp, vec3 mOrigin, int slice) {
    vec3 rel = wp - mOrigin;
    ivec3 tc = ivec3(floor(rel + vec3(0.0001)));
    if (tc.x >= 0 && tc.x < SX && tc.y >= 0 && tc.y < SY && tc.z >= 0 && tc.z < SZ) {
        tc.z += slice * SZ;
        return texelFetch(uCollision, tc, 0).r > 0.5;
    }
    // SEAM FALLBACK: the point lies outside this slice — almost always a
    // NEIGHBOUR quadrant of the storm's 2x2 bake grid, whose seam runs
    // through the storm anchor (an integer BlockPos, so the seam coincides
    // with a block seam on the ground). Out-of-bounds used to read as FREE,
    // which LIES at seams: an embedded particle's escape scan then "exited"
    // sideways into the neighbour floor block across the seam (the scan's
    // +x probe at distance 1 tied with the true free up-exit and won by
    // check order), and the escape ping-ponged the particle between the two
    // floor blocks astride the seam, one teleport per frame — the reported
    // flicker. Test the slice that actually CONTAINS the point instead.
    // Cost: the hot sweep path pays the meta loop only within ~1 block of
    // the two seam lines (or on genuine escape scans); everything else hits
    // the fast path above.
    for (int i = 0; i < BAKE_SLICES; i++) {
        vec4 meta = bakeMeta.m[i];
        if (meta.w < 0.5)
            continue;
        vec3 nrel = wp - meta.xyz;
        ivec3 ntc = ivec3(floor(nrel + vec3(0.0001)));
        if (ntc.x < 0 || ntc.x >= SX || ntc.y < 0 || ntc.y >= SY || ntc.z < 0 || ntc.z >= SZ)
            continue;
        ntc.z += i * SZ;
        if (texelFetch(uCollision, ntc, 0).r > 0.5)
            return true;
    }
    return false; // outside every baked volume: no collision (honest)
}

// First FREE voxel along dir from pos, in whole-voxel steps (1-based
// distance), or 0.0 when every voxel within maxVox is solid. Stepping
// outside the baked volume reads as free — there is no collision there.
float escapeScan(vec3 pos, vec3 mOrigin, int slice, vec3 dir, int maxVox) {
    for (int i = 1; i <= maxVox; i++) {
        if (!solidAt(pos + dir * float(i), mOrigin, slice))
            return float(i);
    }
    return 0.0;
}

