package com.iridium126.createmanaindustry.client.particles.packages;

/** Separate JVM probe: compileOnly must stay genuinely optional at runtime. */
public final class PackageSableAbsentProbe {
    public static void main(String[] args) {
        var loader = PackageSableAbsentProbe.class.getClassLoader();
        for (String path : new String[]{"dev/ryanhcode/sable/Sable.class", "dev/ryanhcode/sable/companion/math/Pose3dc.class"}) {
            if (loader.getResource(path) != null) throw new AssertionError("Unexpected optional runtime dependency: " + path);
        }
        if (PackageMovingCollisionSources.optionalBridge(false, null) != null) throw new AssertionError("Absent Sable linked");
        System.out.println("Package Sable absent: direct bridge gate passed without Sable/companion");
    }
}
