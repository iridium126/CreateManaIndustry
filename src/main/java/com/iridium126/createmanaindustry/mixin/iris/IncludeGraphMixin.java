package com.iridium126.createmanaindustry.mixin.iris;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


import net.irisshaders.iris.shaderpack.include.IncludeGraph;

/**
 * Applies the session's complete-path, source-checked candidate in memory.
 * Structural preflight runs before include loading; unmatched files pass through.
 */
@Mixin(value = IncludeGraph.class, remap = false)
public class IncludeGraphMixin {

	// require = 0: same rationale as IrisIdMapMixin.
	@Inject(method = "readFile", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void cmi$transformShaderSource(Path path, CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current()
                .transform(path, cir.getReturnValue()));
	}
}
