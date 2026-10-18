package com.iridium126.createmanaindustry.compat;

import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.MishapOthersName;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.fragment.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.*;
import io.netty.buffer.Unpooled;

/** Opt-in allocation and wall-clock samples. Never included in a distributable jar. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexInteropBenchmark {
    private static final int ITERATIONS = Integer.getInteger("cmi.benchmark.iterations", 1000);
    private static volatile Object sink;
    private static Class<?> type(String legacy, String current) throws ClassNotFoundException {
        try { return Class.forName(current); } catch (ClassNotFoundException missing) { return Class.forName(legacy); }
    }
    @FunctionalInterface private interface Probe { Object run() throws Exception; }

    @GameTest(template = "hexinterop_benchmark", timeoutTicks = 1200)
    public static void benchmark(GameTestHelper helper) throws Exception {
        var iotaType = type("com.iridium126.createmanaindustry.compat.hexcasting.TrickIota", "com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota");
        var constructor = iotaType.getConstructor(SpellPart.class);
        var getter = iotaType.getMethod("getSpell");
        var values = new ArrayList<Fragment>();
        for (int i = 0; i < 128; i++) values.add(new NumberFragment(i));
        values.add(new EntityFragment(new UUID(17, 31), Component.literal("benchmark")));
        var spell = new SpellPart(new ListFragment(values));
        var iota = (Iota) constructor.newInstance(spell);
        var nbt = IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, iota).getOrThrow();
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        IotaType.TYPED_STREAM_CODEC.encode(wire, iota);
        byte[] encoded = new byte[wire.readableBytes()]; wire.readBytes(encoded); wire.release();
        helper.assertTrue(iota.equals(IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow()), "Benchmark input did not round trip");
        var probes = new LinkedHashMap<String, Probe>();
        probes.put("snapshot_129_leaves", () -> constructor.newInstance(spell));
        probes.put("snapshot_copy", () -> getter.invoke(iota));
        probes.put("nbt_encode", () -> IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, iota).getOrThrow());
        probes.put("nbt_decode", () -> IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow());
        probes.put("network_encode", () -> {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try { IotaType.TYPED_STREAM_CODEC.encode(buffer, iota); return buffer.readableBytes(); } finally { buffer.release(); }
        });
        probes.put("network_decode", () -> {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encoded), helper.getLevel().registryAccess());
            try { return IotaType.TYPED_STREAM_CODEC.decode(buffer); } finally { buffer.release(); }
        });
        probes.put("true_name_scan", () -> MishapOthersName.getTrueNameMishapFromDatum(helper.getLevel(), iota, null));
        var inlineType = type("com.iridium126.createmanaindustry.compat.hexcasting.InlineTrickData", "com.iridium126.createmanaindustry.compat.hextrick.data.InlineTrickData");
        var inline = (com.samsthenerd.inline.api.InlineData<?>) inlineType.getConstructor(SpellPart.class).newInstance(spell);
        @SuppressWarnings("rawtypes") com.mojang.serialization.Codec inlineCodec = inline.getType().getCodec();
        @SuppressWarnings("unchecked") Object inlineJson = inlineCodec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, inline).getOrThrow();
        probes.put("inline_encode", () -> inlineCodec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, inline).getOrThrow());
        probes.put("inline_decode", () -> inlineCodec.parse(com.mojang.serialization.JsonOps.INSTANCE, inlineJson).getOrThrow());
        var parseType = Class.forName("com.iridium126.createmanaindustry.compat.hexparse.TrickIotaHexParseAdapter");
        var parser = parseType.getField("INSTANCE").get(null);
        var textEncode = parseType.getMethod("parse", iotaType);
        var textDecode = parseType.getMethod("parse", String.class);
        var token = (String) textEncode.invoke(parser, iota);
        probes.put("hexparse_encode", () -> textEncode.invoke(parser, iota));
        probes.put("hexparse_decode", () -> textDecode.invoke(parser, token));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var samples = new ArrayList<Map<String, Object>>();
        for (var entry : probes.entrySet()) {
            for (int i = 0; i < ITERATIONS; i++) sink = entry.getValue().run();
            for (int round = 0; round < 7; round++) {
                long allocation = bean.getThreadAllocatedBytes(thread), start = System.nanoTime(), cpu = bean.getCurrentThreadCpuTime();
                for (int i = 0; i < ITERATIONS; i++) sink = entry.getValue().run();
                long elapsed = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(thread) - allocation;
                samples.add(Map.of("probe", entry.getKey(), "round", round, "iterations", ITERATIONS, "cpuNsPerOp", (bean.getCurrentThreadCpuTime() - cpu) / (double) ITERATIONS, "nsPerOp", elapsed / (double) ITERATIONS, "bytesPerOp", bytes / (double) ITERATIONS));
            }
        }
        var output = Path.of(System.getProperty("cmi.benchmark.output"));
        Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        report.put("label", System.getProperty("cmi.benchmark.label"));
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("heap", Runtime.getRuntime().maxMemory());
        report.put("seed", "UUID(17,31), scalars 0..127");
        report.put("samples", samples);
        Files.writeString(output.resolve("data.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        helper.succeed();
    }
}
