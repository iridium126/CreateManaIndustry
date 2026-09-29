package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PackageSableCompatibilityTest {
    @Test void absentModDoesNotLinkTheTypedAdapter() {
        // Sable is compileOnly. This JVM has the math companion for other tests,
        // but none of the Sable sublevel/physics/loader implementation classes.
        assertNull(getClass().getResource("/dev/ryanhcode/sable/Sable.class"));
        assertNull(getClass().getResource("/dev/ryanhcode/sable/sublevel/ClientSubLevel.class"));
        assertNull(PackageMovingCollisionSources.optionalBridge(false, null));
    }

    @Test void commonSourcesHaveNoExternalSableTypesAndAdapterHasNoReflection() throws Exception {
        String root = "/com/iridium126/createmanaindustry/client/particles/packages/";
        for (String suffix : new String[]{"", "$Base", "$CreateSource", "$OptionalBridge"}) {
            try (var stream = getClass().getResourceAsStream(root + "PackageMovingCollisionSources" + suffix + ".class")) {
                assertNotNull(stream);
                assertFalse(new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1).contains("dev/ryanhcode/sable/"));
            }
        }
        for (String name : new String[]{"PackageSableCollisionSources", "PackageSableCollisionSources$Source", "PackageSablePose"}) {
            try (var stream = getClass().getResourceAsStream(root + name + ".class")) {
                assertNotNull(stream);
                String bytecode = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("java/lang/reflect/"));
                assertFalse(bytecode.contains("forName"));
                assertFalse(bytecode.contains("getMethod"));
                assertFalse(bytecode.contains("getField"));
            }
        }
    }
}
