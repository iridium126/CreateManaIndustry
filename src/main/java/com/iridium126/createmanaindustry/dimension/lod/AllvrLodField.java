package com.iridium126.createmanaindustry.dimension.lod;

import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandLayout;

/** Conservative coverage: datapack density can carve caves anywhere inside an island. */
public final class AllvrLodField {
    private final AllvrIslandFieldGenerator generator;

    public AllvrLodField(AllvrIslandFieldGenerator generator) { this.generator = generator; }

    public long[] compute(int level, int originX, int originY, int originZ, int dim) {
        int size = AllvrLodBands.cellBlocks(level);
        int bx = originX * size, by = originY * size, bz = originZ * size;
        long[] words = new long[(dim * dim * dim + 63) >> 6];
        for (var island : generator.islandsForBox(bx - size, by - size, bz - size,
                bx + (dim + 1) * size, by + (dim + 1) * size, bz + (dim + 1) * size)) {
            double radius = AllvrIslandLayout.MAX_RADIUS;
            int xMin = Math.max(0, (int) Math.floor((island.cx() - radius - bx) / size) - 2);
            int xMax = Math.min(dim - 1, (int) Math.floor((island.cx() + radius - bx) / size) + 1);
            int zMin = Math.max(0, (int) Math.floor((island.cz() - radius - bz) / size) - 2);
            int zMax = Math.min(dim - 1, (int) Math.floor((island.cz() + radius - bz) / size) + 1);
            int yMin = Math.max(0, Math.floorDiv(island.minY() - by, size) - 2);
            int yMax = Math.min(dim - 1, Math.floorDiv(island.maxY() - by, size) + 1);
            for (int z = zMin; z <= zMax; z++) for (int x = xMin; x <= xMax; x++) {
                double x0 = bx + x * (double) size, z0 = bz + z * (double) size;
                for (int y = yMin; y <= yMax; y++) {
                    double y0 = by + y * (double) size;
                    if (island.intersects(x0 - size, y0 - size, z0 - size, x0 + 2 * size, y0 + 2 * size, z0 + 2 * size)) {
                        int index = (y * dim + z) * dim + x;
                        words[index >> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
        return words;
    }
}
