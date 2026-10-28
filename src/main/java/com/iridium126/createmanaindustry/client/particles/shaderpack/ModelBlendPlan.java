package com.iridium126.createmanaindustry.client.particles.shaderpack;

import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;

/** Encoded MRT attachments must keep the pack's native blend/overwrite contract. */
public record ModelBlendPlan(boolean nativeAttachments) {
    public static final ModelBlendPlan LEGACY=new ModelBlendPlan(false);
    public static ModelBlendPlan resolve(ProgramDirectives directives){return new ModelBlendPlan(!directives.getBufferBlendOverrides().isEmpty());}
    public String report(){return nativeAttachments?"native per-attachment blend":"legacy translucent blend fallback";}
}
