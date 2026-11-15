package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.network.chat.Component;

@Mixin(value = GoggleOverlayRenderer.class, remap = false)
public abstract class TemporaryKineticsGogglesMixin {
    @WrapOperation(method = "renderOverlay", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;addToGoggleTooltip(Ljava/util/List;Z)Z"))
    private static boolean createmanaindustry$showTemporaryCapacity(IHaveGoggleInformation target,
            List<Component> tooltip, boolean sneaking, Operation<Boolean> original) {
        boolean added = original.call(target, tooltip, sneaking);
        return target instanceof KineticBlockEntity be && TemporaryKinetics.addToGoggleTooltip(be, tooltip) || added;
    }
}
