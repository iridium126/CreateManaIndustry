package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingRanges;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.fragment.storage.SlotFragment;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="dev.enjarai.trickster.spell.trick.mana.TriggerConverterTrick",remap=false)
public abstract class ConverterInvertedRangeMixin {
    @WrapOperation(method="trigger",at=@At(value="INVOKE",target="Lorg/joml/Vector3d;distance(Lorg/joml/Vector3dc;)D"))
    private double cmi$range(Vector3d from,Vector3dc to,Operation<Double> original,
            SpellContext ctx,SlotFragment slot) {
        return CastingRanges.reach(
                ctx.source(), from, to, original.call(from, to));
    }
}
