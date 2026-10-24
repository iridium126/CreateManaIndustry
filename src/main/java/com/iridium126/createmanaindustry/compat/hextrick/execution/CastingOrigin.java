package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    public CastingOrigin {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(facing, "facing");
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid casting bounds");
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
