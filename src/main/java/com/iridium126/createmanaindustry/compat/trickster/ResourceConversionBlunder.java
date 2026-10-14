package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
public final class ResourceConversionBlunder extends WrappableBlunder {
    private final String reason;

    public ResourceConversionBlunder(String reason) { this.reason = reason; }

    @Override
    public MutableComponent createMessage(@Nullable TrickContext context) {
        return Component.translatable("createmanaindustry.conversion.error." + reason);
    }
}
