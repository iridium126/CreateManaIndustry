// Explicit main-camera inputs. World effects must not bind/unbind Veil's global shader-block registry.
struct CmiWorldCameraData { mat4 IProjMat; mat4 IViewMat; vec3 CameraPosition; };
uniform CmiWorldCameraData CmiWorldCamera;
// Transfer used by the verified native scene buffer, before the pack's own
// exposure/tonemap. Linear, translucent and raw paths use gamma 1.
uniform float CmiSceneGamma;
vec3 cmiDecodeScene(vec3 color) {
    return CmiSceneGamma > 1.001 ? pow(max(color, vec3(0.0)), vec3(CmiSceneGamma)) : color;
}
vec3 cmiEncodeScene(vec3 color) {
    return CmiSceneGamma > 1.001 ? pow(max(color, vec3(0.0)), vec3(1.0 / CmiSceneGamma)) : color;
}
vec4 screenToWorldSpace(vec2 uv,float depth) {
    vec4 view=CmiWorldCamera.IProjMat*(vec4(uv,depth,1.0)*2.0-1.0);
    return vec4(CmiWorldCamera.CameraPosition,0.0)+CmiWorldCamera.IViewMat*(view/view.w);
}
vec3 viewDirFromUv(vec2 uv) {
    vec4 view=CmiWorldCamera.IProjMat*(vec4(uv,1.0,1.0)*2.0-1.0);
    return normalize((CmiWorldCamera.IViewMat*(view/view.w)).xyz);
}
