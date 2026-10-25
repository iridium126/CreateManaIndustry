package com.iridium126.createmanaindustry.dimension;

import java.util.UUID;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server event wiring for the allay dimension. Registered from the mod
 * constructor on {@code NeoForge.EVENT_BUS}.
 * <ul>
 *   <li>{@code LevelTickEvent.Post} — drives cube generation + per-player
 *       streaming outside the central native chunk band. Vanilla owns the
 *       central chunks independently.</li>
 *   <li>logout / dimension change — drops the player's cube subscription so
 *       the stream restarts cleanly on re-entry.</li>
 * </ul>
 */
public final class AllvrServerHandler {

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel
            && serverLevel.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            AllvrServerLevelDuck duck = (AllvrServerLevelDuck) serverLevel;
            AllvrCubeMap map = serverLevel.players().isEmpty() ? duck.allvr$peekCubeMap() : duck.allvr$getCubeMap();
            if (map != null) {
                map.tick();
            }
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        resetSubscription(event.getEntity());
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        resetSubscription(event.getEntity());
    }

    private static void resetSubscription(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel allayLevel = server.getLevel(AllvrDimensions.ALLAY_LEVEL);
        if (allayLevel != null) {
            UUID uuid = player.getUUID();
            var map = ((AllvrServerLevelDuck) allayLevel).allvr$peekCubeMap();
            if (map != null) map.resetPlayer(uuid);
        }
    }

    public static void onChunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel server && AllvrDimensions.isAllay(server)) {
            var map = ((AllvrServerLevelDuck) server).allvr$peekCubeMap();
            if (map != null) map.lightEngine().onNativeColumnChanged(event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    public static void onChunkUnloaded(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server && AllvrDimensions.isAllay(server)) {
            var map = ((AllvrServerLevelDuck) server).allvr$peekCubeMap();
            if (map != null) map.lightEngine().onNativeColumnUnloaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    public static void onEntityJoined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && AllvrDimensions.isAllay(level)) {
            var map = ((AllvrServerLevelDuck) level).allvr$peekCubeMap();
            if (map != null) map.entityEntered(event.getEntity().blockPosition());
        }
    }
    public static void onEntityEntered(net.neoforged.neoforge.event.entity.EntityEvent.EnteringSection event) {
        if (event.getEntity().level() instanceof ServerLevel level && AllvrDimensions.isAllay(level)) {
            var map = ((AllvrServerLevelDuck) level).allvr$peekCubeMap();
            if (map != null) map.entityEntered(event.getEntity().blockPosition());
        }
    }

    private AllvrServerHandler() {}
}
