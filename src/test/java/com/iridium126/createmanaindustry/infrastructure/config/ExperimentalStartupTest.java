package com.iridium126.createmanaindustry.infrastructure.config;

import static com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.HexJitMode.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentalStartupTest {
    @TempDir Path config;

    @Test void legacyKeysAndMissingFilesLeaveExperimentsOff() throws IOException {
        var defaults = new ExperimentalStartup.Settings(false, false, OFF);
        assertEquals(defaults, ExperimentalStartup.read(config, true));
        server("[experimental.gpuPackages]\nenabled = true\n[experimental.hexJit]\nmode = 'AUTO'\n");
        client("[experimental.gpuPackages]\nenabled = true\n[packages]\npackageGpuAuthority = true\n");
        assertEquals(defaults, ExperimentalStartup.read(config, true));
    }

    @Test void localClientAndServerSwitchesAreIndependent() throws IOException {
        server("[experimental]\nhexJitMode = 'PROFILE'\n");
        client("[experimental]\nclientPackages = true\n");
        assertEquals(new ExperimentalStartup.Settings(false, true, PROFILE), ExperimentalStartup.read(config, true));
        assertEquals(new ExperimentalStartup.Settings(false, false, PROFILE), ExperimentalStartup.read(config, false));
        server("[experimental]\nserverPackages = true\nhexJitMode = 'AUTO'\n");
        client("[experimental]\nclientPackages = false\n");
        assertEquals(new ExperimentalStartup.Settings(true, false, AUTO), ExperimentalStartup.read(config, true));
    }

    @Test void invalidValuesDoNotEnableAnExperiment() throws IOException {
        server("[experimental]\nserverPackages = 'true'\nhexJitMode = 'AUTO'\n");
        client("[experimental]\nclientPackages = 'true'\n");
        assertEquals(new ExperimentalStartup.Settings(false, false, AUTO), ExperimentalStartup.read(config, true));
        server("[experimental]\nserverPackages = true\nhexJitMode = 1\n");
        assertEquals(new ExperimentalStartup.Settings(true, false, OFF), ExperimentalStartup.read(config, true));
    }

    private void server(String contents) throws IOException {
        Files.writeString(config.resolve(ExperimentalStartup.SERVER_FILE), contents);
    }

    private void client(String contents) throws IOException {
        Files.writeString(config.resolve(ExperimentalStartup.CLIENT_FILE), contents);
    }
}
