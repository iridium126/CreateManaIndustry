package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class PotionBlunder extends WrappableBlunder {
    private static final long serialVersionUID = 1L;
    private final String reason;
    public PotionBlunder(String reason) { this.reason = reason; }
    public MutableComponent createMessage(TrickContext context) {
        return Component.translatable("createmanaindustry.tincture_shaping.error." + reason);
    }
}
