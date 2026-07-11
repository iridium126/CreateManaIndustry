package com.iridium126.createmanaindustry.content.kinetics.bnb;

import org.jetbrains.annotations.Nullable;

import com.cake.azimuth.behaviour.SuperBlockEntityBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Direct-type accessors for the BnB cogwheel-chain API.
 * <p>
 * All BnB-specific methods degrade gracefully via
 * {@link NoClassDefFoundError} guarding so that non-BnB callers
 * (e.g. {@code KineticsSpellCoreItemMixin}) work without BnB installed.
 */
public final class BnBCompat {

    private BnBCompat() {}

    @Nullable
    private static CogwheelChainBehaviour getBehaviour(Object be) {
        if (!(be instanceof BlockEntity blockEntity))
            return null;
        Level level = blockEntity.getLevel();
        if (level == null)
            return null;
        try {
            return SuperBlockEntityBehaviour.getOptional(level,
                    blockEntity.getBlockPos(),
                    CogwheelChainBehaviour.TYPE).orElse(null);
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    // ---- single lookup -----------------------------------------------------

    public static boolean isChainBE(Object be) {
        return getBehaviour(be) != null;
    }

    /**
     * Returns the controlled chain if {@code be} is a chain controller,
     * or {@code null} otherwise. Combines {@code isChainBE + isController
     * + getChain} into a single behaviour lookup.
     */
    @Nullable
    public static CogwheelChain getChainIfController(Object be) {
        try {
            CogwheelChainBehaviour behaviour = getBehaviour(be);
            if (behaviour != null && behaviour.isController())
                return behaviour.getControlledChain();
        } catch (NoClassDefFoundError ignored) {
        }
        return null;
    }
}
