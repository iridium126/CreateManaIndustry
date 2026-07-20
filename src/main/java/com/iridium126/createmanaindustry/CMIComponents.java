package com.iridium126.createmanaindustry;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Custom data components for CreateManaIndustry.
 * <p>
 * {@link #FILLED_MANA} tracks the cumulative Trickster mana already infused
 * into an incomplete knot item during the multi-step filling process.
 * Stored as a {@code float} in Trickster display-mana units (not mB).
 */
public final class CMIComponents {
    public static final DeferredRegister<DataComponentType<?>> REGISTER =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CreateManaIndustry.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> FILLED_MANA =
            REGISTER.register("filled_mana", () -> DataComponentType.<Float>builder()
                    .persistent(Codec.FLOAT)
                    .networkSynchronized(ByteBufCodecs.FLOAT)
                    .build());

    private CMIComponents() {}

    public static void register(net.neoforged.bus.api.IEventBus bus) {
        REGISTER.register(bus);
    }
}
