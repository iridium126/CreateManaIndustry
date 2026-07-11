package com.iridium126.createmanaindustry.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.infrastructure.config.CStress;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

public class CMIStress extends CStress {

    private static final Object2DoubleMap<ResourceLocation> DEFAULT_IMPACTS = new Object2DoubleOpenHashMap<>();
    private static final Object2DoubleMap<ResourceLocation> DEFAULT_CAPACITIES = new Object2DoubleOpenHashMap<>();

    /** The singleton instance — populated during config registration. */
    public static final CMIStress INSTANCE = new CMIStress();

    private CMIStress() {}

    // ---- config registration -----------------------------------------------

    @Override
    public void registerAll(ModConfigSpec.Builder builder) {
        builder.comment(".", Comments.su, Comments.impact).push("impact");
        DEFAULT_IMPACTS.forEach((id, value) -> this.impacts.put(id, builder.define(id.getPath(), value)));
        builder.pop();

        builder.comment(".", Comments.su, Comments.capacity).push("capacity");
        DEFAULT_CAPACITIES.forEach((id, value) -> this.capacities.put(id, builder.define(id.getPath(), value)));
        builder.pop();
    }

    @Override
    public String getName() {
        return "cmiStressValues";
    }

    // ---- static helpers for block registration -----------------------------

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setNoImpact() {
        return setImpact(0.0);
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setImpact(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_IMPACTS.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setCapacity(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_CAPACITIES.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    // ---- validation --------------------------------------------------------

    private static void assertFromCMI(BlockBuilder<?, ?> builder) {
        if (!builder.getOwner().getModid().equals(CreateManaIndustry.MODID)) {
            throw new IllegalStateException(
                    "Non-" + CreateManaIndustry.MODID + " blocks cannot be added to CMI's config.");
        }
    }

    // ---- comments ----------------------------------------------------------

    private static class Comments {
        static String su = "[in Stress Units]";
        static String impact =
                "Configure the individual stress impact of mechanical blocks. Note that this cost is doubled for every speed increase it receives.";
        static String capacity = "Configure how much stress a source can accommodate for.";
    }
}
