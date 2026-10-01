package net.irisshaders.iris;

import java.nio.file.Path;
import net.irisshaders.iris.config.IrisConfig;

/**
 * Standalone transformer harness only: supplies Iris's debug configuration and
 * logger without starting Minecraft/NeoForge. TransformPatcher, its transformer
 * classes and IrisConfig are the unmodified classes from the installed Iris jar.
 * This does not validate game startup, mixin application or pipeline integration.
 * Never added to main/test source sets or the shipped mod.
 */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("CMI Iris transformer validation");
    private static final IrisConfig CONFIG = new IrisConfig(
            Path.of("build/package-iris-validation/unused.properties"),
            Path.of("build/package-iris-validation/unused-excluded.json"));
    public static IrisConfig getIrisConfig() { return CONFIG; }
    private Iris() {}
}
