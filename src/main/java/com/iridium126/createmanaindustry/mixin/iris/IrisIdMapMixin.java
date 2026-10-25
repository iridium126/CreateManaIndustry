package com.iridium126.createmanaindustry.mixin.iris;

import java.nio.file.Path;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.render.shaderpack.BlockIdMapContext;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;

/**
 * Publishes the session's preflighted block/item map copies at construction tail,
 * before the rendering pipeline consumes them. Rejected candidates leave the
 * original maps intact and fail loading so the unmodified pack can be retried.
 */
@Mixin(value = IdMap.class, remap = false)
public class IrisIdMapMixin {

	@Shadow(remap = false)
	private Int2ObjectLinkedOpenHashMap<List<BlockEntry>> blockPropertiesMap;

	@Shadow(remap = false)
	@Final
	@Mutable
	private Object2IntMap<NamespacedId> itemIdMap;

	// require = 0: a future Iris refactor must degrade this cosmetic feature to
	// a logged no-op rather than crash the game through the config's defaultRequire.
	@Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
	private void cmi$applyColoredLightIdMaps(Path shaderPath, ShaderPackOptions shaderPackOptions,
		Iterable<StringPair> environmentDefines, CallbackInfo ci) {
		BlockIdMapContext ctx = com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current()
                .apply(new BlockIdMapContext(blockPropertiesMap, itemIdMap));
        blockPropertiesMap=ctx.blockProperties();
        itemIdMap=ctx.itemIds();
	}
}
