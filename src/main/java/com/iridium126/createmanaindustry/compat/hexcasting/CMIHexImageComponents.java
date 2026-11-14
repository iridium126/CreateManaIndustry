package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.vm.ImageComponentType;
import at.petrak.hexcasting.common.lib.HexRegistries;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChainSnapshot;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** CMI-owned state in Hexcasting's persistent casting images. */
public final class CMIHexImageComponents {
    private static final DeferredRegister<ImageComponentType<?>> TYPES =
            DeferredRegister.create(HexRegistries.IMAGE_COMPONENT, CreateManaIndustry.MODID);

    // Chain identity and shared-fate state must survive staff patterns and circle jumps.
    public static final DeferredHolder<ImageComponentType<?>, ImageComponentType<CastChainSnapshot>> CAST_CHAIN =
            TYPES.register("cast_chain", () -> new ImageComponentType<>(CastChainSnapshot.CODEC, CastChainSnapshot.STREAM_CODEC, false));

    private CMIHexImageComponents() {}

    public static void register(IEventBus bus) { TYPES.register(bus); }
}
