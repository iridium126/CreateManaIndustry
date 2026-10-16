package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageCrushingAccess;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity$1")
public abstract class PackageCrushingInventoryMixin {
    @Shadow @Final private CrushingWheelControllerBlockEntity this$0;
    @Inject(method="isItemValid",at=@At("HEAD"),cancellable=true)
    private void cmi$recordOccupancy(int slot,ItemStack stack,CallbackInfoReturnable<Boolean> cir){
        if(((PackageCrushingAccess)this$0).cmi$hasPackage())cir.setReturnValue(false);
    }
}
