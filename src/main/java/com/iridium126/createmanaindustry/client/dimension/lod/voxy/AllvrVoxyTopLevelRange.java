package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

/**
 * Constants for the allay-dimension top-level Y range patch (voxy
 * integration plan §7.5-2). Voxy derives the L4 top-level keys from
 * {@code getMinSection() >> 5 .. (getMaxSection()-1) >> 5}; for the allay
 * dimension the getters are remapped so that derived range tiles the L0
 * hard window {@code [-128, 127]} exactly: 16 L4 keys, {@code [-8, 7]}.
 */
public final class AllvrVoxyTopLevelRange {

    /** What {@code getMinSection()} must return so {@code >> 5} gives -8. */
    public static final int TOP_LEVEL_MIN_SECTION_Y = -8 << 5;
    /** What {@code getMaxSection()} must return so {@code (v-1) >> 5} gives 7. */
    public static final int TOP_LEVEL_MAX_SECTION_EXCLUSIVE = (7 + 1) << 5;

    private AllvrVoxyTopLevelRange() {}
}
