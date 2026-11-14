package com.iridium126.createmanaindustry.infrastructure.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.HexJitMode;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Process-local switches read before Mixin transforms any game classes. */
public final class ExperimentalStartup {
    public static final String SERVER_FILE = "createmanaindustry-server.toml";
    public static final String CLIENT_FILE = "createmanaindustry-client.toml";
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Settings(boolean serverPackages, boolean clientPackages, HexJitMode hexJitMode) {
        public boolean sharedPackages() {
            return serverPackages || clientPackages;
        }
    }

    private static class Snapshot {
        static final Settings SETTINGS = read(FMLPaths.CONFIGDIR.get(), FMLLoader.getDist() == Dist.CLIENT);
    }

    private ExperimentalStartup() {}

    public static Settings settings() {
        return Snapshot.SETTINGS;
    }

    static Settings read(Path directory, boolean client) {
        Path server = directory.resolve(SERVER_FILE);
        Path local = directory.resolve(CLIENT_FILE);
        Config serverConfig = readFile(server);
        return new Settings(packagesEnabled(serverConfig, server),
                client && packagesEnabled(readFile(local), local), hexJitMode(serverConfig, server));
    }

    private static Config readFile(Path file) {
        try (var reader = Files.newBufferedReader(file)) {
            return new TomlParser().parse(reader);
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException | ParsingException failure) {
            LOGGER.warn("Cannot read CMI experimental settings from {}: {}", file, failure.getMessage());
            return null;
        }
    }

    private static boolean packagesEnabled(Config config, Path file) {
        Object value = setting(config, file, "gpuPackages", "enabled");
        if (value instanceof Boolean enabled) return enabled;
        if (value != null) invalid(file, "gpuPackages.enabled", "a boolean");
        return false;
    }

    private static HexJitMode hexJitMode(Config config, Path file) {
        Object value = setting(config, file, "hexJit", "mode");
        if (value instanceof String mode) {
            try {
                return HexJitMode.valueOf(mode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Invalid modes stay off.
            }
        }
        if (value != null) invalid(file, "hexJit.mode", "OFF, PROFILE or AUTO");
        return HexJitMode.OFF;
    }

    private static Object setting(Config config, Path file, String section, String key) {
        if (config == null) return null;
        Object experimental = config.get("experimental");
        if (experimental == null) return null;
        if (!(experimental instanceof Config experiments)) {
            invalid(file, "", "a TOML table");
            return null;
        }
        Object group = experiments.get(section);
        if (group == null) return null;
        if (group instanceof Config settings) return settings.get(key);
        invalid(file, section, "a TOML table");
        return null;
    }

    private static void invalid(Path file, String key, String expected) {
        LOGGER.warn("Invalid CMI setting experimental{} in {}: expected {}; feature disabled",
                key.isEmpty() ? "" : "." + key, file, expected);
    }
}
