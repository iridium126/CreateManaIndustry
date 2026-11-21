layout(std140, binding = 31) uniform CmiFgGrid {
    uvec4 cmiFgFrame;       // current epoch, retained epoch, geometry size
    vec4 cmiFgSource;       // active source size, source jitter
    ivec4 cmiFgCameraBlock;
    vec4 cmiFgCameraFraction; // camera fraction and flag for externally jittered GPU rasterization
};
