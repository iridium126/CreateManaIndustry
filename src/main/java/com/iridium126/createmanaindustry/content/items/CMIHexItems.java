package com.iridium126.createmanaindustry.content.items;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.Config;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.resources.ResourceLocation;

/**
 * Conditional registration of incomplete hexcasting items.
 * <p>
 * All items in this class reference Hexcasting API types and are only registered
 * when {@link CreateManaIndustry#HEX_ACTIVE} is {@code true}.
 */
public final class CMIHexItems {

    public static ItemEntry<IncompleteHexItem> INCOMPLETE_CYPHER;
    public static ItemEntry<IncompleteHexItem> INCOMPLETE_TRINKET;
    public static ItemEntry<IncompleteHexItem> INCOMPLETE_ARTIFACT;

    private CMIHexItems() {}

    public static void register() {
        if (!CreateManaIndustry.HEX_ACTIVE)
            return;

        INCOMPLETE_CYPHER = REGISTRATE.item("incomplete_cypher",
                        p -> new IncompleteHexItem(p, () -> Config.cypherMaxMedia, hexLoc("cypher")))
                .model(NonNullBiConsumer.noop())
                .register();

        INCOMPLETE_TRINKET = REGISTRATE.item("incomplete_trinket",
                        p -> new IncompleteHexItem(p, () -> Config.trinketMaxMedia, hexLoc("trinket")))
                .model(NonNullBiConsumer.noop())
                .register();

        INCOMPLETE_ARTIFACT = REGISTRATE.item("incomplete_artifact",
                        p -> new IncompleteHexItem(p, () -> Config.artifactMaxMedia, hexLoc("artifact")))
                .model(NonNullBiConsumer.noop())
                .register();
    }

    private static ResourceLocation hexLoc(String path) {
        return ResourceLocation.fromNamespaceAndPath("hexcasting", path);
    }
}
