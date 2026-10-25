package com.iridium126.createmanaindustry.client.render.shaderpack;

import foundry.veil.api.client.render.shader.program.UniformAccess;
import java.util.function.*;

/** World effects produce radiance/transmission; update is separate from drawing and runs once per frame. */
public record WorldEffectPass(String id,String shader,BooleanSupplier enabled,Runnable update,
        Consumer<UniformAccess> uniforms,boolean mist,boolean probe) {}
