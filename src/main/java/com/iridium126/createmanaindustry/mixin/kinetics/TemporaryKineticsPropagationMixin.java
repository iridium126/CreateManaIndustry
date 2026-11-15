package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

@Mixin(value = RotationPropagator.class, remap = false)
public abstract class TemporaryKineticsPropagationMixin {
    @ModifyReturnValue(method = "findConnectedNeighbour", at = @At("RETURN"))
    private static KineticBlockEntity createmanaindustry$waitForRestoredNeighbour(KineticBlockEntity neighbour) {
        // Its saved RPM belongs to the last session until the entity has reconciled the effect.
        return neighbour != null && TemporaryKinetics.isRestoring(neighbour) ? null : neighbour;
    }

    @WrapOperation(method = {"getAxisModifier", "propagateMissingSource"}, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;isSource()Z"))
    private static boolean createmanaindustry$recognizeTemporarySource(KineticBlockEntity be, Operation<Boolean> original) {
        return TemporaryKinetics.isActive(be) || original.call(be);
    }
}
