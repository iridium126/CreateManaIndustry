package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.ImageComponentType;
import at.petrak.hexcasting.common.lib.HexRegistries;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChainSnapshot;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** CMI-owned state in Hexcasting's persistent casting images. */
public final class CMIHexImageComponents {
    private static final DeferredRegister<ImageComponentType<?>> TYPES =
            DeferredRegister.create(HexRegistries.IMAGE_COMPONENT, CreateManaIndustry.MODID);

    // Keep the previous userData counter lifetime, including persisted staff/circle images.
    public static final DeferredHolder<ImageComponentType<?>, ImageComponentType<CompoundTag>> TICK_DATA =
            TYPES.register("tick_data", () -> new ImageComponentType<>(CompoundTag.CODEC, ByteBufCodecs.COMPOUND_TAG, false));
    // Chain identity and shared-fate state must survive staff patterns and circle jumps.
    public static final DeferredHolder<ImageComponentType<?>, ImageComponentType<CastChainSnapshot>> CAST_CHAIN =
            TYPES.register("cast_chain", () -> new ImageComponentType<>(CastChainSnapshot.CODEC, CastChainSnapshot.STREAM_CODEC, false));

    private CMIHexImageComponents() {}

    public static void register(IEventBus bus) { TYPES.register(bus); }

    /** Reading absent state does not attach mutable data to the input image. */
    public static CompoundTag tickData(CastingImage image) {
        CompoundTag data = image.getComponent(TICK_DATA.get());
        return data == null ? new CompoundTag() : data;
    }

    public static CastingImage withTickData(CastingImage image, CompoundTag data) {
        return image.withComponent(TICK_DATA.get(), data);
    }

    /** Reuse the component map when the optimized Tick already owns its mutable counter. */
    public static Map<ImageComponentType<?>, Object> componentsWithTickData(CastingImage image, CompoundTag data) {
        var type = TICK_DATA.get();
        if (image.getComponent(type) == data) return image.getComponents();
        Map<ImageComponentType<?>, Object> components = new HashMap<>(image.getComponents());
        components.put(type, data);
        return components;
    }

}
