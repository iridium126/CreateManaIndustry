package com.iridium126.createmanaindustry.mixin.packages;

import java.util.List;
import java.util.Map;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainRenderAccess;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRenderer;
import net.minecraft.core.BlockPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve wheel, shaft and chain rendering; replace only package membership. */
@Mixin(ChainConveyorRenderer.class)
public abstract class ChainRendererOwnershipMixin {
    @Redirect(method="renderSafe(Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at=@At(value="FIELD",opcode=Opcodes.GETFIELD,target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;loopingPackages:Ljava/util/List;"))
    private List<ChainConveyorPackage> cmi$loop(ChainConveyorBlockEntity be) {
        var snapshot=((PackageChainRenderAccess)be).cmi$renderPackages();return snapshot==null?be.getLoopingPackages():snapshot.loop();
    }
    @Redirect(method="renderSafe(Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at=@At(value="FIELD",opcode=Opcodes.GETFIELD,target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;travellingPackages:Ljava/util/Map;"))
    private Map<BlockPos,List<ChainConveyorPackage>> cmi$travel(ChainConveyorBlockEntity be) {
        var snapshot=((PackageChainRenderAccess)be).cmi$renderPackages();return snapshot==null?be.getTravellingPackages():snapshot.travel();
    }
}
