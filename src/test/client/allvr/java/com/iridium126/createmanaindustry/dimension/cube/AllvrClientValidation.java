package com.iridium126.createmanaindustry.dimension.cube;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Automated smoke test only; it is never included in a release jar. */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class AllvrClientValidation {
    private static final int[] HEIGHTS = {500, 8_388_608, 16_777_216, -16_777_216, 29_999_936};
    private static final StringBuilder REPORT = new StringBuilder("Allvr isolated client smoke test\n");
    private static long started;
    private static int stage, heightIndex, waitTicks;
    private static volatile boolean setupComplete;
    private static BlockPos floor, signPos;
    private static boolean done;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (done || !Boolean.getBoolean("cmi.allvrClientValidation")) return;
        var mc = Minecraft.getInstance();
        if (started == 0) started = System.nanoTime();
        try {
            if (System.nanoTime() - started > java.util.concurrent.TimeUnit.MINUTES.toNanos(6)) throw new AssertionError("Client scenario timed out at stage " + stage + " height=" + heightIndex);
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null || mc.level == null) return;
            if (stage == 0) {
                stage = 1; setupComplete = false;
                floor = new BlockPos(4, HEIGHTS[heightIndex], 4); signPos = floor.above();
                server.execute(() -> {
                    try {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        prepareScene(player, floor.getY());
                        setupComplete = true;
                    } catch (Throwable error) { mc.execute(() -> finish(error)); }
                });
                mc.setScreen(null);
                return;
            }
            if (!setupComplete || !AllvrDimensions.isAllay(mc.level)) return;
            if (stage == 1) {
                if (!mc.level.getBlockState(floor).is(Blocks.STONE)) return;
                if (!(mc.level.getBlockEntity(signPos) instanceof SignBlockEntity sign) || !sign.getFrontText().getMessage(0, false).getString().equals("full")) return;
                var storage = ((AllvrEntityLevelAccess) mc.level).allvr$entitySections();
                if (!storage.enabled() || mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(floor).inflate(16)).isEmpty()) return;
                var manager = SodiumApi_0813_1211.sectionManager();
                int localY = AllvrSodiumBridge.window().virtualSectionY(floor.getY() >> 4);
                if (manager == null || !SodiumApi_0813_1211.isSectionBuilt(manager, 0, localY, 0)) return;
                REPORT.append("PASS full/BE/entity/Sodium section at Y=").append(floor.getY()).append('\n');
                stage = 2; waitTicks = 0;
                server.execute(() -> updateScene(server.getLevel(AllvrDimensions.ALLAY_LEVEL)));
            } else if (stage == 2) {
                if (!mc.level.getBlockState(floor.offset(1, 0, 0)).is(Blocks.GLOWSTONE)) return;
                if (!(mc.level.getBlockEntity(signPos) instanceof SignBlockEntity sign) || !sign.isWaxed() || !sign.getFrontText().getMessage(0, false).getString().equals("delta")) return;
                if (++waitTicks < 30) return;
                REPORT.append("PASS block/BE delta at Y=").append(floor.getY()).append('\n');
                if (++heightIndex < HEIGHTS.length) stage = 0;
                else finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }
    @SubscribeEvent public static void commands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("allvr_validation")
            .requires(source -> source.hasPermission(2))
            .then(net.minecraft.commands.Commands.literal("setup")
                .then(net.minecraft.commands.Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-29_999_936, 29_999_936))
                    .executes(context -> {
                        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "y");
                        if (com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaY(y)) {
                            context.getSource().sendFailure(Component.literal("Choose an outer Cube height, for example 500")); return 0;
                        }
                        prepareScene(context.getSource().getPlayerOrException(), y); return 1;
                    })))
            .then(net.minecraft.commands.Commands.literal("update").executes(context -> {
                if (floor == null) { context.getSource().sendFailure(Component.literal("Run setup first")); return 0; }
                updateScene(context.getSource().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL)); return 1;
            })));
    }
    private static void prepareScene(net.minecraft.server.level.ServerPlayer player, int y) {
        floor = new BlockPos(4, y, 4); signPos = floor.above();
        var server = player.getServer(); var level = server.getLevel(AllvrDimensions.ALLAY_LEVEL);
        server.getPlayerList().setViewDistance(2); server.getPlayerList().setSimulationDistance(2);
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        map.publishCube(new AllvrCube(AllvrCubePos.of(floor), level.registryAccess().registryOrThrow(Registries.BIOME)));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), 3);
        var sign = (SignBlockEntity) level.getBlockEntity(signPos);
        sign.setText(sign.getFrontText().setMessage(0, Component.literal("full")), true);
        var entity = EntityType.ARMOR_STAND.create(level); entity.setPos(7, y + 1, 7); entity.setNoGravity(true); level.addFreshEntity(entity);
        player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = true; player.onUpdateAbilities();
        player.teleportTo(level, 4.5, y + 3, 9.5, java.util.Set.of(), 180, 40);
    }
    private static void updateScene(net.minecraft.server.level.ServerLevel level) {
        level.setBlock(floor.offset(1, 0, 0), Blocks.GLOWSTONE.defaultBlockState(), 3);
        var sign = (SignBlockEntity) level.getBlockEntity(signPos);
        sign.setText(sign.getFrontText().setMessage(0, Component.literal("delta")), true); sign.setWaxed(true);
        level.getChunkSource().blockChanged(signPos);
    }
    private static void finish(Throwable error) {
        if (done) return; done = true;
        if (error != null) { REPORT.append("FAIL ").append(error).append('\n'); CreateManaIndustry.LOGGER.error("Allvr client validation failed", error); }
        else REPORT.append("PASS all automated client scenarios\n");
        try { java.nio.file.Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("allvr-client-result.txt"), REPORT); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        Minecraft.getInstance().stop();
    }
}
