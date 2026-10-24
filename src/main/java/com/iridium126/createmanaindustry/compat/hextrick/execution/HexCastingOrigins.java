package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotInventory;
import net.minecraft.server.level.ServerPlayer;

/** Hexcasting-only capture logic kept out of the shared origin value type. */
public final class HexCastingOrigins {
    private HexCastingOrigins() {}
    public static CastingOrigin capture(CircleCastEnv environment) {
        var state = environment.circleState();
        var world = environment.getWorld();
        var inventory = SlateKnotInventory.forCircle(world, state.reachedPositions);
        var slots = inventory.positions().stream()
                .map(pos -> CircleSourceDescriptor.BlockReference.capture(world.getBlockEntity(pos))).toList();
        var descriptor = new CircleSourceDescriptor(world.dimension().location(),
                CircleSourceDescriptor.BlockReference.capture(environment.getImpetus()),
                CircleSourceDescriptor.BlockReference.capture(world.getBlockEntity(state.currentPos)), slots);
        var bounds = state.bounds;
        return new CastingOrigin(descriptor, state.enteredFrom.getOpposite(),
                bounds.min().getX(), bounds.min().getY(), bounds.min().getZ(),
                bounds.max().getX(), bounds.max().getY(), bounds.max().getZ(),
                environment.getCastingEntity() instanceof ServerPlayer caster ? caster.getUUID() : null);
    }

}
