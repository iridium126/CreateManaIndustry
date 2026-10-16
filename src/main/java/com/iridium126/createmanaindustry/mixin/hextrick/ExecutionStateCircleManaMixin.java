package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps live slate-knot debits and refunds inside Trickster's transaction. */
@Mixin(value = ExecutionState.class, remap = false)
public abstract class ExecutionStateCircleManaMixin {
    @Shadow @Final private Optional<MutableManaPool> poolOverride;

    @Inject(method = "tryOverridePool", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$circleStorage(Storage<ManaVariant> fallback, Level world,
            CallbackInfoReturnable<Storage<ManaVariant>> callback) {
        if (poolOverride.orElse(null) instanceof CircleSlateManaPool circle) {
            callback.setReturnValue(circle.storage(world));
        }
    }
}
