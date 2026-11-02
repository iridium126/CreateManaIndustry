package com.iridium126.createmanaindustry.infrastructure.config;

/** Independent depth strategies; ordinal values are never part of the GPU ABI. */
public enum ParticleShadowQuality implements net.neoforged.neoforge.common.TranslatableEnum {
    FULL, COARSE, FRUSTUM, SHADOW_HIZ, SCREEN_HIZ, COMBINED_HIZ, OFF;

    public boolean castsShadows() { return this != OFF; }
    public boolean receivesShadows() { return this == FULL; }
    public boolean groupsStorm() { return this != FULL && this != OFF; }
    public boolean projectsFrustum() { return groupsStorm() && this != COARSE; }
    public boolean shadowDepth() { return this == SHADOW_HIZ || this == COMBINED_HIZ; }
    public boolean screenDepth() { return this == SCREEN_HIZ || this == COMBINED_HIZ; }
    public String label() { return this == COMBINED_HIZ ? "SHADOW_HIZ+SCREEN_HIZ" : name(); }
    @Override public net.minecraft.network.chat.Component getTranslatedName(){
        return net.minecraft.network.chat.Component.translatable("createmanaindustry.configuration.shadowQuality."+name());
    }
}
