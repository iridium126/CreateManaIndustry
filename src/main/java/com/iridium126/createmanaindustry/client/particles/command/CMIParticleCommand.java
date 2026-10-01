package com.iridium126.createmanaindustry.client.particles.command;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.HexSpecs;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

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
import net.minecraft.network.chat.Component;
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
 *   /cmi particle clear|stats|profile|budget|shaderpack status
 *   /cmi particle allaystorm ... (server command)
 * </pre>
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
                                    if(n>0 && dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse()
                                            && (!com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE
                                            || !com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook.prepare())) {
                                        tell(ctx,"Package shaderpack program unavailable: "+CMIParticleEngine.INSTANCE.packageShaderStatus());return 0;
                                    }
                                    if(n>CMIParticleEngine.INSTANCE.capacity()){tell(ctx,"Preview exceeds the shared particle pool capacity.");return 0;}
                                    var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
                                    var look=camera.getLookVector();
                                    Vec3 forward=new Vec3(look.x,0,look.z);
                                    if(forward.lengthSqr()<1e-8)forward=new Vec3(0,0,1);
                                    forward=forward.normalize();
                                    Vec3 origin=camera.getPosition().add(forward.scale(6)).add(0,1,0);
                                    CMIParticleEngine.INSTANCE.previewPackages(n,origin,forward);
                                    tell(ctx,n==0?"Package preview stop queued.":"Queued "+n+" synthetic chain packages ahead of the camera; Create gameplay takeover is not enabled.");
                                    return 1;
                                })))
                        .then(Commands.literal("packagecollision")
                                .executes(ctx->{tell(ctx,com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime.report());return 1;})
                                .then(Commands.literal("capture").executes(ctx->{
                                    var mc=net.minecraft.client.Minecraft.getInstance();
                                    if(mc.level==null || mc.player==null)return 0;
                                    var runtime=com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime.forLevel(mc.level);
                                    boolean queued=runtime.request(mc.player.getBoundingBox().inflate(16));
                                    tell(ctx,queued?"Queued nearby collision sections; capture and GPU upload use separate 0.25 ms soft budgets. Check packagecollision for completed GPU coverage.":"Collision cache capacity unavailable; Create retains ownership.");return queued?1:0;
                                }))
                                .then(Commands.literal("clear").executes(ctx->{
                                    com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime.closeCurrent();
                                    tell(ctx,"Package collision coverage revoked.");return 1;
                                })))
                        .then(Commands.literal("profile")
                                .executes(ctx -> { tell(ctx, com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.report());tell(ctx,com.iridium126.createmanaindustry.client.particles.packages.PackageAuthorityClient.report());return 1; })
                                .then(Commands.literal("on").executes(ctx -> { com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled(true); tell(ctx,"Particle profiling enabled"); return 1; }))
                                .then(Commands.literal("off").executes(ctx -> { com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled(false); tell(ctx,"Particle profiling disabled"); return 1; })))
                        .then(Commands.literal("stats")
                                .executes(CMIParticleCommand::stats))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("ms", FloatArgumentType.floatArg(1f, 50f))
                                        .executes(CMIParticleCommand::budget)))
                        .then(Commands.literal("shaderpack")
                                .then(Commands.literal("status")
                                        .executes(CMIParticleCommand::shaderPackStatus))));
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static int emit(CommandContext<CommandSourceStack> ctx, boolean streaming, float seconds) {
        String source = StringArgumentType.getString(ctx, "source");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        String timeError = durationValidationError(streaming, seconds);
        if (timeError != null) {
            tell(ctx, timeError);
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
            tell(ctx, "Ambiguous particle source; preset and pigment names must be distinct.");
            return 0;
        }
        if (spec != null) {
            String error = amountValidationError(false, streaming, amount);
            if (error != null) {
                tell(ctx, error);
                return 0;
            }
            if (!engine(ctx)) return 0;
            Vec3 pos = ctx.getSource().getPosition().add(0, 0.2, 0);
            if (streaming) {
                CMIParticleEngine.INSTANCE.stream(spec, pos, amount, seconds);
                tell(ctx, "Streaming " + amount + "/s × " + source + " for "
                        + (seconds <= 0 ? "forever" : seconds + "s") + ".");
            } else {
                CMIParticleEngine.INSTANCE.spawn(spec, pos, amount);
                tell(ctx, "Spawning " + amount + " × " + source + " (throttled).");
            }
            return Command.SINGLE_SUCCESS;
        }
        if (isPigment) {
            String error = amountValidationError(true, streaming, amount);
            if (error != null) {
                tell(ctx, error);
                return 0;
            }
            return spray(ctx, source, amount);
        }
        if (!CreateManaIndustry.HEX_ACTIVE)
            tell(ctx, "Unknown particle preset, or Hexcasting is not loaded for pigment sources.");
        else
            tell(ctx, "Unknown particle source. Try: " + String.join(", ", EmitterPresets.names())
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
            tell(ctx, "Unknown MODEL preset. Try: allay_fly, allay_dance, allay_hold");
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
            tell(ctx, "Unknown animation. Try: fly, dance, hold");
            return 0;
        }
        if (!engine(ctx)) {
            return 0;
        }
        CMIParticleEngine.INSTANCE.setAnimation(spec, anim);
        tell(ctx, "Animation switch queued: " + presetName + " -> " + animName
                + " (live particles switch next frame).");
        return Command.SINGLE_SUCCESS;
    }

    private static int spray(CommandContext<CommandSourceStack> ctx, String name, int count) {
        // Hexcasting is optional: HexSpecs (and the ColorProvider below) link
        // its API types, so the whole handler must return before touching them.
        if (!CreateManaIndustry.HEX_ACTIVE) {
            tell(ctx, "Conjure sprays require Hexcasting, which is not loaded.");
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
            tell(ctx, "A player is required (the uuid pigment keys off the caster's UUID).");
            return 0;
        }
        at.petrak.hexcasting.api.pigment.ColorProvider provider =
                HexSpecs.pigment(pigment, player.getUUID());
        Vec3 pos = player.position();
        CMIParticleEngine.INSTANCE.spawnHexSpray(pos, new Vec3(0.0, 1.5, 0.0),
                0.4, Math.PI / 3, count, HexSpecs.sampleWheel(provider));
        tell(ctx, "Hex spray: " + count + " conjure motes, pigment " + pigment.name().toLowerCase(java.util.Locale.ROOT) + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        CMIParticleEngine.INSTANCE.clear();
        tell(ctx, "Cleared all particles and streams.");
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable (Veil absent or shaders not ready).");
            return 0;
        }
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        tell(ctx, "", "§b[CMI particles]§r live=§e" + e.liveCount() + "§r/" + e.capacity()
                + "  streams=" + e.streamCount()
                + "  emission=" + Math.round(e.emissionScale() * 100) + "%"
                + "  gpu=" + String.format("%.2f", e.emaMs()) + "ms (budget " + e.budgetMs() + "ms)");
        tell(ctx,"Package preview: "+e.packagePreviewStatus()+"; live includes admitted package slots (asynchronous GPU snapshot).");
        tell(ctx,"Package shaderpack: "+e.packageShaderStatus());
        return Command.SINGLE_SUCCESS;
    }

    private static int budget(CommandContext<CommandSourceStack> ctx) {
        float ms = FloatArgumentType.getFloat(ctx, "ms");
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        CMIParticleEngine.INSTANCE.setBudget(ms);
        tell(ctx, "Particle frame budget set to " + ms + " ms.");
        return Command.SINGLE_SUCCESS;
    }

    private static int shaderPackStatus(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx))
            return 0;
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        String integration = ClientConfig.shaderPackIntegration ? "auto" : "off";
        tell(ctx, "", "§b[CMI particles]§r shader-pack path:"
                + " config=§e" + integration + "§r"
                + "  irisveil=§e" + (CreateManaIndustry.IRISVEIL_ACTIVE ? "loaded" : "absent") + "§r"
                + "  path=§e" + e.shaderPackPathStatus + "§r"
                + "  depth=§e" + e.shaderPackDepthStatus + "§r"
                + "  shadow=§e" + e.shaderPackShadowStatus);
        tell(ctx, "§7", "permutation: §e" + e.shaderPackPermStatus);
        if (!e.shaderPackErrorStatus.isEmpty())
            tell(ctx, "§7", "last fallback reason: §c" + e.shaderPackErrorStatus + "§r");
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean engine(CommandContext<CommandSourceStack> ctx) {
        // The engine is self-hosted GL — Veil is no longer required to spawn.
        if (!CMIParticleEngine.INSTANCE.available()) {
            tell(ctx, "§cParticle engine unavailable (GL/GPU too old or shaders not compiled; F3+T to recompile).§r");
            return false;
        }
        return true;
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String message) {
        tell(ctx, "§7", message);
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String prefix, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(prefix + message), false);
    }
}
