package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the Flywheel instance allocated so handback can restore it next frame. */
@Mixin(value=PackageVisual.class,remap=false)
public abstract class PackageVisualOwnershipMixin extends AbstractEntityVisual<PackageEntity> {
    @Shadow @Final public TransformedInstance instance=null;
    protected PackageVisualOwnershipMixin(VisualizationContext context,PackageEntity entity,float partialTick) {
        super(context,entity,partialTick);
    }
    @Inject(method="animate",at=@At("HEAD"),cancellable=true,remap=false)
    private void cmi$gpuOwned(float partialTick,CallbackInfo ci) {
        if(!PackageRenderOwnership.renderedByGpu(entity))return;
        instance.setZeroTransform().setChanged();
        ci.cancel();
    }
}
