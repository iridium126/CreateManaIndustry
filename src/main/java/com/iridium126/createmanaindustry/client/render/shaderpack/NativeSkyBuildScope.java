package com.iridium126.createmanaindustry.client.render.shaderpack;

import net.irisshaders.iris.targets.RenderTargets;

/** Construction-only redirect for ShaderCreator's parent sampler factory. Never active during source rendering. */
public final class NativeSkyBuildScope {
    private static RenderTargets source,target;
    private NativeSkyBuildScope() {}
    public static boolean active(){return source!=null;}
    public static RenderTargets targets(RenderTargets value){return source!=null&&value==source?target:value;}
    public static void enter(RenderTargets from,RenderTargets to){if(source!=null)throw new IllegalStateException("Nested sky build");source=from;target=to;}
    public static void leave(){source=target=null;}
}
