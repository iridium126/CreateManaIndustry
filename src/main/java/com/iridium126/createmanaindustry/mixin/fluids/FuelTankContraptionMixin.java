package com.iridium126.createmanaindustry.mixin.fluids;

import java.util.Map;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankContraptionBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Contraption.class, remap = false)
public abstract class FuelTankContraptionMixin {
    @Shadow protected Map<BlockPos, CompoundTag> updateTags;
    @Shadow public boolean disassembled;

    @Inject(method = "captureMultiblock", at = @At("HEAD"), cancellable = true)
    private void cmi$captureFuel(BlockPos local, StructureBlockInfo info, BlockEntity be, CallbackInfo ci) {
        if (!(be instanceof FuelTankBlockEntity tank)) return;
        BlockPos controller = tank.getController().subtract(be.getBlockPos()).offset(local);
        info.nbt().put("Controller", NbtUtils.writeBlockPos(controller));
        CompoundTag update = updateTags.get(local);
        if (update != null) update.put("Controller", NbtUtils.writeBlockPos(controller));
        ci.cancel();
    }

    @WrapMethod(method = "addBlocksToWorld")
    private void cmi$placeFuel(Level level, StructureTransform transform, Operation<Void> original) {
        if (disassembled) { original.call(level, transform); return; }
        var placement = FuelTankContraptionBridge.begin((Contraption) (Object) this, level, transform);
        boolean success = false;
        try { original.call(level, transform); success = true; }
        finally { FuelTankContraptionBridge.end(placement, success); }
    }

    @WrapOperation(method = "addBlocksToWorld", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/StructureTransform;apply(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void cmi$recordFuel(StructureTransform transform, BlockEntity be, Operation<Void> original) {
        original.call(transform, be); FuelTankContraptionBridge.placed(be);
    }
}
