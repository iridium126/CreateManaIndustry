package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

public class InvalidKineticStressBlunder extends WrappableBlunder {
    private final float stressMagnitude;
    private final int durationTicks;

    public InvalidKineticStressBlunder(float stressMagnitude, int durationTicks) {
        this.stressMagnitude = stressMagnitude;
        this.durationTicks = durationTicks;
    }

    @Override
    public MutableComponent createMessage(@Nullable TrickContext ctx) {
        return Component.empty()
                .append("Invalid kinetic stress parameters: stress=")
                .append(String.format("%.1f", stressMagnitude))
                .append(", duration=")
                .append(Integer.toString(durationTicks));
    }
}
