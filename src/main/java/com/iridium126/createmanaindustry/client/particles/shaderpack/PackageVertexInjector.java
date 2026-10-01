package com.iridium126.createmanaindustry.client.particles.shaderpack;

import java.util.Map;
import java.util.function.Function;

/** Strict package vertex injection; native pack stages and matrices remain the pack's. */
public final class PackageVertexInjector {
    private final ParticleVertexInjector injector=new ParticleVertexInjector();
    public String patch(String packVertex,Function<String,String> sources,String name) {
        return injector.patchStrict(packVertex,sources.apply("packages/package_merged.vsh"),name,Map.of(
                "at_tangent","cmi_Tangent","mc_midTexCoord","cmi_MidTexCoord",
                "mc_Entity","cmi_EntityData","at_midBlock","cmi_MidBlock"));
    }
}
