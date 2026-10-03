package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOutputHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BrassTunnelBlockEntity.class)
public abstract class PackageTunnelOutputMixin {
    @WrapOperation(method="insertIntoTunnel",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean cmi$output(Level world,Entity item,Operation<Boolean> original,
            @Local(argsOnly=true) BrassTunnelBlockEntity tunnel,@Local(argsOnly=true) Direction side) {
        return original.call(world,PackageOutputHooks.prepare(world,item,tunnel.getBlockPos(),side));
    }
}
