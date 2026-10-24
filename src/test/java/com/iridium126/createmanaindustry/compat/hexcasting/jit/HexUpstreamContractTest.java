package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import com.iridium126.createmanaindustry.mixin.hexjit.HexJitMixinPlugin;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Keep the fail-closed JIT gates enabled for the audited dependency, without launching Minecraft. */
class HexUpstreamContractTest {
    @Test void allHexcastingFingerprintsMatchTheResolvedJar() throws Exception {
        int verified = 0;
        for (var field : HexJitMixinPlugin.class.getDeclaredFields()) {
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
            var field = HexJitMixinPlugin.class.getDeclaredField(pair[0]);
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
