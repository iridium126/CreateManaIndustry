package com.iridium126.createmanaindustry.infrastructure.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver;
import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleChainCapture;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime;
import com.google.gson.JsonParser;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAccess;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageInitialEntityAccess;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class ExperimentalClientProbe {
    private static final long DEADLINE = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
    private static boolean done;
    private static boolean openRequested;
    private static int diagnosticStep;
    private static long captureStarted;
    private static String chainName;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        String side = System.getProperty("cmi.experimentClientSide");
        if (side == null) return;
        if (done) {
            if (Boolean.getBoolean("cmi.testDiagnosticCommands")) diagnostics(Minecraft.getInstance());
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null || minecraft.player == null || minecraft.level == null) {
            if (minecraft.screen instanceof BackupConfirmScreen screen) {
                for (var child : screen.children()) {
                    if (child instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents text
                            && text.getKey().equals("selectWorld.backupJoinSkipButton")) {
                        button.onPress();
                        break;
                    }
                }
            } else if (!openRequested && minecraft.isGameLoadFinished() && minecraft.screen instanceof TitleScreen) {
                openRequested = true;
                minecraft.createWorldOpenFlows().openWorld("Experimental", () -> {
                    throw new AssertionError("Could not open the isolated fixture world");
                });
            }
            if (System.nanoTime() - DEADLINE >= 0)
                throw new AssertionError("Integrated server did not start. Screen: " + minecraft.screen);
            return;
        }
        done = true;
        boolean client = side.equals("client");
        check(ClientConfig.packageGpuAuthority == client, "client switch");
        check(ServerConfig.packageGpuAuthority != client, "integrated server switch");
        boolean cameraHook = java.util.Arrays.stream(Frustum.class.getInterfaces()).anyMatch(type ->
                type.getName().equals("com.iridium126.createmanaindustry.mixin.render.PackageCameraFrustumAccessor"));
        check(cameraHook == client, "client Mixin");
        check(PackageInitialEntityAccess.class.isAssignableFrom(PackageEntity.class) != client, "server Mixin");
        check(PackageChainAccess.class.isAssignableFrom(ChainConveyorBlockEntity.class), "shared Mixin");
        CreateManaIndustry.LOGGER.info("CMI_EXPERIMENTAL_CLIENT_PASSED {}", side);
        if (!Boolean.getBoolean("cmi.testDiagnosticCommands")) minecraft.stop();
    }

    private static void diagnostics(Minecraft minecraft) {
        if (System.nanoTime() - DEADLINE >= 0)
            throw new AssertionError("Diagnostic probe stopped at step " + diagnosticStep);
        if (minecraft.screen == null && !minecraft.isWindowActive()) minecraft.setWindowActive(true);
        var capture = ClientFrameCapture.INSTANCE;
        switch (diagnosticStep) {
            case 0 -> {
                check(!capture.busy() && !GpuTimingObserver.active(), "idle diagnostic state");
                if (Boolean.getBoolean("cmi.testDiagnosticShaderPack")) {
                    top.leonx.irisveil.compat.iris.BeforeDeferredWorldRenderers.INSTANCE
                            .register("cmi-test-diagnostic-callback", () -> true);
                }
                minecraft.setScreen(null);
                minecraft.setWindowActive(true);
                captureStarted = System.currentTimeMillis();
                command("profile frames 2");
                check(capture.busy(), "frame request");
                diagnosticStep++;
            }
            case 1 -> {
                if (!capture.collecting()) return;
                check(GpuTimingObserver.active(), "active GPU observer");
                command("profile frames 2");
                check(capture.collecting(), "duplicate command replaced the active capture");
                diagnosticStep++;
            }
            case 2 -> {
                if (capture.busy() || !exportedFrame(minecraft)) return;
                check(!GpuTimingObserver.active(), "observer after completed capture");
                command("profile frames 30");
                diagnosticStep++;
            }
            case 3 -> {
                if (!capture.collecting()) return;
                command("profile frames cancel");
                check(!capture.collecting(), "cancelled capture still collecting");
                diagnosticStep++;
            }
            case 4 -> {
                if (capture.busy() || !ParticleRuntime.INSTANCE.available()) return;
                check(!GpuTimingObserver.active(), "observer after cancel");
                command("shaderpack stormcloud profile on");
                check(com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.cloudProfiling(),
                        "cloud timing after enable command");
                chainName = "mixin_probe_" + captureStarted;
                command("emit ember 32 2");
                command("profile capture " + chainName);
                check(ParticleChainCapture.INSTANCE.busy(), "command chain request");
                diagnosticStep++;
            }
            case 5 -> {
                if (ParticleChainCapture.INSTANCE.busy()) return;
                check(exportedChain(minecraft), "command chain export");
                command("shaderpack stormcloud profile off");
                check(!com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.cloudProfiling(),
                        "cloud timing after disable command");
                command("profile frames 30");
                diagnosticStep++;
            }
            case 6 -> {
                if (!capture.collecting()) return;
                CreateManaIndustry.LOGGER.info("CMI_DIAGNOSTIC_COMMANDS_PASSED");
                diagnosticStep++;
                minecraft.stop();
            }
        }
    }

    private static boolean exportedFrame(Minecraft minecraft) {
        Path directory = minecraft.gameDirectory.toPath().resolve("logs/cmi-particle-frames");
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().startsWith("frames-")
                    && file.getFileName().toString().endsWith(".json")).toList()) {
                if (Files.getLastModifiedTime(path).toMillis() < captureStarted) continue;
                var stages = path.resolveSibling(path.getFileName().toString().replace(".json", "-stages.csv"));
                if (!Files.isRegularFile(stages)) continue;
                try (var reader = Files.newBufferedReader(path)) {
                    var metadata = JsonParser.parseReader(reader).getAsJsonObject();
                    if (metadata.get("samples").getAsInt() < 2) continue;
                }
                String content = Files.readString(stages);
                if (!content.contains("world.section.")) continue;
                if (Boolean.getBoolean("cmi.testDiagnosticShaderPack")
                        && (!content.contains("iris.translucents.begin") || !content.contains("irisveil.before-deferred.callbacks"))) continue;
                return true;
            }
            return false;
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not read frame diagnostics", failure);
        }
    }

    private static boolean exportedChain(Minecraft minecraft) {
        Path directory = minecraft.gameDirectory.toPath().resolve("logs/cmi-particle-captures");
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) {
            for (Path capture : files.filter(path -> path.getFileName().toString().startsWith(chainName + "-")).toList()) {
                var manifest = capture.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) continue;
                try (var reader = Files.newBufferedReader(manifest)) {
                    var result = JsonParser.parseReader(reader).getAsJsonObject();
                    check(result.get("complete").getAsBoolean(), "command chain publication: " + result.get("failure_reason"));
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not read GPU command capture", failure);
        }
    }

    private static void command(String suffix) {
        check(ClientCommandHandler.runCommand("cmi particle " + suffix), "client command " + suffix);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Unexpected " + description);
    }
}
