package com.iridium126.createmanaindustry.dimension.storage;

import net.minecraft.nbt.CompoundTag;

/**
 * Explicit migration entry point for the ALLVR cube NBT schema (plan §6).
 * The vanilla {@code DataFixTypes.CHUNK} pipeline is deliberately NOT used —
 * the 32³ cube schema is not a vanilla chunk schema; migrations run here,
 * keyed by {@code AllvrFormatVersion}.
 * <p>
 * V1 accepts only version 1. Unknown newer versions fail closed — the record
 * is corrupt as far as this mod version is concerned and must never be
 * overwritten by a regeneration.
 */
public final class AllvrCubeDataFixes {

    static final int CURRENT_VERSION = 1;

    /**
     * Validates and (in future versions) migrates a cube root tag to the
     * current schema. Returns the tag unchanged for V1.
     *
     * @throws AllvrCubeCorruptedException on missing or unknown versions
     */
    public static CompoundTag update(CompoundTag root) throws AllvrCubeCorruptedException {
        if (!root.contains("AllvrFormatVersion", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
            throw new AllvrCubeCorruptedException("cube record missing AllvrFormatVersion");
        }
        int version = root.getInt("AllvrFormatVersion");
        if (version > CURRENT_VERSION) {
            throw new AllvrCubeCorruptedException(
                "cube record format " + version + " is newer than supported " + CURRENT_VERSION
                    + " — upgrade the mod, do not regenerate");
        }
        if (version < CURRENT_VERSION) {
            throw new AllvrCubeCorruptedException("legacy format " + version + " has no migration path");
        }
        return root;
    }

    private AllvrCubeDataFixes() {}
}
