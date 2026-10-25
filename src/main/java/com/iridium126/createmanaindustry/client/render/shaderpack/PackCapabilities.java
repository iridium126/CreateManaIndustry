package com.iridium126.createmanaindustry.client.render.shaderpack;

/** Evidence, not a pack-name/version allowlist. Unknown scene encodings use the display-space path. */
public record PackCapabilities(String family, WorldPath worldPath, int sceneBuffer, int exposureBuffer,
        int mistBuffer, int glowBuffer, String evidence,NativeSceneContract nativeScene) {
    public enum WorldPath { BASELINE, SCENE, TRANSLUCENT, HDR, RAW }
    public static PackCapabilities detect(ShaderPackInventory inv,ShaderPatchPlan plan) {
        String shadows=inv.source("lib/Shadows.glsl"), settings=inv.source("lib/Shadow_Params.glsl");
        String composite=inv.source("programs/composite/Composite14.frag");
        if(plan.rawLayers())return new PackCapabilities("curve-encoded",WorldPath.RAW,-1,-1,plan.mistBuffer(),plan.glowBuffer(),"complete curve-layer patch; unused attachments checked",null);
        // These are contracts for the currently implemented conversion equations, not family-name guesses.
        String all=String.join("\n",inv.sources().values());
        String translucent=ShaderPackInventory.code(inv.source("dimensions/composite3.fsh"));
        if(!shadows.isEmpty()&&!settings.isEmpty()&&all.contains("TRANSLUCENT_COLORED_SHADOWS")
                &&ShaderPackInventory.code(all).matches("(?s).*texelFetch(?:2D)?\\s*\\(\\s*colortex4\\s*,\\s*ivec2\\s*\\(\\s*10\\s*,\\s*37\\s*\\).*"))
            if(translucent.matches("(?s).*color\\s*\\*=\\s*\\(\\s*1(?:\\.0)?\\s*-\\s*TranslucentShader\\.a\\s*\\).*color\\s*\\+=\\s*TranslucentShader\\.rgb\\s*\\*\\s*10(?:\\.0)?\\s*;.*"))
            return new PackCapabilities("log-shadow/translucent",WorldPath.TRANSLUCENT,2,4,-1,-1,"shadow library + exposure texel contract",null);
        var nativeScenes=NativeSceneContract.detect(inv);
        if(nativeScenes.size()==1){var scene=nativeScenes.getFirst();return new PackCapabilities(scene.id(),scene.hdr()?WorldPath.HDR:WorldPath.SCENE,scene.buffer(),-1,-1,-1,scene.evidence(),scene);}
        return new PackCapabilities(plan.family(),WorldPath.BASELINE,-1,-1,-1,-1,
                nativeScenes.size()>1?"ambiguous native scene contracts":"native scene/exposure semantics unproven; depth-aware display-space composition",null);
    }
}
