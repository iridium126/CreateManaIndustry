package com.iridium126.createmanaindustry.content.kinetics.bnb;

import org.jetbrains.annotations.Nullable;

import com.cake.azimuth.behaviour.SuperBlockEntityBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Direct-type accessors for the BnB cogwheel-chain API.
 */
public final class BnBCompat {

    private BnBCompat() {}

    @Nullable
    private static CogwheelChainBehaviour getBehaviour(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null)
            return null;
        return SuperBlockEntityBehaviour.getOptional(level,
                be.getBlockPos(), CogwheelChainBehaviour.TYPE).orElse(null);
    }

    public static boolean isChainBE(BlockEntity be) {
        return getBehaviour(be) != null;
    }

    /**
     * Returns the controlled chain if {@code be} is a chain controller,
     * or {@code null} otherwise.
     */
    @Nullable
    public static CogwheelChain getChainIfController(BlockEntity be) {
        CogwheelChainBehaviour behaviour = getBehaviour(be);
        if (behaviour != null && behaviour.isController())
            return behaviour.getControlledChain();
        return null;
    }
}
