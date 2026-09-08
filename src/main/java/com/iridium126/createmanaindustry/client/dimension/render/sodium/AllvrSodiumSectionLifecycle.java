package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.concurrent.atomic.AtomicReference;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Sodium 0.8.13 lifecycle shims kept separate from the data source. */
public final class AllvrSodiumSectionLifecycle {

    private record AddRequest(ClientLevel level, int x, int y, int z) {}
    private static final AtomicReference<AddRequest> NATIVE_ADD = new AtomicReference<>();

    private AllvrSodiumSectionLifecycle() {}

    public static void nativeAdd(RenderSectionManager manager, int x, int y, int z) {
        ClientLevel level = AllvrSodiumBridge.level();
        NATIVE_ADD.set(new AddRequest(level, x, y, z));
        try {
            SodiumApi_0813_1211.onSectionAdded(manager, x, y, z);
        } finally {
            NATIVE_ADD.set(null);
        }
    }

    public static boolean internalAdd() {
        return NATIVE_ADD.get() != null;
    }

    public static LevelChunkSection[] sectionsFor(ChunkAccess original) {
        AddRequest request = NATIVE_ADD.get();
        if (request == null || !AllvrSodiumSectionSource.isAllay(request.level())) {
            return original.getSections();
        }
        return AllvrSodiumSectionSource.sectionArray(request.level(), request.x(), request.y(), request.z(),
            AllvrSodiumBridge.resourceRevision(), AllvrSodiumBridge.window().epoch());
    }

    public static int sectionIndex(int ignored) {
        return internalAdd() ? 0 : ignored;
    }
}
