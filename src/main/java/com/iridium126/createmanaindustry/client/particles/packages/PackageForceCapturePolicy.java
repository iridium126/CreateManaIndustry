package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.Supplier;

/** Keeps a transient optional world-source failure local to that source and capture tick. */
final class PackageForceCapturePolicy {
    static PackageForceScene.Source optionalSource(Supplier<PackageForceScene.Source> capture) {
        try {
            return capture.get();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private PackageForceCapturePolicy() {}
}
