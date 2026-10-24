package com.iridium126.createmanaindustry.compat;

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.common.lib.HexBlocks;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.compat.hextrick.data.*;
import com.iridium126.createmanaindustry.compat.hextrick.execution.*;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.*;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import dev.enjarai.trickster.block.ModBlocks;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.item.component.FragmentComponent;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.fragment.*;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Tricks;
import io.netty.buffer.Unpooled;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.bytebuf.ByteBufDeserializer;
import io.wispforest.endec.format.bytebuf.ByteBufSerializer;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

/** Explicit opt-in test fixture. No production registration or packaged classes. */
@EventBusSubscriber(modid = "createmanaindustry")
public final class HexInteropLiveProbe {
    private static final BlockPos IMPETUS = new BlockPos(0, 80, 0), SLATE = new BlockPos(1, 80, 0), TARGET = new BlockPos(2, 80, 0);
    private static final Path OUTPUT = Path.of("../evidence");
    private static final Object OWNER = new Object();
    private static final Map<UUID, CastChain> SCENES = new HashMap<>();
    private static final Map<UUID, Integer> PENDING = new HashMap<>();
    private static SpellExecutor restored;
    private static CastingOrigin savedOrigin;
    private static int retries;

    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        MinecraftServer server = event.getServer();
        Files.createDirectories(OUTPUT);
        ServerLevel world = server.overworld();
        world.getChunkAt(SLATE);
        world.setChunkForced(0, 0, true);
        if (System.getProperty("cmi.interop.phase", "write").equals("write")) {
            world.setBlockAndUpdate(IMPETUS, HexBlocks.IMPETUS_REDSTONE.get().defaultBlockState());
            world.setBlockAndUpdate(SLATE, HexBlocks.SLATE.get().defaultBlockState());
            world.setBlockAndUpdate(TARGET, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            var knot = new ItemStack(ModItems.AMETHYST_KNOT);
            knot.set(ModComponents.MANA, knot.get(ModComponents.MANA).with(new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.TRADITIONAL))));
            ((SlateKnotHolder) world.getBlockEntity(SLATE)).createmanaindustry$setKnot(knot);
            savedOrigin = origin(world, null);
            var light = new SpellPart(new PatternGlyph(Tricks.CONJURE_LIGHT.getPattern()), List.of(new SpellPart(new VectorFragment(new Vector3d(2.5, 80.5, .5)))));
            var delay = new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()), List.of(new SpellPart(new NumberFragment(2))));
            var executor = new BridgeSpellExecutor(new DefaultSpellExecutor(new SpellPart(light, List.of(delay)), List.of()), new BridgeExecutionContext(server, savedOrigin));
            if (executor.run(source(world, savedOrigin)).isPresent()) throw new AssertionError("Restart fixture completed before save");
            new CircleSlateManaPool(savedOrigin).use(ManaVariant.of(Manae.TRADITIONAL), 7000, world);
            var buffer = Unpooled.buffer();
            try {
                SpellExecutor.INTERNAL_ENDEC.encode(SerializationContext.empty(), ByteBufSerializer.of(buffer), executor);
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
                Files.write(OUTPUT.resolve("executor.bin"), bytes);
            } finally { buffer.release(); }
            Files.writeString(OUTPUT.resolve("origin.json"), savedOrigin.encode());
            Files.writeString(OUTPUT.resolve("write.json"), "{\"initial\":64000,\"spentBeforeRestart\":7000,\"savedBalance\":57000}");
            System.out.println("CMI_INTEROP_RESTART_WRITE_READY balance=57000");
        } else {
            savedOrigin = CastingOrigin.decode(Files.readString(OUTPUT.resolve("origin.json")));
            if (savedOrigin.source().resolve(server).status() != CircleSourceDescriptor.Status.READY) throw new AssertionError("Actual world reload lost source identity");
            if (new CircleSlateManaPool(savedOrigin).get(world) != 57000) throw new AssertionError("Actual restart changed live knot balance");
            var buffer = Unpooled.wrappedBuffer(Files.readAllBytes(OUTPUT.resolve("executor.bin")));
            try { restored = SpellExecutor.INTERNAL_ENDEC.decode(SerializationContext.empty(), ByteBufDeserializer.of(buffer)); }
            finally { buffer.release(); }
        }
    }
    private static CastingOrigin origin(ServerLevel world, UUID owner) {
        var state = new CircleExecutionState(IMPETUS, Direction.EAST, new HashSet<>(List.of(IMPETUS, SLATE)), SLATE,
                Direction.WEST, new CastingImage(), owner, null, 0L, new BlockPos(8, 88, 8), new BlockPos(-8, 75, -8)) {};
        var captured = com.iridium126.createmanaindustry.compat.hextrick.execution.HexCastingOrigins.capture(new CircleCastEnv(world, state));
        return new CastingOrigin(captured.source(), captured.facing(), captured.minX(), captured.minY(), captured.minZ(),
                captured.maxX(), captured.maxY(), captured.maxZ(), owner);
    }
    private static CircleSpellSource source(ServerLevel world, CastingOrigin origin) { return new CircleSpellSource(new AnchoredCircleCastEnv(world, origin), origin); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        var server = event.getServer();
        if (restored != null) {
            if (++retries > 100) throw new AssertionError("Restart executor failed to finish");
            if (restored.run(source(server.overworld(), savedOrigin)).isPresent()) {
                long remaining = new CircleSlateManaPool(savedOrigin).get(server.overworld());
                if (remaining != 37000 || !server.overworld().getBlockState(TARGET).is(ModBlocks.LIGHT)) throw new AssertionError("Post-restart mana ledger/effect mismatch: " + remaining);
                Files.writeString(OUTPUT.resolve("restart.json"), "{\"passed\":true,\"restoredBalance\":57000,\"spentAfterRestart\":20000,\"finalBalance\":37000}");
                System.out.println("CMI_INTEROP_RESTART_PASS finalBalance=37000");
                restored = null;
            }
        }
        for (UUID id : List.copyOf(PENDING.keySet())) if (server.getTickCount() >= PENDING.get(id)) {
            PENDING.remove(id);
            var player = server.getPlayerList().getPlayer(id);
            if (player != null) scene(player);
        }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PENDING.put(player.getUUID(), player.getServer().getTickCount() + 40);
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cmiinterop").requires(source -> source.hasPermission(2))
                .then(Commands.literal("scene").executes(ctx -> { scene(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("cross").executes(ctx -> { var p = ctx.getSource().getPlayerOrException(); p.teleportTo(p.getServer().getLevel(Level.NETHER), 0, 90, 0, 0, 0); return 1; }))
                .then(Commands.literal("back").executes(ctx -> { var p = ctx.getSource().getPlayerOrException(); p.teleportTo(p.getServer().overworld(), .5, 82, 6.5, 180, 20); return 1; })));
    }
    private static void scene(ServerPlayer player) {
        var server = player.getServer();
        CastChain old = SCENES.remove(player.getUUID());
        if (old != null) CastChains.release(old, OWNER);
        player.teleportTo(server.overworld(), .5, 82, 6.5, 180, 20);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var battery = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));
        battery.set(HexDataComponents.MEDIA_MAX, 6400000L); battery.set(HexDataComponents.MEDIA, 6400000L);
        battery.set(CMIComponents.ANIMA.get(), 128000000L); player.getInventory().setItem(9, battery);
        var spell = new SpellPart(new ListFragment(List.of(new NumberFragment(42), new IotaFragment(IotaFragment.serializeIota(new DoubleIota(7))))));
        var mirror = new ItemStack(ModItems.MIRROR_OF_EVALUATION);
        mirror.set(ModComponents.FRAGMENT, new FragmentComponent(spell)); player.getInventory().setItem(0, mirror); player.getInventory().selected = 0;
        player.sendSystemMessage(Component.literal("CMI acceptance: Inline ").append(new InlineTrickData(spell).asText(true)));
        var broken = InlineTrickData.InlineTrickDataType.INSTANCE.getCodec().parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive("broken")).getOrThrow();
        player.sendSystemMessage(Component.literal("CMI damaged placeholder: ").append(broken.asText(true)));
        var cow = EntityType.COW.create(server.overworld()); cow.setPos(1.5, 81, 3.5); cow.setNoAi(true); server.overworld().addFreshEntity(cow);
        var chain = CastChains.attach(new CastChain(), player, OWNER); SCENES.put(player.getUUID(), chain);
        var context = new BridgeExecutionContext(server, origin(server.overworld(), player.getUUID()));
        try (var scope = context.enter(); var ignored = CastChains.enter(chain)) {
            SharedFateManager.bind(player, cow, chain, 32000);
            if (!PartitionManager.create(player, chain, new PartitionBounds(4, 80, 4, 5, 82, 5))) throw new AssertionError("Live partition scene creation failed");
        }
        System.out.println("CMI_INTEROP_LIVE_SCENE player=" + player.getUUID() + " links=" + SharedFateManager.bindingCount(server));
    }
}
