package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotInventory;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Immutable call-site geometry. Player identity does not determine its world. */
public record CastingOrigin(CircleSourceDescriptor source, Direction facing,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ, UUID player) {
    private static final Gson JSON = new GsonBuilder().create();

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

    public Vec3 position() { return Vec3.atCenterOf(source.anchor().pos()); }
    public BlockPos min() { return new BlockPos(minX, minY, minZ); }
    public BlockPos max() { return new BlockPos(maxX, maxY, maxZ); }
    public ServerPlayer owner(MinecraftServer server) { return player == null ? null : server.getPlayerList().getPlayer(player); }
    public String encode() { return JSON.toJson(this); }

    public static CastingOrigin decode(String encoded) {
        if (encoded.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Origin too large");
        CastingOrigin origin = JSON.fromJson(encoded, CastingOrigin.class);
        if (origin == null || origin.source == null || origin.facing == null || origin.source.impetus() == null
                || origin.source.anchor() == null || origin.minX > origin.maxX || origin.minY > origin.maxY
                || origin.minZ > origin.maxZ) throw new IllegalArgumentException("Invalid casting origin");
        return origin;
    }
}
