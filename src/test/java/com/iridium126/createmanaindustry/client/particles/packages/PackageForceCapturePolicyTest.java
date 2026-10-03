package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageForceCapturePolicyTest {
    private static PackageForceScene.Source fan(double x) {
        return new PackageForceScene.Source(PackageForceScene.FAN,x,0,0,x+1,1,1,x,0,0,1,1,0,0,4);
    }

    @Test void unavailableOptionalSableSourceIsOmittedWithoutPoisoningTheSharedPhysicsInput() {
        var captured = new ArrayList<PackageForceScene.Source>();
        var first = PackageForceCapturePolicy.optionalSource(() -> fan(0));
        var unavailable = PackageForceCapturePolicy.optionalSource(() -> {
            throw new IllegalStateException("Sable sub-level pose is being replaced");
        });
        var last = PackageForceCapturePolicy.optionalSource(() -> fan(4));
        if (first != null) captured.add(first);
        if (unavailable != null) captured.add(unavailable);
        if (last != null) captured.add(last);

        var snapshot = PackageForceScene.bake(12,captured,0,0,0);
        assertEquals(2,snapshot.sources());
        assertEquals(3,snapshot.nodes());
    }

    @Test void optionalLinkageFailureIsAlsoLocalToThatSource() {
        var source = PackageForceCapturePolicy.optionalSource(() -> {
            throw new NoClassDefFoundError("optional Sable API unavailable during sub-level update");
        });
        assertNull(source);
    }
}
