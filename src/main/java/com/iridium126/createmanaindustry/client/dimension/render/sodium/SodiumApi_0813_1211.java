package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.List;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.services.PlatformLevelRenderHooks;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import com.iridium126.createmanaindustry.mixin.sodium.AllvrSodiumWorldRendererAccessor;

/**
 * The single Sodium ABI adapter for the pinned {@code 0.8.13-beta.1+mc1.21.1}
 * build (sodium-parity plan §7.1). Every Sodium class the bridge touches is
 * referenced HERE and only here, so a future Sodium version migrates by
 * replacing this one class (plus the {@code mixin.sodium} hook set). Mixins
 * stay hand-written against the same ABI and are version-gated by
 * {@link AllvrSodiumCompatibilityProbe} and the mixin plugin.
 */
public final class SodiumApi_0813_1211 {

    private SodiumApi_0813_1211() {}

    /** The active world renderer, or null before/after a level. */
    public static SodiumWorldRenderer worldRenderer() {
        return SodiumWorldRenderer.instanceNullable();
    }

    /** The render section manager of the current level, or null. */
    public static RenderSectionManager sectionManager() {
        SodiumWorldRenderer renderer = worldRenderer();
        return renderer == null ? null : ((AllvrSodiumWorldRendererAccessor) renderer).cmi$getRenderSectionManager();
    }

    /** The pinned adapter is deliberately strict: a missing accessor is an
     * ABI failure, not a reason to silently fall back to the legacy renderer. */
    public static boolean hasSectionManager() {
        return worldRenderer() != null && sectionManager() != null;
    }

    /** Sodium's build-context preparation — normal-dimension path only. */
    public static ChunkRenderContext prepareSlice(Level level, SectionPos pos, ClonedChunkSectionCache cache) {
        return LevelSlice.prepare(level, pos, cache);
    }

    /** Builds a build-context over already-cloned sections (bridge path). */
    public static ChunkRenderContext context(SectionPos origin, ClonedChunkSection[] sections,
                                             BoundingBox volume, List<?> renderers) {
        return new ChunkRenderContext(origin, sections, volume, renderers);
    }

    /** Clones one section for the build context (bridge path supplies the
     *  cube-backed {@link LevelChunkSection}). */
    public static ClonedChunkSection cloneSection(Level level, LevelChunk chunk,
                                                  LevelChunkSection section, SectionPos pos) {
        return new ClonedChunkSection(level, chunk, section, pos);
    }

    /** NeoForge AddSectionGeometryEvent appenders for one section origin. */
    public static List<?> chunkMeshAppenders(Level level, BlockPos origin) {
        return PlatformLevelRenderHooks.getInstance().retrieveChunkMeshAppenders(level, origin);
    }

    // ------------------------------------------------------------------
    // section lifecycle passthrough (used by AllvrSodiumSectionLifecycle)
    // ------------------------------------------------------------------

    public static void onSectionAdded(RenderSectionManager manager, int x, int y, int z) {
        manager.onSectionAdded(x, y, z);
    }

    public static void onSectionRemoved(RenderSectionManager manager, int x, int y, int z) {
        manager.onSectionRemoved(x, y, z);
    }

    public static void scheduleRebuild(RenderSectionManager manager, int x, int y, int z, boolean important) {
        manager.scheduleRebuild(x, y, z, important);
    }

    public static void markGraphDirty(RenderSectionManager manager) {
        manager.markGraphDirty();
    }

    // ------------------------------------------------------------------
    // ABI probe
    // ------------------------------------------------------------------

    /**
     * Loads and member-checks every Sodium class the bridge binds to. Returns
     * null when the ABI matches, else a short failure reason. Invoked once by
     * {@link AllvrSodiumCompatibilityProbe} with the throwable captured — a
     * linkage failure (missing/renamed member) surfaces as a clean availability
     * result instead of a mid-render {@link NoSuchMethodError}.
     */
    public static String probeAbi() {
        try {
            ClassLoader loader = SodiumApi_0813_1211.class.getClassLoader();
            String[] classes = {
                "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
                "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
                "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection",
                "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion",
                "net.caffeinemc.mods.sodium.client.world.LevelSlice",
                "net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext",
                "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection",
                "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache",
                "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
                "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller",
                "net.caffeinemc.mods.sodium.client.render.viewport.Viewport",
            };
            for (String name : classes) {
                Class.forName(name, false, loader);
            }
            Class<?> rsm = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", false, loader);
            rsm.getMethod("onSectionAdded", int.class, int.class, int.class);
            rsm.getMethod("onSectionRemoved", int.class, int.class, int.class);
            rsm.getMethod("scheduleRebuild", int.class, int.class, int.class, boolean.class);
            rsm.getMethod("createRebuildTask",
                Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false, loader), int.class);
            Class<?> slice = Class.forName("net.caffeinemc.mods.sodium.client.world.LevelSlice", false, loader);
            slice.getMethod("prepare", Level.class, SectionPos.class,
                Class.forName("net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache", false, loader));
            Class<?> cloned = Class.forName(
                "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection", false, loader);
            cloned.getDeclaredMethod("copyLightData", Level.class, SectionPos.class);
            Class<?> meshingTask = Class.forName(
                "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
                false, loader);
            meshingTask.getMethod("execute",
                Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext",
                    false, loader),
                Class.forName("net.caffeinemc.mods.sodium.client.util.task.CancellationToken",
                    false, loader));
            Class<?> swr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", false, loader);
            swr.getDeclaredField("renderSectionManager");
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
