package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSpellSource;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The player's offhand remains the item source; distance belongs to the circle. */
@Mixin(targets = "dev.enjarai.trickster.spell.trick.entity.StoreEntityTrick", remap = false)
public abstract class StoreEntityOriginMixin {
    @WrapOperation(method = "store", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 cmi$origin(ServerPlayer player, Operation<Vec3> original, SpellContext context, EntityFragment entity) {
        return context.source() instanceof CircleSpellSource source ? source.origin().position() : original.call(player);
    }
}
