package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.MishapOthersName;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import java.util.ArrayDeque;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "at.petrak.hexcasting.api.casting.mishaps.MishapOthersName$Companion", remap = false)
public abstract class TrueNameExemptionMixin {
    @Inject(method = "getTrueNameMishapFromDatum", at = @At("HEAD"), cancellable = true)
    private void cmi$scan(ServerLevel level, Iota datum, Player caster, CallbackInfoReturnable<MishapOthersName> cir) {
        var pending = new ArrayDeque<Iota>(); pending.add(datum);
        while (!pending.isEmpty()) {
            Iota next = pending.removeFirst();
            if (next instanceof EntityIota entity) {
                if (CastChains.nameExempt(level.getServer(), entity.getEntityId())) continue;
                var resolved = entity.getEntity(level);
                if (resolved == null && entity.isPlayer()) { cir.setReturnValue(new MishapOthersName(null)); return; }
                if (resolved instanceof Player player && player != caster) { cir.setReturnValue(new MishapOthersName(player)); return; }
            }
            Iterable<Iota> children = next.subIotas();
            if (children != null) children.forEach(pending::addLast);
        }
        cir.setReturnValue(null);
    }
}
