package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.CastChains;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="dev.enjarai.trickster.spell.trick.mana.TriggerConverterTrick",remap=false)
public abstract class ConverterInvertedRangeMixin {
    @WrapOperation(method="trigger",at=@At(value="INVOKE",target="Lorg/joml/Vector3d;distance(Lorg/joml/Vector3dc;)D"))
    private double cmi$range(Vector3d from,Vector3dc to,Operation<Double> original,
            dev.enjarai.trickster.spell.SpellContext ctx,dev.enjarai.trickster.spell.fragment.storage.SlotFragment slot) {
        double distance=original.call(from,to);
        return ctx.source().getPlayer().map(p -> CastChains.invertedFor(p.getUUID())).orElse(false)
                ? (distance<=16?Math.nextUp(16D):16D):distance;
    }
}
