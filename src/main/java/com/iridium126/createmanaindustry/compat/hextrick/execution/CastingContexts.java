package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Explicit origin policy shared by great tricks and their persistent effects. */
public final class CastingContexts {
    private CastingContexts() {}
    public static CastingOrigin origin() {
        var context = BridgeExecutionContext.current();
        return context == null ? null : context.origin();
    }
    public static ServerLevel world(ServerPlayer owner, CastingOrigin origin) {
        return origin == null ? owner.serverLevel() : origin.source().resolve(owner.getServer()).level();
    }
    public static CastingEnvironment environment(ServerPlayer owner, CastingOrigin origin) {
        return origin == null ? new StaffCastEnv(owner, InteractionHand.MAIN_HAND)
                : new AnchoredCircleCastEnv(world(owner, origin), origin);
    }
}
