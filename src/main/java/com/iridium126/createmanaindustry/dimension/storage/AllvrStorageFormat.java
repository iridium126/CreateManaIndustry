package com.iridium126.createmanaindustry.dimension.storage;

/**
 * On-disk constants of the ALLVR {@code region3d/} format — the single place
 * that pins the file layout (plan §5). Pure Java: unit-testable without a
 * Minecraft bootstrap.
 * <p>
 * File layout ({@code r.<rx>.<ry>.<rz>.3dr}, fixed 4096 B sectors):
 * <pre>
 *   sectors 0..23          header copy A
 *   sectors 24..47         header copy B (shadow of A)
 *   sectors 48..           payload sectors, allocated by the free list
 * </pre>
 * Each header holds the region identity, an incrementing generation, the
 * 4096 slot table and a trailing CRC32C over the whole header image. A batch
 * commit writes new payloads and rewrites the <b>inactive</b> header copy
 * with a bumped generation; channel forcing is deferred to flush/close so
 * background saves do not pay an fsync per batch, while the shadow header
 * still gives crash recovery a valid old/new image (plan §5.3).
 */
public final class AllvrStorageFormat {

    /** "ALLVR3DR" — marker of a region3d file. */
    public static final long MAGIC = 0x414C4C5652334452L;

    /** Bumped only by incompatible region-file layout changes (see AllvrCubeDataFixes for the cube NBT schema version). */
    public static final int REGION_FORMAT_VERSION = 1;

    /** Fixed sector size of every offset in the format (vanilla RegionFile uses the same value). */
    public static final int SECTOR_BYTES = 4096;

    /** Cubes per region axis (16³ cubes = 4096 slots = 512 blocks per axis). */
    public static final int REGION_DIAMETER_CUBES = 16;

    /** Slot table size: 16³ entries, one per cube in the region. */
    public static final int SLOTS = REGION_DIAMETER_CUBES * REGION_DIAMETER_CUBES * REGION_DIAMETER_CUBES;

    /** Per-slot header entry: sectorOffset(int), sectorCount(int), compressedLength(int), CRC32C(long). */
    public static final int SLOT_ENTRY_BYTES = 4 + 4 + 4 + 8;

    /** Sectors reserved per header copy (96 KiB; usable header content is ~82 KiB). */
    public static final int HEADER_SECTORS = 24;

    /** First sector that may carry cube payloads. */
    public static final int PAYLOAD_START_SECTOR = HEADER_SECTORS * 2;

    /** CRC32C (Castagnoli, hardware-accelerated) of the whole payload record. */
    public static final int CRC_LENGTH = 8; // long stored per slot entry

    private AllvrStorageFormat() {}
}
