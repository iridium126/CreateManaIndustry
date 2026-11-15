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
import com.iridium126.createmanaindustry.infrastructure.output.CMILog;

/**
 * Process-local switches read before Mixin transforms any game classes.
 * <p>
 * All three are flat entries of the {@code [experimental]} table: the server switch
 * {@code serverPackages}, the client switch {@code clientPackages} (client file only) and the
 * JIT mode {@code hexJitMode}. Missing or invalid values disable the feature.
 */
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
        Config clientConfig = client ? readFile(local) : null;
        return new Settings(packagesEnabled(serverConfig, server, "serverPackages"),
                packagesEnabled(clientConfig, local, "clientPackages"), hexJitMode(serverConfig, server));
    }

    private static Config readFile(Path file) {
        try (var reader = Files.newBufferedReader(file)) {
            return new TomlParser().parse(reader);
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException | ParsingException failure) {
            LOGGER.warn(CMILog.CONFIG + "Cannot read CMI experimental settings from {}: {}", file, failure.getMessage());
            return null;
        }
    }

    private static boolean packagesEnabled(Config config, Path file, String key) {
        Object value = setting(config, file, key);
        if (value instanceof Boolean enabled) return enabled;
        if (value != null) invalid(file, key, "a boolean");
        return false;
    }

    private static HexJitMode hexJitMode(Config config, Path file) {
        Object value = setting(config, file, "hexJitMode");
        if (value instanceof String mode) {
            try {
                return HexJitMode.valueOf(mode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Invalid modes stay off.
            }
        }
        if (value != null) invalid(file, "hexJitMode", "OFF, PROFILE or AUTO");
        return HexJitMode.OFF;
    }

    /** Reads one entry of the {@code [experimental]} table from the raw TOML tree. */
    private static Object setting(Config config, Path file, String key) {
        if (config == null) return null;
        Object experimental = config.get("experimental");
        if (experimental == null) return null;
        if (!(experimental instanceof Config table)) {
            invalid(file, "", "a TOML table");
            return null;
        }
        return table.get(key);
    }

    private static void invalid(Path file, String key, String expected) {
        LOGGER.warn(CMILog.CONFIG + "Invalid CMI setting experimental{} in {}: expected {}; feature disabled",
                key.isEmpty() ? "" : "." + key, file, expected);
    }
}
