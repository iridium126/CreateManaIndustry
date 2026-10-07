package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PackageMovingContactShaderTest {
    @Test void knownStaticObstructionsSlideCarriedBodiesInsteadOfFreezingThem() throws Exception {
        String shader = shader("moving_carry.comp");
        assertTrue(shader.contains("sweepCarryWorld"));
        assertTrue(shader.contains("remaining-=normal*into"), "carry motion must remove only its blocked normal component");
        assertTrue(shader.contains("if(!sweepCarryWorld(b,b.positionMass.xyz,shift,carried))freezeForWorldCollision"),
                "only missing or unsupported static collision coverage remains fail-closed");
        assertFalse(shader.contains("closest<.99999"), "a known wall collision is resolved by sliding, not a freeze sentinel");
    }

    @Test void solvableStaticContactCorrectionIsAppliedAlongsideMovingSupport() throws Exception {
        String shader = shader("moving_contacts.comp");
        assertTrue(shader.contains("if(!solveWorld(b,correction,velocity,worldGround,material)){freezeForWorldCollision"));
        assertTrue(shader.contains("b.positionMass.xyz+=correction;"));
        assertFalse(shader.contains("length(correction)>1e-4&&grounded"),
                "a valid static-world depenetration must not freeze a body merely because it also touches a moving source");
    }

    @Test void movingBvhTraversalBudgetCoversTheConfiguredGeometryCapacity() throws Exception {
        String shader = shader("moving_contacts.comp");
        assertTrue(shader.contains("MAX_BOXES=16384"));
        assertTrue(shader.contains("visits>32768u"), "a valid maximum-sized BVH must not freeze every touching package as an overflow");
    }

    private static String shader(String name) throws IOException {
        String path = "/assets/createmanaindustry/shaders/particles/packages/" + name;
        try (var input = PackageMovingContactShaderTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing shader resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
