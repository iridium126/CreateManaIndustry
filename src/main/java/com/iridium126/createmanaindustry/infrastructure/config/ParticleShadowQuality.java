package com.iridium126.createmanaindustry.infrastructure.config;

/** Particle casting policy. Receiving shadows is a separate setting. */
public enum ParticleShadowQuality implements net.neoforged.neoforge.common.TranslatableEnum {
    FULL, COARSE, RT, OFF;

    public boolean castsShadows() { return this != OFF; }
    public boolean groupsStorm() { return this == COARSE; }
    public String label() { return name(); }
    @Override public net.minecraft.network.chat.Component getTranslatedName(){
        return net.minecraft.network.chat.Component.translatable("createmanaindustry.configuration.shadowQuality."+name());
    }
}
