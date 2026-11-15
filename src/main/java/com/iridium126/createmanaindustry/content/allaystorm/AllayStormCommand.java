package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.infrastructure.output.CMICommandOutput;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side Allay Storm command (op, permission 2). The storm is
 * server-authoritative: the definition persists in the level attachment and
 * every player sees the same swarm.
 * <pre>
 *   /cmi particle allaystorm [count &lt;=131072]   (default 2048)
 *   /cmi particle allaystorm status
 *   /cmi particle allaystorm stop
 * </pre>
 * Re-running the command on an ACTIVE storm is an anchor-only retarget: the
 * count argument is ignored, identity/HP/seed and generated progress survive.
 * Only {@code stop} + re-create resets everything. The radius derives from the
 * population ({@link AllayStormData#vortexRadius}) and the angular velocity
 * follows the current radius ({@link AllayStormData#vortexOmega}); neither is a
 * command argument. Feedback reports the CANONICAL attachment values, never
 * the raw argument, and is rendered through {@link CMICommandOutput}.
 */
@EventBusSubscriber(modid = com.iridium126.createmanaindustry.CreateManaIndustry.MODID)
public final class AllayStormCommand {

    private AllayStormCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(commandTree());
    }

    static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        var storm = Commands.literal("allaystorm")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> allayStorm(ctx, 2048))
                .then(Commands.literal("stop").executes(AllayStormCommand::allayStormStop))
                .then(Commands.literal("status").executes(AllayStormCommand::allayStormStatus))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                        .executes(ctx -> allayStorm(ctx, IntegerArgumentType.getInteger(ctx, "count"))));
        return Commands.literal("cmi").then(Commands.literal("particle").then(storm));
    }

    private static int allayStorm(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        boolean wasActive = level.getData(CMIAttachments.STORM_DATA.get()).active;
        AllayStormManager.setStorm(level,
                BlockPos.containing(src.getPosition().add(0.0, 1.0, 0.0)), count);
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        // The argument is clamped by setStorm and ignored for active storms, so
        // every derived value must come from the persisted attachment.
        float radius = AllayStormData.vortexRadius(data.count);
        float spin = Math.abs(AllayStormData.vortexOmega(radius, 0));
        CMICommandOutput.sendReport(src, CMICommandOutput.STORM,
                wasActive ? "Allay Storm anchor retargeted" : "Allay Storm created",
                "action", wasActive ? "retarget (existing storm preserved)" : "new storm",
                "members", data.count,
                "final members", data.finalCount,
                "growth", data.growthPerSecond > 0.0
                        ? String.format(Locale.ROOT, "%.1f/s", data.growthPerSecond) : "disabled",
                "radius", String.format(Locale.ROOT, "%.1f blocks (derived)", radius),
                "spin", String.format(Locale.ROOT, "±%.3f rad/s at current radius (derived)", spin),
                "anchor", data.anchor.getX() + ", " + data.anchor.getY() + ", " + data.anchor.getZ(),
                "state", "persisted in the level, synced to clients");
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStatus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        AllayStormData data = level.getData(CMIAttachments.STORM_DATA.get());
        List<String> lines = new ArrayList<>();
        lines.add("active : " + data.active);
        lines.add("members : " + data.count);
        lines.add("final members : " + data.finalCount);
        lines.add("growth : " + (data.growthPerSecond > 0.0
                ? String.format(Locale.ROOT, "%.1f/s", data.growthPerSecond) : "disabled"));
        lines.add("radius : " + String.format(Locale.ROOT, "%.1f blocks (derived)",
                AllayStormData.vortexRadius(data.count)));
        lines.add("anchor : " + data.anchor.getX() + ", " + data.anchor.getY() + ", " + data.anchor.getZ());
        lines.addAll(AllayStormManager.attackStatus(level));
        CMICommandOutput.sendReportLines(ctx.getSource(), CMICommandOutput.STORM, "Allay Storm status", lines);
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStop(CommandContext<CommandSourceStack> ctx) {
        AllayStormManager.stopStorm(ctx.getSource().getLevel());
        CMICommandOutput.send(ctx.getSource(), CMICommandOutput.STORM, CMICommandOutput.Outcome.OK,
                "Allay Storm dispersed; persisted state cleared.");
        return Command.SINGLE_SUCCESS;
    }
}
