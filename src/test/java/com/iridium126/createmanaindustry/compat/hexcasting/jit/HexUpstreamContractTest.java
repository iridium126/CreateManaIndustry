package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Keep the fail-closed JIT gates enabled for the audited dependency, without launching Minecraft. */
class HexUpstreamContractTest {
    @Test void hexalTickFingerprintsMatchThePreparedJar() throws Exception {
        var field = HexJitMixinChecks.class.getDeclaredField("HEXAL_TICK_HASHES");
        field.setAccessible(true);
        for (var entry : ((Map<?, ?>) field.get(null)).entrySet()) {
            String name = (String) entry.getKey();
            assertEquals(entry.getValue(), digest(name), name);
        }
    }

    @Test void allHexcastingFingerprintsMatchTheResolvedJar() throws Exception {
        int verified = 0;
        for (var field : HexJitMixinChecks.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            for (var entry : ((Map<?, ?>) field.get(null)).entrySet()) {
                if (!(entry.getKey() instanceof String name) || !name.startsWith("at/petrak/hexcasting/")
                        || !(entry.getValue() instanceof String hash)) continue;
                assertEquals(hash, digest(name), field.getName() + ": " + name);
                verified++;
            }
        }
        for (String[] pair : new String[][] {
                {"HEX_UTILS_HASH", "at/petrak/hexcasting/api/utils/HexUtils"},
                {"IOTA_TYPE_HASH", "at/petrak/hexcasting/api/casting/iota/IotaType"}}) {
            var field = HexJitMixinChecks.class.getDeclaredField(pair[0]);
            field.setAccessible(true);
            assertEquals(field.get(null), digest(pair[1]), pair[0]);
        }
        assertTrue(verified >= 30, "Missing upstream fingerprint coverage");
    }

    private static String digest(String name) throws Exception {
        try (var stream = HexUpstreamContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }
}
