package com.iridium126.createmanaindustry.client.particles.command;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.HexSpecs;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.output.CMICommandOutput;
import com.iridium126.createmanaindustry.infrastructure.output.CMICommandOutput.Outcome;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client commands for the GPU particle engine.
 * <p>
 * Registered only on the client (client-side commands); every command body
 * first verifies the engine is available (GL capable + programs compiled).
 * Hex particle sources gate on {@code CreateManaIndustry.HEX_ACTIVE}
 * instead: hexcasting is an optional dependency and {@code HexSpecs} links
 * its API types, so both the handler and the pigment tab-completion must
 * stay behind that flag or they throw {@code NoClassDefFoundError} with the
 * mod absent.
 *
 * <pre>
 *   /cmi particle emit &lt;source&gt; &lt;amount&gt; [seconds|forever]
 *   /cmi particle anim &lt;preset&gt; &lt;animation&gt;
 *   /cmi particle clear|packagepreview &lt;amount&gt;|status|budget &lt;ms&gt;
 *   /cmi particle profile [details|hardware status|frames|capture|on|off]
 *   /cmi particle shaderpack status [details]|stormcloud|probe
 *   /cmi particle allaystorm ... (server command)
 * </pre>
 * All feedback is rendered through {@link CMICommandOutput}; do not emit
 * hand-written colour codes or unprefixed literal strings here.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class CMIParticleCommand {

    private CMIParticleCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> PRESETS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EmitterPresets.names(), builder);

    private static final SuggestionProvider<CommandSourceStack> ANIMATIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EmitterPresets.animationNames(), builder);

    // Behind HEX_ACTIVE as well: resolving HexSpecs.Pigment loads HexSpecs,
    // which links hexcasting API types — tab-completing particle sources
    // with the mod absent must not throw before the handler's guard runs.
    private static final SuggestionProvider<CommandSourceStack> SOURCES = (ctx, builder) -> {
        java.util.List<String> sources = new java.util.ArrayList<>(java.util.List.of(EmitterPresets.names()));
        if (CreateManaIndustry.HEX_ACTIVE) {
            for (HexSpecs.Pigment pigment : HexSpecs.Pigment.values()) {
                String name = pigment.name().toLowerCase(java.util.Locale.ROOT);
                if (sources.contains(name))
                    throw new IllegalStateException("Particle preset conflicts with Hex pigment source: " + name);
                sources.add(name);
            }
        }
        return SharedSuggestionProvider.suggest(sources, builder);
    };

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(commandTree());
    }

    static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("cmi")
                        .then(Commands.literal("particle")
                        .then(Commands.literal("emit")
                                .then(Commands.argument("source", StringArgumentType.word())
                                        .suggests(SOURCES)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4_000_000))
                                                .executes(ctx -> emit(ctx, false, 0f))
                                                .then(Commands.argument("seconds", FloatArgumentType.floatArg(0.1f, 3600f))
                                                        .executes(ctx -> emit(ctx, true,
                                                                FloatArgumentType.getFloat(ctx, "seconds"))))
                                                .then(Commands.literal("forever")
                                                        .executes(ctx -> emit(ctx, true, -1f))))))
                        .then(Commands.literal("anim")
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESETS)
                                        .then(Commands.argument("animation", StringArgumentType.word())
                                                .suggests(ANIMATIONS)
                                                .executes(CMIParticleCommand::anim))))
                        .then(Commands.literal("clear")
                                .executes(CMIParticleCommand::clear))
                        .then(Commands.literal("packagepreview")
                                .then(Commands.argument("amount",IntegerArgumentType.integer(0,131072)).executes(ctx->{
                                    if(!engine(ctx))return 0;
                                    int n=IntegerArgumentType.getInteger(ctx,"amount");
                                    if(n>0 && !ClientConfig.packageGpuAuthority) {
                                        fail(ctx,"Enable experimental GPU packages in the client config and restart.");return 0;
                                    }
                                    if(n>0 && dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse()
                                            && (!com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE
                                            || !com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.prepare())) {
                                        fail(ctx,CMICommandOutput.SHADERPACK,"Shader pack program unavailable: "+com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.status());return 0;
                                    }
                                    if(n>ParticleRuntime.INSTANCE.capacity()){fail(ctx,"Preview exceeds the shared particle pool capacity.");return 0;}
                                    var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
                                    var look=camera.getLookVector();
                                    Vec3 forward=new Vec3(look.x,0,look.z);
                                    if(forward.lengthSqr()<1e-8)forward=new Vec3(0,0,1);
                                    forward=forward.normalize();
                                    Vec3 origin=camera.getPosition().add(forward.scale(6)).add(0,1,0);
                                    com.iridium126.createmanaindustry.client.packages.render.PackageRuntime.INSTANCE.previewPackages(n,origin,forward);
                                    ok(ctx,n==0?"Package preview stop queued.":"Queued "+n+" synthetic chain packages ahead of the camera; Create gameplay takeover is not enabled.");
                                    return 1;
                                })))
                        .then(Commands.literal("profile")
                                .executes(CMIParticleCommand::profile)
                                .then(Commands.literal("details").executes(CMIParticleCommand::profileDetails))
                                .then(Commands.literal("hardware").then(Commands.literal("status")
                                        .executes(CMIParticleCommand::hardwareStatus)))
                                .then(Commands.literal("frames")
                                        .then(Commands.literal("cancel").executes(ctx -> {
                                            boolean cancelled = com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.cancel();
                                            if (cancelled) ok(ctx, "Frame capture cancelled.");
                                            else warn(ctx, "No frame capture to cancel.");
                                            return cancelled ? Command.SINGLE_SUCCESS : 0;
                                        }))
                                        .then(Commands.argument("seconds",IntegerArgumentType.integer(1,900)).executes(ctx->{
                                    boolean queued=com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.request(IntegerArgumentType.getInteger(ctx,"seconds"));
                                    if (queued) ok(ctx,"Whole-client frame capture queued; close menus and keep the scene/settings fixed. Results: logs/cmi-particle-frames");
                                    else warn(ctx,"A frame capture is already pending.");
                                    return queued?1:0;
                                })))
                                .then(Commands.literal("capture").then(Commands.argument("name",StringArgumentType.word()).executes(ctx->{
                                    String name=StringArgumentType.getString(ctx,"name");
                                    boolean queued=com.iridium126.createmanaindustry.client.particles.engine.ParticleChainCapture.INSTANCE.request(name);
                                    if (queued) ok(ctx,"Asynchronous complete GPU command-chain freeze queued for the next frame. Instrumented: exclude from FPS. Output: logs/cmi-particle-captures");
                                    else fail(ctx,"Capture name must match [A-Za-z0-9_-]{1,64}, with no pending capture.");
                                    return queued?1:0;
                                })))
                                .then(Commands.literal("on").executes(ctx -> { com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled(true); ok(ctx,"Particle profiling enabled."); return 1; }))
                                .then(Commands.literal("off").executes(ctx -> { com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled(false); ok(ctx,"Particle profiling disabled."); return 1; })))
                        .then(Commands.literal("status")
                                .executes(CMIParticleCommand::status))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("ms", FloatArgumentType.floatArg(1f, 50f))
                                        .executes(CMIParticleCommand::budget)))
                        .then(Commands.literal("shaderpack")
                                .then(Commands.literal("status")
                                        .executes(ctx->shaderPackStatus(ctx,false))
                                        .then(Commands.literal("details").executes(ctx->shaderPackStatus(ctx,true))))
                                .then(Commands.literal("stormcloud")
                                        .then(Commands.literal("on").executes(ctx->stormCloud(ctx,true,false)))
                                        .then(Commands.literal("off").executes(ctx->stormCloud(ctx,false,false)))
                                        .then(Commands.literal("profile")
                                                .then(Commands.literal("on").executes(ctx->stormCloud(ctx,true,true)))
                                                .then(Commands.literal("off").executes(ctx->stormCloud(ctx,false,true)))))
                                .then(Commands.literal("probe")
                                        .then(Commands.literal("on").executes(ctx->visualProbe(ctx,true)))
                                        .then(Commands.literal("off").executes(ctx->visualProbe(ctx,false)))
                                        .then(Commands.literal("mode")
                                                .then(Commands.literal("auto").executes(ctx->visualProbeMode(ctx,false)))
                                                .then(Commands.literal("baseline").executes(ctx->visualProbeMode(ctx,true)))))));
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static int hardwareStatus(CommandContext<CommandSourceStack> ctx) {
        CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.PROFILE, "Storm hardware",
                SpatialQueryRuntime.INSTANCE.status().lines().toList());
        return Command.SINGLE_SUCCESS;
    }

    private static int emit(CommandContext<CommandSourceStack> ctx, boolean streaming, float seconds) {
        String source = StringArgumentType.getString(ctx, "source");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        String timeError = durationValidationError(streaming, seconds);
        if (timeError != null) {
            fail(ctx, timeError);
            return 0;
        }
        EmitterSpec spec = EmitterPresets.byName(source);
        boolean isPigment = false;
        if (CreateManaIndustry.HEX_ACTIVE) {
            try {
                HexSpecs.Pigment.valueOf(source.toUpperCase(java.util.Locale.ROOT));
                isPigment = true;
            } catch (IllegalArgumentException ignored) {
                // Not a Hex pigment; it may still be an emitter preset.
            }
        }
        if (spec != null && isPigment) {
            fail(ctx, "Ambiguous particle source; preset and pigment names must be distinct.");
            return 0;
        }
        if (spec != null) {
            String error = amountValidationError(false, streaming, amount);
            if (error != null) {
                fail(ctx, error);
                return 0;
            }
            if (!engine(ctx)) return 0;
            Vec3 pos = ctx.getSource().getPosition().add(0, 0.2, 0);
            if (streaming) {
                CMIParticleEngine.INSTANCE.stream(spec, pos, amount, seconds);
                ok(ctx, "Streaming " + amount + "/s × " + source + " for "
                        + (seconds <= 0 ? "forever" : seconds + "s") + ".");
            } else {
                CMIParticleEngine.INSTANCE.spawn(spec, pos, amount);
                ok(ctx, "Spawning " + amount + " × " + source + " (throttled).");
            }
            return Command.SINGLE_SUCCESS;
        }
        if (isPigment) {
            String error = amountValidationError(true, streaming, amount);
            if (error != null) {
                fail(ctx, error);
                return 0;
            }
            return spray(ctx, source, amount);
        }
        if (!CreateManaIndustry.HEX_ACTIVE)
            fail(ctx, "Unknown particle preset, or Hexcasting is not loaded for pigment sources.");
        else
            fail(ctx, "Unknown particle source. Try: " + String.join(", ", EmitterPresets.names())
                    + ", amethyst, uuid, rainbow");
        return 0;
    }

    static String amountValidationError(boolean pigment, boolean streaming, int amount) {
        if (amount < 1) return "Amount must be at least 1.";
        if (pigment && streaming) return "Hex pigment sources cannot be streamed; omit the duration.";
        int maximum = pigment ? 2_000 : streaming ? 1_000_000 : 4_000_000;
        if (amount > maximum) {
            if (pigment) return "Hex spray count must be between 1 and 2000.";
            if (streaming) return "Stream rate must be between 1 and 1000000 particles per second.";
            return "Particle count must be between 1 and 4000000.";
        }
        return null;
    }

    static String durationValidationError(boolean streaming, float seconds) {
        if (!streaming || seconds == -1f) return null;
        if (!Float.isFinite(seconds) || seconds < 0.1f || seconds > 3600f)
            return "Stream duration must be 0.1 to 3600 seconds, or forever.";
        return null;
    }

    private static int anim(CommandContext<CommandSourceStack> ctx) {
        String presetName = StringArgumentType.getString(ctx, "preset");
        EmitterSpec spec = EmitterPresets.byName(presetName);
        if (spec == null || spec.type.material() != ParticleTypes.Material.MODEL) {
            fail(ctx, "Unknown MODEL preset. Try: allay_fly, allay_dance, allay_hold");
            return 0;
        }
        String animName = StringArgumentType.getString(ctx, "animation");
        EmitterSpec.Animation anim = switch (animName) {
            case "fly" -> EmitterSpec.Animation.FLY;
            case "dance" -> EmitterSpec.Animation.DANCE;
            case "hold" -> EmitterSpec.Animation.HOLD;
            default -> null;
        };
        if (anim == null) {
            // the DEATH pose is pool-driven now (HP-death corpse countdown) --
            // a per-emitter header switch to it has no valid meaning
            fail(ctx, "Unknown animation. Try: fly, dance, hold");
            return 0;
        }
        if (!engine(ctx)) {
            return 0;
        }
        CMIParticleEngine.INSTANCE.setAnimation(spec, anim);
        ok(ctx, "Animation switch queued: " + presetName + " -> " + animName
                + " (live particles switch next frame).");
        return Command.SINGLE_SUCCESS;
    }

    private static int spray(CommandContext<CommandSourceStack> ctx, String name, int count) {
        // Hexcasting is optional: HexSpecs (and the ColorProvider below) link
        // its API types, so the whole handler must return before touching them.
        if (!CreateManaIndustry.HEX_ACTIVE) {
            fail(ctx, "Conjure sprays require Hexcasting, which is not loaded.");
            return 0;
        }
        HexSpecs.Pigment pigment = HexSpecs.Pigment.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        if (!engine(ctx)) {
            return 0;
        }
        // vanilla StaffCastEnv caster spray: origin = the player's position,
        // velocity straight up 1.5 b/s, fuzziness 0.4, spread π/3, 30 motes.
        // NeoForge client-command sources carry no player entity, so the
        // local player comes straight off Minecraft.
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            fail(ctx, "A player is required (the uuid pigment keys off the caster's UUID).");
            return 0;
        }
        at.petrak.hexcasting.api.pigment.ColorProvider provider =
                HexSpecs.pigment(pigment, player.getUUID());
        Vec3 pos = player.position();
        CMIParticleEngine.INSTANCE.spawnHexSpray(pos, new Vec3(0.0, 1.5, 0.0),
                0.4, Math.PI / 3, count, HexSpecs.sampleWheel(provider));
        ok(ctx, "Hex spray: " + count + " conjure motes, pigment " + pigment.name().toLowerCase(java.util.Locale.ROOT) + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            return 0;
        }
        CMIParticleEngine.INSTANCE.clear();
        com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.profiler.reset();
        ok(ctx, "Cleared all particles and streams.");
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            return 0;
        }
        var statistics = ParticleRuntime.INSTANCE.statistics();
        var profiler = com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.profiler;
        boolean throttle = ClientConfig.particleAutoThrottle;
        CMICommandOutput.sendReport(ctx.getSource(), CMICommandOutput.PARTICLES, "Particle engine status",
                "live", statistics.live() + " / " + statistics.capacity(),
                "streams", statistics.streams(),
                "emission", Math.round(statistics.emissionScale() * 100) + "%",
                "auto throttle", throttle ? "on" : "off",
                "gpu frame", throttle ? String.format(java.util.Locale.ROOT, "%.2f", profiler.emaMs()) + " ms" : "not sampled",
                "frame budget", profiler.budget() + " ms");
        return Command.SINGLE_SUCCESS;
    }

    private static int profile(CommandContext<CommandSourceStack> ctx) {
        return showProfile(ctx,false);
    }

    private static int profileDetails(CommandContext<CommandSourceStack> ctx) {
        return showProfile(ctx,true);
    }

    private static int showProfile(CommandContext<CommandSourceStack> ctx,boolean details) {
        var diagnostics=com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE;
        var frame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.budgets.snapshot();
        var report=new StringBuilder(details?diagnostics.report(frame):diagnostics.summary(frame));
        if(diagnostics.hasSamples()) {
            appendReport(report,details?com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.report():com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.summary());
            appendReport(report,details?com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime.profilingReport():com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime.profilingSummary());
            appendReport(report,details?com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.report():com.iridium126.createmanaindustry.client.packages.authority.PackageAuthorityClient.profilingSummary());
            if(CreateManaIndustry.HEX_ACTIVE)appendReport(report,details?com.iridium126.createmanaindustry.client.hex.pattern.HexMetrics.report():com.iridium126.createmanaindustry.client.hex.pattern.HexMetrics.summary());
            if(CreateManaIndustry.IRISVEIL_ACTIVE)appendReport(report,details?com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.profilingReport():com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.profilingSummary());
            if(CreateManaIndustry.IRIS_ACTIVE)appendReport(report,details?com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.profilingReport():com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.profilingSummary());
            if(details)appendReport(report,com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.starfallStatus());
        }
        appendReport(report,com.iridium126.createmanaindustry.client.particles.engine.StormLodGpu.INSTANCE.report());
        CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.PROFILE,
                "Particle profile" + (details ? " (details)" : ""), report.toString().lines().toList());
        return Command.SINGLE_SUCCESS;
    }

    private static void appendReport(StringBuilder report,String section) {
        if(!section.isBlank())report.append('\n').append(section);
    }

    private static int budget(CommandContext<CommandSourceStack> ctx) {
        float ms = FloatArgumentType.getFloat(ctx, "ms");
        if (!engine(ctx)) {
            return 0;
        }
        com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.profiler.setBudget(ms);
        ok(ctx, "Particle frame budget set to " + ms + " ms.");
        return Command.SINGLE_SUCCESS;
    }

    private static int shaderPackStatus(CommandContext<CommandSourceStack> ctx,boolean details) {
        var snapshot=com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackStatusSnapshot.capture();
        CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.SHADERPACK,
                "Shader pack status" + (details ? " (details)" : ""), snapshot.lines(details));
        return Command.SINGLE_SUCCESS;
    }
    private static int stormCloud(CommandContext<CommandSourceStack> ctx,boolean enabled,boolean profile){
        if(profile){
            CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.SHADERPACK,
                    "Storm cloud density report",
                    com.iridium126.createmanaindustry.client.allaystorm.rift.VanillaSkyView.cloudReport().lines().toList());
            if(CreateManaIndustry.IRIS_ACTIVE){
                var session=com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
                CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.SHADERPACK,
                        "Native stage GPU timings", session.cloudTimingReport().lines().toList());
                session.cloudProfiling(enabled);
            }
            com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.cloudProfiling(enabled);
        }else{
            com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.cloudEnabled(enabled);
            com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider.cloudProfiling(false);
            if(CreateManaIndustry.IRIS_ACTIVE){
                var session=com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
                CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.SHADERPACK,
                        "Native stage GPU timings", session.cloudTimingReport().lines().toList());
                session.cloudProfiling(false);
            }
        }
        ok(ctx, CMICommandOutput.SHADERPACK,
                profile?"Native stage GPU profiling: "+enabled:"Storm cloud density: "+(enabled?"on":"bypassed")+"; local only; re-enable profiling after switching A/B");
        return Command.SINGLE_SUCCESS;
    }
    private static int visualProbe(CommandContext<CommandSourceStack> ctx,boolean enabled){
        if(!CreateManaIndustry.VEIL_ACTIVE){
            fail(ctx, CMICommandOutput.SHADERPACK, "Veil is not loaded; the visual probe requires Veil.");
            return 0;
        }
        com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe.setEnabled(enabled);
        info(ctx, CMICommandOutput.SHADERPACK, com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe.status());
        return Command.SINGLE_SUCCESS;
    }
    private static int visualProbeMode(CommandContext<CommandSourceStack> ctx,boolean baseline){
        com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe.baseline(baseline);
        info(ctx, CMICommandOutput.SHADERPACK, com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe.status());
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Guards on the actual engine state: initialized, not disabled, programs compiled. */
    private static boolean engine(CommandContext<CommandSourceStack> ctx) {
        // The engine is self-hosted GL — Veil is not a prerequisite.
        if (!ParticleRuntime.INSTANCE.available()) {
            fail(ctx, "Particle engine unavailable: not initialized, disabled, or shader programs not ready. "
                    + "Recompile with F3+T if shader compilation failed.");
            return false;
        }
        return true;
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String message) {
        ok(ctx, CMICommandOutput.PARTICLES, message);
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String module, String message) {
        CMICommandOutput.send(ctx.getSource(), module, Outcome.OK, message);
    }

    private static void info(CommandContext<CommandSourceStack> ctx, String module, String message) {
        CMICommandOutput.send(ctx.getSource(), module, Outcome.INFO, message);
    }

    private static void warn(CommandContext<CommandSourceStack> ctx, String message) {
        CMICommandOutput.send(ctx.getSource(), CMICommandOutput.PARTICLES, Outcome.WARN, message);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        fail(ctx, CMICommandOutput.PARTICLES, message);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String module, String message) {
        CMICommandOutput.send(ctx.getSource(), module, Outcome.FAIL, message);
    }
}
