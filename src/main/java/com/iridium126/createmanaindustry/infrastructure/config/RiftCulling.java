package com.iridium126.createmanaindustry.infrastructure.config;
public enum RiftCulling implements net.neoforged.neoforge.common.TranslatableEnum {
    NONE, FRUSTUM, FRUSTUM_DEPTH;
    public net.minecraft.network.chat.Component getTranslatedName(){return net.minecraft.network.chat.Component.translatable("createmanaindustry.configuration.riftCulling."+name());}
}
