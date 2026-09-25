package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class HexJitConfig {
    public enum Mode { OFF, PROFILE, AUTO }
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.EnumValue<Mode> MODE = BUILDER.comment("OFF: original interpreter; PROFILE: count sites without compiling; AUTO: tiered JIT.")
            .defineEnum("mode", Mode.AUTO);
    private static final ModConfigSpec.IntValue THRESHOLD = BUILDER.defineInRange("hotThreshold", 64, 1, 1000000);
    private static final ModConfigSpec.IntValue ENTRIES = BUILDER.defineInRange("maxUnits", 1024, 1, 65536);
    private static final ModConfigSpec.IntValue BYTES = BUILDER.defineInRange("bytecodeBudgetMiB", 16, 1, 256);
    private static final ModConfigSpec.BooleanValue ACTIONS = BUILDER.comment("Experimental ordinary Action call-site compilation. Disabled until it demonstrates a net benefit.")
            .define("compileActions", false);
    private static final ModConfigSpec.BooleanValue OBSERVERS = BUILDER.comment("Permit skipping explicitly declared SkippablePostExecutionObserver listeners on compiled steps.")
            .define("skipDeclaredObservers", false);
    public static final ModConfigSpec SPEC = BUILDER.build();
    public static volatile Mode mode = Mode.AUTO;
    public static volatile int threshold = 64, maxUnits = 1024;
    public static volatile long byteBudget = 16L << 20;
    public static volatile boolean compileActions, skipObservers;
    private HexJitConfig() {}
    public static void reload() {
        mode = MODE.get(); threshold = THRESHOLD.get(); maxUnits = ENTRIES.get();
        byteBudget = (long) BYTES.get() << 20;
        compileActions = ACTIONS.get(); skipObservers = OBSERVERS.get();
        HexJitRuntime.invalidate("configuration changed");
    }
}
