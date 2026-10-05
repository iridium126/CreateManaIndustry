package com.iridium126.createmanaindustry.compat.trickster;

import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class InvalidKineticTargetBlunder extends WrappableBlunder {
    private final BlockPos pos;

    public InvalidKineticTargetBlunder(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public MutableComponent createMessage(@Nullable TrickContext ctx) {
        return Component.empty()
                .append("Invalid kinetic target at ")
                .append(pos.toShortString());
    }
}
