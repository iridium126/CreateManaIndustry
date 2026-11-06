package com.iridium126.createmanaindustry.mixin.iris;

import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WorldRenderingSettings.class, remap = false)
public interface FeatherLeafTerrainSettings {
    @Accessor("reloadRequired")
    void cmi$requireLeafMeshReload(boolean reloadRequired);
}
