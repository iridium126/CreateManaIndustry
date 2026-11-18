package com.iridium126.createmanaindustry.infrastructure.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

public enum StormSimulationBackend implements TranslatableEnum {
    AUTO("AUTO"), DXR("DXR"), OPTIX("OptiX"), OPENGL("OpenGL");

    private final String label;

    StormSimulationBackend(String label) {
        this.label = label;
    }

    @Override public Component getTranslatedName() {
        return Component.literal(label);
    }
}
