package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import me.cortex.voxy.commonImpl.WorldIdentifier;

/**
 * Maps Allay's unbounded absolute Y into Voxy's signed eight-bit section-Y
 * key without allowing two absolute heights to share a persistent key.
 *
 * <p>Voxy L0 sections are 32 blocks wide and its section key reserves 8 bits
 * for Y, so one engine can represent 256 L0 sections = 8192 blocks.  A slab
 * is centred on a deterministic 8192-block band.  The slab is a persistence
 * namespace as well as a coordinate frame; crossing it creates/selects a
 * different Voxy {@link WorldIdentifier}.</p>
 */
public final class AllvrVoxyYSlab {

    public static final int L0_BLOCKS = 32;
    public static final int L0_CELLS_PER_SLAB = 256;
    public static final int BLOCKS_PER_SLAB = L0_BLOCKS * L0_CELLS_PER_SLAB;
    public static final int SECTION_BLOCKS = 16;
    public static final int SECTIONS_PER_SLAB = BLOCKS_PER_SLAB / SECTION_BLOCKS;
    public static final int SECTION_CENTER_OFFSET = SECTIONS_PER_SLAB / 2;
    public static final int MIN_VIRTUAL_SECTION_Y = -256;
    public static final int MAX_VIRTUAL_SECTION_Y = 255;

    private AllvrVoxyYSlab() {}

    public static long slabIdForBlockY(int blockY) {
        return Math.floorDiv(blockY, BLOCKS_PER_SLAB);
    }

    public static long slabIdForLevel(Level level) {
        if (level == null) return 0L;
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.level == level) {
            return slabIdForBlockY(mc.player.blockPosition().getY());
        }
        return slabIdForBlockY(level.getMinBuildHeight());
    }

    public static int slabCenterBlockY(long slabId) {
        long center = slabId * (long) BLOCKS_PER_SLAB + BLOCKS_PER_SLAB / 2L;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, center));
    }

    /** Virtual vanilla section Y passed to Voxy rawIngest. */
    public static int virtualSectionY(long slabId, int absoluteSectionY) {
        long base = slabId * (long) SECTIONS_PER_SLAB;
        long value = absoluteSectionY - base - SECTION_CENTER_OFFSET;
        return (int) value;
    }

    /** Virtual Voxy L0+ cell Y for an absolute level-L cell. */
    public static int virtualCellY(long slabId, int level, int absoluteCellY) {
        int scale = L0_BLOCKS << level;
        int centerCell = Math.floorDiv(slabCenterBlockY(slabId), scale);
        return absoluteCellY - centerCell;
    }

    public static double virtualCameraY(long slabId, double absoluteCameraY) {
        return absoluteCameraY - slabCenterBlockY(slabId);
    }

    public static boolean containsSectionY(long slabId, int absoluteSectionY) {
        int virtual = virtualSectionY(slabId, absoluteSectionY);
        return virtual >= MIN_VIRTUAL_SECTION_Y && virtual <= MAX_VIRTUAL_SECTION_Y;
    }

    /**
     * Creates the identifier used by Voxy's active-world map and storage
     * path.  The real Minecraft seed remains untouched; the marker only
     * separates Allay Y slabs in Voxy's client namespace.
     */
    public static WorldIdentifier withSlab(WorldIdentifier base, long slabId) {
        if (base == null || !isAllay(base.key)) return base;
        long marker = WorldIdentifier.mixStafford13(slabId ^ 0x41564F58595F534CL);
        return new WorldIdentifier(base.key, base.biomeSeed ^ marker, base.dimension);
    }

    private static boolean isAllay(ResourceKey<Level> key) {
        return com.iridium126.createmanaindustry.dimension.AllvrDimensions.ALLAY_LEVEL.equals(key);
    }
}
