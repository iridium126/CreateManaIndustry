package com.iridium126.createmanaindustry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMICreativeModeTabs {
    private static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateManaIndustry.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = REGISTER.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.createmanaindustry"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> CMIBlocks.KINETIC_MANA_GENERATOR.asStack())
                    .build());

    private CMICreativeModeTabs() {}

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }
}
