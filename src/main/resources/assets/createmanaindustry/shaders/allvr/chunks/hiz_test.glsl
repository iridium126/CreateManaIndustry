// Shared HiZ occlusion test (doc §9.2), included by the traversal and
// revalidate kernels. Conservative by construction: the pyramid stores MAX
// window-space depth (farthest occluder per texel), the mip is chosen so the
// node's dilated screen rect overlaps at most 2x2 texels, and all four are
// sampled (coarser = larger max = safe),
// nodes touching/behind the near plane pass, and the depth comparison bias is
// scaled by the node's nearest view distance (1.5 blocks of view-space slack —
// depth precision collapses with distance, a constant epsilon would flicker).
// Degrades safely: sky = depth 1.0 → nothing occluded against sky regions.
uniform sampler2D uHiz;      // unit HIZ_UNIT (bound by the renderer when enabled)
uniform mat4 uModelView;
uniform mat4 uProj;
uniform vec3 uCamFrac;       // camera fraction omitted by integer relOrigin
uniform vec2 uViewport;      // main target pixel size
uniform int uHizTopLevel;    // highest populated mip
uniform float uDepthBiasScale; // 1.5 * near * far / (far - near)

bool allvrHizOccluded(ivec3 relOrigin, ivec3 extent) {
    float minZ = 1e30;
    float minW = 1e30;
    vec2 ndcMin = vec2(1e30);
    vec2 ndcMax = vec2(-1e30);
    for (int i = 0; i < 8; i++) {
        ivec3 cornerInt = relOrigin + extent * ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 corner = vec3(cornerInt) - uCamFrac;
        vec4 vp = uModelView * vec4(corner, 1.0);
        vec4 clip = uProj * vp;
        if (clip.w <= 0.0) {
            return false; // touches/behind the near plane — conservative pass
        }
        vec3 ndc = clip.xyz / clip.w;
        minZ = min(minZ, ndc.z);
        minW = min(minW, clip.w);
        ndcMin = min(ndcMin, ndc.xy);
        ndcMax = max(ndcMax, ndc.xy);
    }
    // Screen rect (full-resolution pixels) + 2 px conservative dilation.
    // Clamp to the viewport before choosing the mip: off-screen coverage is
    // raster-clipped and must not enlarge the occlusion footprint.
    vec2 pxMin = (ndcMin * 0.5 + 0.5) * uViewport - 2.0;
    vec2 pxMax = (ndcMax * 0.5 + 0.5) * uViewport + 2.0;
    vec2 viewportMax = max(uViewport - vec2(1e-4), vec2(0.0));
    pxMin = clamp(pxMin, vec2(0.0), viewportMax);
    pxMax = clamp(pxMax, vec2(0.0), viewportMax);
    if (any(lessThanEqual(pxMax, pxMin))) {
        return false;
    }

    vec2 dPx = max(pxMax - pxMin, vec2(1.0));
    // Pyramid mip 0 already represents 2x2 full-resolution pixels. Choose a
    // texel span >= the rect extent, so the rect can cross at most one texel
    // boundary on each axis. A single center lookup is not conservative: a
    // large LOD node can be visible around a foreground occluder while its
    // center texel is covered, which caused the camera-center visibility hole.
    int level = int(clamp(ceil(log2(max(dPx.x, dPx.y))) - 1.0,
                          0.0, float(uHizTopLevel)));
    ivec2 mipSize = textureSize(uHiz, level);
    // Convert through normalized viewport coordinates instead of assuming
    // every mip texel is an exact power-of-two span. The half-resolution base
    // uses ceil(width/2), while GL floors subsequent odd mip dimensions.
    vec2 uvMin = pxMin / uViewport;
    vec2 uvMax = pxMax / uViewport;
    ivec2 texelMin = clamp(ivec2(floor(uvMin * vec2(mipSize))), ivec2(0), mipSize - 1);
    ivec2 texelMax = clamp(ivec2(floor((uvMax - vec2(1e-7)) * vec2(mipSize))),
                           ivec2(0), mipSize - 1);
    if (any(greaterThan(texelMax - texelMin, ivec2(1)))) {
        return false; // top mip too fine for this rect: conservative pass
    }
    float pyramidZ = texelFetch(uHiz, texelMin, level).r;
    pyramidZ = max(pyramidZ, texelFetch(uHiz, ivec2(texelMax.x, texelMin.y), level).r);
    pyramidZ = max(pyramidZ, texelFetch(uHiz, ivec2(texelMin.x, texelMax.y), level).r);
    pyramidZ = max(pyramidZ, texelFetch(uHiz, texelMax, level).r);
    float eps = uDepthBiasScale / max(minW * minW, 1e-8) + 1e-7;
    // minZ is NDC z in [-1,1] but the pyramid stores window depth in [0,1] —
    // convert before comparing (uDepthBiasScale is calibrated in depth units).
    // Raw NDC-z-vs-depth shifted the effective threshold to
    // depth > (pyramid + 1) / 2, so only the far half of the frustum was
    // ever culled (review 2026-09-04).
    float depthZ = minZ * 0.5 + 0.5;
    return depthZ > pyramidZ + eps;
}
