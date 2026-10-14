package com.iridium126.createmanaindustry.compat.trickster.trick;

import com.iridium126.createmanaindustry.compat.trickster.blunder.InvalidKineticStressBlunder;
import com.iridium126.createmanaindustry.compat.trickster.blunder.InvalidKineticTargetBlunder;
import com.iridium126.createmanaindustry.compat.trickster.mana.TricksterManaAccess;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.type.Signature;
import net.minecraft.core.BlockPos;

/** Supplies temporary kinetic stress to a Create kinetic block entity. */
public final class TemporaryKineticStressTrick extends Trick<TemporaryKineticStressTrick> {
    private static final float STRESS_PER_SPEED = 4;

    public TemporaryKineticStressTrick() {
        super(Pattern.of(1, 0, 2, 3, 4, 5, 6, 8, 7),
                Signature.of(FragmentType.VECTOR, FragmentType.NUMBER, FragmentType.NUMBER,
                        TemporaryKineticStressTrick::run, FragmentType.VECTOR));
    }

    public VectorFragment run(SpellContext ctx, VectorFragment target, NumberFragment speed, NumberFragment duration) {
        double speedInput = speed.number();
        float stressMagnitude = (float) Math.abs(speedInput) * STRESS_PER_SPEED;
        int durationTicks = (int) Math.floor(duration.number());
        if (stressMagnitude <= 0 || durationTicks <= 0) {
            throw new InvalidKineticStressBlunder(stressMagnitude, durationTicks);
        }

        BlockPos pos = target.toBlockPos();
        if (!(ctx.source().getWorld().getBlockEntity(pos) instanceof KineticBlockEntity kinetic)) {
            throw new InvalidKineticTargetBlunder(pos);
        }

        double manaCost = ServerConfig.manaPerStress * stressMagnitude * durationTicks
                * ServerConfig.kineticStressTrickManaMultiplier;
        TricksterManaAccess.useTraditionalMana(ctx, manaCost);
        float kineticSpeed = (float) speedInput;
        TemporaryKinetics.apply(kinetic, kineticSpeed < 0 ? -stressMagnitude : stressMagnitude, kineticSpeed, durationTicks);
        return target;
    }
}
