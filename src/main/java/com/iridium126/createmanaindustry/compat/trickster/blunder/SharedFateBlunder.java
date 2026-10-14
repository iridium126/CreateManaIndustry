package com.iridium126.createmanaindustry.compat.trickster.blunder;

import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

@SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
public final class SharedFateBlunder extends WrappableBlunder {
    private final String reason;
    public SharedFateBlunder(String reason) { this.reason = reason; }
    public MutableComponent createMessage(TrickContext context) {
        return Component.translatable("createmanaindustry.shared_fate.error." + reason);
    }
}
