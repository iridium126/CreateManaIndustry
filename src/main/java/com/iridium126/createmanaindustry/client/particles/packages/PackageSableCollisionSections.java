package com.iridium126.createmanaindustry.client.particles.packages;

/** Null-safe section iteration for Sable plot chunks, whose section arrays may be sparse. */
final class PackageSableCollisionSections {
    private PackageSableCollisionSections() {}

    static <T> int nextNonNull(T[] sections, int index) {
        while (index < sections.length && sections[index] == null) index++;
        return index;
    }
}
