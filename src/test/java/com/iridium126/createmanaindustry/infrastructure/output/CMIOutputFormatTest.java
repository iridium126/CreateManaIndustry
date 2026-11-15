package com.iridium126.createmanaindustry.infrastructure.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Pure contract checks for the shared output format; no Minecraft bootstrap required. */
class CMIOutputFormatTest {

    @Test
    void everyLogPrefixUsesTheSameLayout() {
        String[] prefixes = {
                CMILog.PARTICLES, CMILog.PACKAGES, CMILog.FRAMES, CMILog.CAPTURE,
                CMILog.PROFILE, CMILog.SHADERPACK, CMILog.SHADOW, CMILog.EFFECTS,
                CMILog.STORM, CMILog.RIFT, CMILog.HEX, CMILog.HEXJIT,
                CMILog.ALLVR, CMILog.YSM, CMILog.TRICKSTER, CMILog.DISPLAY,
                CMILog.CONFIG, CMILog.MIXIN, CMILog.BOOTSTRAP
        };
        Set<String> unique = new HashSet<>();
        for (String prefix : prefixes) {
            assertTrue(prefix.matches("\\[CMI/[a-z]+] "), "malformed prefix: " + prefix);
            unique.add(prefix);
        }
        assertEquals(prefixes.length, unique.size(), "log prefixes must be distinct");
    }

    @Test
    void commandModulesAreLowercaseLabels() {
        String[] modules = {
                CMICommandOutput.PARTICLES, CMICommandOutput.PACKAGES, CMICommandOutput.PROFILE,
                CMICommandOutput.SHADERPACK, CMICommandOutput.STORM, CMICommandOutput.RIFT,
                CMICommandOutput.HEXJIT, CMICommandOutput.HEX, CMICommandOutput.ALLVR,
                CMICommandOutput.YSM, CMICommandOutput.TRICKSTER, CMICommandOutput.KINETICS
        };
        for (String module : modules) {
            assertTrue(module.matches("[a-z]+"), "malformed module: " + module);
        }
    }

    @Test
    void outcomeBadgesAreStable() {
        assertEquals("OK", CMICommandOutput.Outcome.OK.name());
        assertEquals("FAIL", CMICommandOutput.Outcome.FAIL.name());
        assertEquals("WARN", CMICommandOutput.Outcome.WARN.name());
        assertEquals("INFO", CMICommandOutput.Outcome.INFO.name());
    }
}
