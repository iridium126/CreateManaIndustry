package com.iridium126.createmanaindustry.content.fluids;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

public final class CMIFluidTransferContext {
    private static final ThreadLocal<Level> CURRENT_LEVEL = new ThreadLocal<>();

    private CMIFluidTransferContext() {}

    public static void setLevel(Level level) {
        CURRENT_LEVEL.set(level);
    }

    public static void clear() {
        CURRENT_LEVEL.remove();
    }

    @Nullable
    public static Level getLevel() {
        return CURRENT_LEVEL.get();
    }
}
