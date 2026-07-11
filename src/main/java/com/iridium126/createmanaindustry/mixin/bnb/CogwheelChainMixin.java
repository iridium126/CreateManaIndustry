package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainMixin {

    @Shadow
    private List<PathedCogwheelNode> cogwheelNodes;

    @Inject(method = "checkIntegrity", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$checkKineticsCoreNodes(Level level, BlockPos origin,
            CallbackInfoReturnable<Boolean> cir) {
        if (!containsKineticsCoreNode(level, origin))
            return;

        for (PathedCogwheelNode node : cogwheelNodes) {
            BlockPos pos = origin.offset(node.localPos());
            if (!level.isLoaded(pos))
                continue;

            if (BnBKineticsCoreNodes.isModularSpellConstruct(level, pos)) {
                if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
                    cir.setReturnValue(false);
                    return;
                }
                continue;
            }

            BlockState state = level.getBlockState(pos);
            CogwheelChainCandidate candidate = CogwheelChainCandidate.getForBlock(state);
            if (candidate == null || !candidate.isConsistentWithNode(node)) {
                cir.setReturnValue(false);
                return;
            }
        }

        cir.setReturnValue(true);
    }

    private boolean containsKineticsCoreNode(Level level, BlockPos origin) {
        for (PathedCogwheelNode node : cogwheelNodes) {
            BlockPos pos = origin.offset(node.localPos());
            if (level.isLoaded(pos) && BnBKineticsCoreNodes.isModularSpellConstruct(level, pos))
                return true;
        }
        return false;
    }
}
