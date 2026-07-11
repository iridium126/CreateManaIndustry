package com.iridium126.createmanaindustry.capability;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.neoforged.neoforge.capabilities.BlockCapability;

/**
 * Capability interface for blocks that can detect and interact with the mist
 * field produced by the Kinetic Atomizer. Future condenser-type blocks should
 * implement this interface and register it as a capability provider.
 * <p>
 * Usage from external code:
 * <pre>{@code
 *   ICondenser condenser = level.getCapability(ICondenser.CAPABILITY, pos, null);
 *   if (condenser != null) {
 *       float conc = condenser.getConcentration();
 *   }
 * }</pre>
 */
public interface ICondenser {
    BlockCapability<ICondenser, Void> CAPABILITY = BlockCapability.createVoid(
            CreateManaIndustry.modLoc("condenser"), ICondenser.class);

    /**
     * @return {@code true} if the mist concentration at this block's position is
     *         greater than zero.
     */
    boolean isInMist();

    /**
     * @return the current mist concentration at this block's position, or
     *         {@code 0.0f} if the block is not in any mist field.
     */
    float getConcentration();
}
