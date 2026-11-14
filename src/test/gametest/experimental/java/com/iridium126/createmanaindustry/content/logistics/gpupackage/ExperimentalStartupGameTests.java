package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import at.petrak.hexcasting.api.casting.iota.PatternIota;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.PatternIotaLoopDispatchAccess;
import com.iridium126.createmanaindustry.infrastructure.config.ExperimentalStartup;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class ExperimentalStartupGameTests {
    private static final BlockPos RECOVERY_POS = new BlockPos(8, 68, 8);
    private static final String RECOVERY_TAG = "CMIColdPackageFixture";

    @GameTest(template = "experimental_startup_test", timeoutTicks = 150)
    public static void startupAndRecovery(GameTestHelper helper) throws Exception {
        String phase = System.getProperty("cmi.experimentPhase");
        boolean packages = phase.equals("save");
        var mode = switch (phase) {
            case "profile" -> ServerConfig.HexJitMode.PROFILE;
            case "auto" -> ServerConfig.HexJitMode.AUTO;
            default -> ServerConfig.HexJitMode.OFF;
        };
        var settings = ExperimentalStartup.settings();
        helper.assertTrue(!settings.clientPackages(), "Dedicated launch read the client switch");
        helper.assertTrue(settings.serverPackages() == packages && ServerConfig.packageGpuAuthority == packages,
                "World overlay changed the process package switch");
        helper.assertTrue(settings.hexJitMode() == mode && ServerConfig.hexJitMode == mode,
                "World overlay changed the process Hex JIT switch");
        helper.assertTrue(ServerConfig.packageMainThreadBudgetNanos() == 17_000_000,
                "World overlay did not apply the GPU budget");
        helper.assertTrue(PackageInitialEntityAccess.class.isAssignableFrom(PackageEntity.class) == packages,
                "GPU package Mixin application disagrees with the startup switch");
        helper.assertTrue(PackageChainAccess.class.isAssignableFrom(ChainConveyorBlockEntity.class) == packages,
                "Shared package Mixin application disagrees with the startup switch");

        boolean hexcasting = ModList.get().isLoaded("hexcasting");
        String absent = System.getProperty("cmi.experimentAbsent", "");
        helper.assertTrue(hexcasting == !absent.equals("hexcasting"), "Incorrect Hexcasting test classpath");
        helper.assertTrue(ModList.get().isLoaded("hexal") == absent.isEmpty(), "Incorrect Hexal test classpath");
        boolean jit = hexcasting && mode != ServerConfig.HexJitMode.OFF;
        if (hexcasting) {
            helper.assertTrue(PatternIotaLoopDispatchAccess.class.isAssignableFrom(PatternIota.class) == jit,
                    "Hex JIT Mixin application disagrees with the startup mode");
        }
        helper.assertTrue(!jit || ModList.get().isLoaded("hexal") || !JitCompatibility.hexalTickReady(),
                "Hexal Tick fast path reported ready with Hexal absent");

        var level = helper.getLevel();
        var marker = level.getServer().getWorldPath(new LevelResource("cmi-package-fixture.txt"));
        if (packages) {
            level.getChunk(RECOVERY_POS);
            var box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 17)));
            var entity = PackageEntity.fromItemStack(level, Vec3.atCenterOf(RECOVERY_POS), box);
            entity.setNoGravity(true);
            entity.getPersistentData().putString(RECOVERY_TAG, "persisted");
            level.addFreshEntity(entity);
            var record = PackageLightStore.get(level).byUuid(entity.getUUID());
            helper.assertTrue(record != null && level.getEntity(entity.getUUID()) == null, "Package was not captured");
            Files.writeString(marker, entity.getUUID().toString());
            level.getServer().saveEverything(true, true, true);
        } else if (phase.equals("restore")) {
            UUID uuid = UUID.fromString(Files.readString(marker).trim());
            level.getChunk(RECOVERY_POS);
            helper.succeedWhen(() -> {
                helper.assertTrue(level.getEntity(uuid) instanceof PackageEntity, "Native package has not joined");
                var restored = (PackageEntity) level.getEntity(uuid);
                helper.assertTrue("persisted".equals(restored.getPersistentData().getString(RECOVERY_TAG)), "Custom NBT lost");
                helper.assertTrue(PackageItem.getContents(restored.getBox()).getStackInSlot(0).getCount() == 17,
                        "Restored inventory differs");
                helper.assertTrue(PackageLightStore.get(level).byUuid(uuid) == null, "Backing record was not consumed");
            });
            return;
        } else if (phase.equals("off")) {
            var config = FMLPaths.CONFIGDIR.get().resolve(ExperimentalStartup.SERVER_FILE);
            String original = Files.readString(config);
            Files.writeString(config, original.replace("enabled = false", "enabled = true")
                    .replace("mode = \"OFF\"", "mode = \"AUTO\"")
                    .replace("mainThreadBudgetMs = 17.0", "mainThreadBudgetMs = 19.0"));
            helper.runAfterDelay(1, () -> {
                // GameTest ticks run faster than the independent config watcher.
                long timeout = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
                while (ServerConfig.packageMainThreadBudgetNanos() != 19_000_000 && System.nanoTime() < timeout) {
                    java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(20).toNanos());
                }
                helper.assertTrue(ServerConfig.packageMainThreadBudgetNanos() == 19_000_000, "Config watcher did not reload");
                helper.assertTrue(ExperimentalStartup.settings() == settings, "Reload replaced the launch snapshot");
                helper.assertTrue(!ServerConfig.packageGpuAuthority && ServerConfig.hexJitMode == ServerConfig.HexJitMode.OFF,
                        "Reload enabled an experiment without its Mixins");
                helper.succeed();
            });
            return;
        }
        helper.succeed();
    }
}
