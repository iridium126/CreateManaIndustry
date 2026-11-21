package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.iridium126.createmanaindustry.client.xessfg.XessFgGpuMotion;
import com.iridium126.createmanaindustry.client.xessfg.XessFgGpuShaderSources;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = TransformPatcher.class, remap = false)
abstract class XessFgTerrainProgramMixin {
    @ModifyReturnValue(method = "patchVanilla", at = @At("RETURN"))
    private static Map<PatchShaderType, String> cmi$mergedMotion(Map<PatchShaderType, String> stages,
                                                               @Local(argsOnly = true, ordinal = 0) String name) {
        if (name == null || !name.startsWith("cmi_model_entities") || !XessFgGpuMotion.shaderSupport()
                || !vertexFragmentOnly(stages)) return stages;
        return withMotion(stages, XessFgGpuShaderSources.mergedParticles(stages.get(PatchShaderType.VERTEX),
                stages.get(PatchShaderType.FRAGMENT)));
    }

    @ModifyReturnValue(method = "patchSodium", at = @At("RETURN"))
    private static Map<PatchShaderType, String> cmi$terrainMotion(Map<PatchShaderType, String> stages,
                                                                  @Local(argsOnly = true, ordinal = 0) String name) {
        var pack = ShaderPackSession.current();
        if (pack.xessFgPack() == null || !XessFgGpuMotion.shaderSupport() || name == null) return stages;
        String route = name.toLowerCase(Locale.ROOT);
        if (route.contains("shadow") || stages == null || stages.get(PatchShaderType.TESS_CONTROL) != null
                || stages.get(PatchShaderType.TESS_EVAL) != null) return stages;
        String vertex = stages.get(PatchShaderType.VERTEX), fragment = stages.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null) return stages;
        String geometry = stages.get(PatchShaderType.GEOMETRY);
        String passthrough = geometry == null ? null : XessFgGpuShaderSources.terrainGeometry(pack.xessFgPack(), geometry);
        if (geometry != null && passthrough == null) return stages;
        var patched = XessFgGpuShaderSources.terrain(pack.xessFgPack(), vertex, fragment,
                route.contains("translucent") || route.contains("water"), passthrough != null);
        if (patched == null) return stages;
        pack.terrainMotionPatched();
        var result = withMotion(stages, patched);
        if (passthrough != null) result.put(PatchShaderType.GEOMETRY, passthrough);
        return result;
    }

    private static boolean vertexFragmentOnly(Map<PatchShaderType, String> stages) {
        return stages != null && stages.get(PatchShaderType.VERTEX) != null && stages.get(PatchShaderType.FRAGMENT) != null
                && stages.get(PatchShaderType.GEOMETRY) == null && stages.get(PatchShaderType.TESS_CONTROL) == null
                && stages.get(PatchShaderType.TESS_EVAL) == null;
    }

    private static Map<PatchShaderType, String> withMotion(Map<PatchShaderType, String> stages, XessFgGpuShaderSources.Pair patched) {
        if (patched == null) return stages;
        var result = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
        result.putAll(stages);
        result.put(PatchShaderType.VERTEX, patched.vertex());
        result.put(PatchShaderType.FRAGMENT, patched.fragment());
        return result;
    }
}
