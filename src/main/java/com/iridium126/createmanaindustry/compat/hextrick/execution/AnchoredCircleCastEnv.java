package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.common.lib.HexAttributes;
import java.util.HashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Native circle rules, with an owner that may currently inhabit another world. */
public final class AnchoredCircleCastEnv extends CircleCastEnv {
    private final CastingOrigin origin;

    public AnchoredCircleCastEnv(ServerLevel world, CastingOrigin origin) {
        super(world, new CircleExecutionState(origin.source().impetus().pos(), origin.facing(),
                new HashSet<>(origin.source().slots().stream().map(CircleSourceDescriptor.BlockReference::pos).toList()),
                origin.source().anchor().pos(), origin.facing().getOpposite(), new CastingImage(), origin.player(),
                null, 0L, origin.max(), origin.min()) {});
        this.origin = origin;
    }

    public CastingOrigin origin() { return origin; }
    @Override public LivingEntity getCastingEntity() { return origin.owner(world.getServer()); }

    @Override
    public boolean isVecInRangeEnvironment(Vec3 position) {
        if (getCastingEntity() instanceof ServerPlayer player && player.serverLevel() == world) {
            if (position.distanceToSqr(player.position()) <= player.getBbHeight() * player.getBbHeight()) return true;
            var sentinel = HexAPI.instance().getSentinel(player);
            double radius = player.getAttributeValue(HexAttributes.SENTINEL_RADIUS);
            if (sentinel != null && sentinel.extendsRange() && sentinel.dimension().equals(world.dimension())
                    && position.distanceToSqr(sentinel.position()) <= radius * radius + 1e-11) return true;
        }
        return circleState().bounds.aabb().contains(position);
    }
}
