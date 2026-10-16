package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** These call sites use distance exclusively for the existing 16-block reach check. */
@Mixin(targets = {
    "dev.enjarai.trickster.spell.trick.basic.ReadSpellTrick",
    "dev.enjarai.trickster.spell.trick.basic.WriteSpellTrick",
    "dev.enjarai.trickster.spell.trick.basic.ClearSpellTrick",
    "dev.enjarai.trickster.spell.trick.misc.MessageSendTrick",
    "dev.enjarai.trickster.spell.trick.misc.MessageListenTrick",
    "dev.enjarai.trickster.spell.trick.mana.ConvertManaTrick"
}, remap = false)
public abstract class TricksterInvertedRangeMixin {
    @WrapOperation(method = "*", require = 1,
            at = @At(value = "INVOKE", target = "Ldev/enjarai/trickster/spell/execution/source/SpellSource;distanceTo(Lorg/joml/Vector3dc;)D"))
    private static double cmi$range(SpellSource source, Vector3dc target, Operation<Double> original) {
        double distance = original.call(source, target);
        if(com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.crosses(source.getWorld(),com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees.vec(source.getPos()),com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees.vec(target)))distance=Double.POSITIVE_INFINITY;
        return source.getPlayer().map(p -> CastChains.invertedFor(p.getUUID())).orElse(false)
                ? (distance <= 16 ? Math.nextUp(16D) : 16D) : distance;
    }
}
