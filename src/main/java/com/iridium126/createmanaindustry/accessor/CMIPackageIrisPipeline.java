package com.iridium126.createmanaindustry.accessor;

import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.minecraft.client.renderer.ShaderInstance;

/** CMI-owned Iris bridge; no dependency on iris-veil's accessor mixins. */
public interface CMIPackageIrisPipeline {
    ProgramSet cmi$packageProgramSet();
    boolean cmi$packageHasShadows();
    void cmi$discardPackageShader(ShaderInstance shader);
    ShaderInstance cmi$createPackageShader(String name,ProgramSource source,ProgramId id,AlphaTest alpha,
            VertexFormat format,FogMode fog,boolean intensity,boolean fullbright,boolean glint,boolean text,boolean ie) throws IOException;
    ShaderInstance cmi$createPackageShadowShader(String name,ProgramSource source,ProgramId id,AlphaTest alpha,
            VertexFormat format,boolean intensity,boolean fullbright,boolean text,boolean ie) throws IOException;
}
