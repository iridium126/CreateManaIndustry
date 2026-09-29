package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PackageSablePoseTest {
    @Test void plotOriginPivotScaleAndRotationMatchActualCompanionApi() {
        Random random = new Random(0x5ab1e);
        Vector3d scratch = new Vector3d();
        for (int i = 0; i < 300; i++) {
            int ox = 20_480_000 + random.nextInt(4096), oy = random.nextInt(256), oz = -20_480_000 + random.nextInt(4096);
            var pose = new Pose3d(new Vector3d(30_000_000 + random.nextDouble(), 80, -30_000_000 + random.nextDouble()),
                    new Quaterniond().rotationXYZ(random.nextDouble() * 6, random.nextDouble() * 6, random.nextDouble() * 6),
                    new Vector3d(ox + .37, oy + .64, oz - .22),
                    new Vector3d(.25 + random.nextDouble() * 3, .25 + random.nextDouble() * 3, .25 + random.nextDouble() * 3));
            var captured = PackageSablePose.capture(pose, ox, oy, oz, scratch);
            for (int n = 0; n < 16; n++) {
                double x = random.nextDouble() * 64 - 32, y = random.nextDouble() * 64 - 32, z = random.nextDouble() * 64 - 32;
                var expected = pose.transformPosition(new Vector3d(ox + x, oy + y, oz + z), new Vector3d());
                var actual = captured.transform(x, y, z);
                assertEquals(expected.x, actual.x, 3e-8);
                assertEquals(expected.y, actual.y, 3e-8);
                assertEquals(expected.z, actual.z, 3e-8);
            }
            var previous = captured;
            pose.position().add(.5, -.25, 1);
            var current = PackageSablePose.capture(pose, ox, oy, oz, scratch);
            assertEquals(.5, current.tx() - previous.tx(), 1e-8);
            assertEquals(-.25, current.ty() - previous.ty(), 1e-8);
            assertEquals(1, current.tz() - previous.tz(), 1e-8);
        }
    }

    @Test void unsupportedTransformsRevokeCoverage() {
        var pose = new Pose3d();
        pose.scale().set(1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> PackageSablePose.capture(pose, 0, 0, 0, new Vector3d()));
        pose.scale().set(1, -1, 1);
        assertThrows(IllegalArgumentException.class, () -> PackageSablePose.capture(pose, 0, 0, 0, new Vector3d()));
    }
}
