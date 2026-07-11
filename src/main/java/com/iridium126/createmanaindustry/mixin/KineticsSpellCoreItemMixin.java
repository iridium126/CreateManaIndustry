package com.iridium126.createmanaindustry.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.items.KineticsSpellCoreItem;
import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBCompat;
import com.iridium126.createmanaindustry.content.kinetics.bnb.ChainSpeedCache;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(targets = "dev.enjarai.trickster.item.SpellCoreItem", remap = false)
public abstract class KineticsSpellCoreItemMixin {

    private static final int CHAIN_SEARCH_RADIUS = 8;

    @Inject(method = "getExecutionLimit", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void createmanaindustry$speedBasedExecutionLimit(ServerLevel world,
            Vec3 pos, int originalLimit,
            CallbackInfoReturnable<Integer> cir) {
        Item item = (Item) (Object) this;
        if (!KineticsSpellCoreItem.is(item.getDefaultInstance()))
            return;

        float speed = getConnectedChainSpeed(world,
                BlockPos.containing(pos));
        if (speed == 0) {
            cir.setReturnValue(0);
            return;
        }
        cir.setReturnValue(Math.max(1,
                (int) (speed / 32.0f * originalLimit)));
    }

    private static float getConnectedChainSpeed(Level level,
            BlockPos spellPos) {
        long gameTime = level.getGameTime();
        float cached = ChainSpeedCache.getCachedChainSpeed(spellPos,
                gameTime);
        if (cached >= 0) return cached;

        float speed = scanConnectedChainSpeed(level, spellPos);
        ChainSpeedCache.putCachedChainSpeed(spellPos, speed, gameTime);
        return speed;
    }

    private static float scanConnectedChainSpeed(Level level,
            BlockPos spellPos) {
        int r = CHAIN_SEARCH_RADIUS;
        for (BlockPos cp : BlockPos.betweenClosed(
                spellPos.offset(-r, -r, -r),
                spellPos.offset(r, r, r))) {
            var be = level.getBlockEntity(cp);
            CogwheelChain chain = BnBCompat.getChainIfController(be);
            if (chain == null) continue;

            List<PathedCogwheelNode> nodes =
                    chain.getChainPathCogwheelNodes();
            BlockPos cPos = be.getBlockPos();

            for (PathedCogwheelNode node : nodes) {
                if (cPos.offset(node.localPos()).equals(spellPos))
                    return Math.abs(((KineticBlockEntity) be)
                            .getSpeed());
            }
        }
        return 0;
    }
}
