package com.iridium126.createmanaindustry.content.items;

import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBCompat;
import com.iridium126.createmanaindustry.content.kinetics.bnb.ChainSpeedCache;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.enjarai.trickster.item.SpellCoreItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class KineticsSpellCoreItem extends SpellCoreItem {

    private static final int CHAIN_SEARCH_RADIUS = 8;

    public KineticsSpellCoreItem() {
        super();
    }

    @Override
    public int getExecutionLimit(ServerLevel world, Vec3 pos, int originalLimit) {
        float speed = getConnectedChainSpeed(world, BlockPos.containing(pos));
        if (speed == 0) {
            return 0;
        }
        return Math.max(1, (int) (speed / 32.0f * originalLimit));
    }

    /**
     * Factory method used by registrate. When Trickster is absent, falls back to
     * a plain {@link Item} so the mod can still load.
     */
    public static Item create(Item.Properties properties) {
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            return new KineticsSpellCoreItem();
        }
        return new Item(properties.stacksTo(4));
    }

    /** Returns {@code true} if the stack is a kinetics spell core item. */
    public static boolean is(ItemStack stack) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return false;
        return !stack.isEmpty() && stack.getItem() instanceof KineticsSpellCoreItem;
    }

    // ---- chain speed logic (was in KineticsSpellCoreItemMixin) -----------

    private static float getConnectedChainSpeed(Level level, BlockPos spellPos) {
        long gameTime = level.getGameTime();
        float cached = ChainSpeedCache.getCachedChainSpeed(spellPos, gameTime);
        if (cached >= 0)
            return cached;

        float speed = scanConnectedChainSpeed(level, spellPos);
        ChainSpeedCache.putCachedChainSpeed(spellPos, speed, gameTime);
        return speed;
    }

    private static float scanConnectedChainSpeed(Level level, BlockPos spellPos) {
        int r = CHAIN_SEARCH_RADIUS;
        for (BlockPos cp : BlockPos.betweenClosed(
                spellPos.offset(-r, -r, -r),
                spellPos.offset(r, r, r))) {
            var be = level.getBlockEntity(cp);
            CogwheelChain chain = BnBCompat.getChainIfController(be);
            if (chain == null)
                continue;

            List<PathedCogwheelNode> nodes = chain.getChainPathCogwheelNodes();
            BlockPos cPos = be.getBlockPos();

            for (PathedCogwheelNode node : nodes) {
                if (cPos.offset(node.localPos()).equals(spellPos))
                    return Math.abs(((KineticBlockEntity) be).getSpeed());
            }
        }
        return 0;
    }
}
