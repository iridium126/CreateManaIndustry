package com.iridium126.createmanaindustry.mixin.packages;

import java.util.List;
import java.util.Map;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainRenderAccess;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorVisual;
import net.minecraft.core.BlockPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Flywheel can traverse this immutable membership on a worker. Native SmartRecycler still
 * resets counts and discards excess instances, so hidden packages leave no stale instances. */
@Mixin(ChainConveyorVisual.class)
public abstract class ChainVisualOwnershipMixin {
    @Redirect(method="beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at=@At(value="FIELD",opcode=Opcodes.GETFIELD,target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;loopingPackages:Ljava/util/List;"))
    private List<ChainConveyorPackage> cmi$loop(ChainConveyorBlockEntity be) {
        var snapshot=((PackageChainRenderAccess)be).cmi$renderPackages();return snapshot==null?be.getLoopingPackages():snapshot.loop();
    }
    @Redirect(method="beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at=@At(value="FIELD",opcode=Opcodes.GETFIELD,target="Lcom/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;travellingPackages:Ljava/util/Map;"))
    private Map<BlockPos,List<ChainConveyorPackage>> cmi$travel(ChainConveyorBlockEntity be) {
        var snapshot=((PackageChainRenderAccess)be).cmi$renderPackages();return snapshot==null?be.getTravellingPackages():snapshot.travel();
    }
}
