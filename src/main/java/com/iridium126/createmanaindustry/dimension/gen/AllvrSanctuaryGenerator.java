package com.iridium126.createmanaindustry.dimension.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Chunk-local writes only: independent of generation order and neighboring chunk availability. */
final class AllvrSanctuaryGenerator {
    private final AllvrSanctuary field;
    private final int seaLevel;
    private final BlockState fluid;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    AllvrSanctuaryGenerator(long seed, int seaLevel, BlockState fluid) {
        field = new AllvrSanctuary(seed);
        this.seaLevel = seaLevel;
        this.fluid = fluid;
    }

    void sculpt(ChunkAccess chunk, boolean restoreRing) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z < z0 + 16; z++) for (int x = x0; x < x0 + 16; x++) {
            double radius = Math.hypot(x, z);
            if (radius >= AllvrSanctuary.BLEND_RADIUS || restoreRing && (radius < 91 || radius > 214)) continue;
            int old = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            int top = AllvrSanctuary.surface(x, z, old);
            var c = field.column(x, z);
            boolean ring = radius >= 91 && radius <= 214;
            int bottom = ring ? -16 : Math.min(old, top) - 8;
            for (int y = Math.max(bottom, chunk.getMinBuildHeight()); y < chunk.getMaxBuildHeight(); y++) {
                BlockState state;
                if (ring) state = state(c, y);
                else if (y > top) state = y <= seaLevel ? fluid : AIR;
                else if (y == top) state = (top < seaLevel ? Blocks.DIRT : Blocks.GRASS_BLOCK).defaultBlockState();
                else if (y >= top - 3) state = Blocks.DIRT.defaultBlockState();
                else state = Blocks.STONE.defaultBlockState();
                pos.set(x, y, z);
                if (chunk.getBlockState(pos) != state) {
                    chunk.removeBlockEntity(pos);
                    chunk.setBlockState(pos, state, false);
                }
            }
        }
    }

    private BlockState state(AllvrSanctuary.Column c, int y) {
        if (y > 95) return AIR;
        if (c.masonry(y)) {
            long h = field.hash(c.x(), y, c.z());
            if (y == 95) return (c.weathered() ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
            return ((h & 15) < 3 ? Blocks.MOSSY_STONE_BRICKS : (h & 15) == 3
                ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
        }
        if (field.cavity(c, y)) return AIR;
        if (y == 95) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (y > 91 && c.radius() < 96) return Blocks.DIRT.defaultBlockState();
        double strata = field.noise(c.x() / 27.0, y / 5.0, c.z() / 27.0);
        return (strata > .32 ? Blocks.CALCITE : strata < -.35 ? Blocks.ANDESITE
            : Blocks.DRIPSTONE_BLOCK).defaultBlockState();
    }

    private boolean solid(int x, int y, int z) {
        return solid(field.column(x, z), y);
    }

    private boolean solid(AllvrSanctuary.Column c, int y) {
        if (y > 95) return false;
        return c.masonry(y) || !field.cavity(c, y);
    }

    void decorate(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z < z0 + 16; z++) for (int x = x0; x < x0 + 16; x++) {
            var c = field.column(x, z);
            if (c.radius() < 91 || c.radius() > 214) continue;
            for (int y = c.floor(); y <= 95; y++) {
                boolean here = solid(c, y), above = solid(c, y + 1);
                double choice = AllvrSanctuary.unit(field.hash(x, y, z));
                if (here && !above && !AllvrSanctuary.suppressSideGroundVegetation(c, y)
                        && !(c.bridge() && y == 95 && c.pathCenter()) && choice < .19) {
                    chunk.setBlockState(pos.set(x, y, z), Blocks.MOSS_BLOCK.defaultBlockState(), false);
                    BlockState plant = (choice < .018 ? Blocks.FLOWERING_AZALEA : choice < .045 ? Blocks.AZALEA
                        : choice < .075 ? Blocks.OXEYE_DAISY : choice < .11 ? Blocks.FERN
                        : Blocks.SHORT_GRASS).defaultBlockState();
                    chunk.setBlockState(pos.set(x, y + 1, z), plant, false);
                }
                // Hanging berries originate on a real ceiling, with a head at the free tip.
                if (!here && above && choice < .14) {
                    int length = 4 + (int) (choice * 60);
                    for (int d = 0; d < length && y - d > c.floor() && !solid(c, y - d); d++) {
                        boolean tip = d == length - 1 || solid(c, y - d - 1);
                        var vine = (tip ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).defaultBlockState()
                            .setValue(BlockStateProperties.BERRIES, d % 3 == 0 || tip);
                        chunk.setBlockState(pos.set(x, y - d, z), vine, false);
                    }
                }
                if (!here && !above && choice < .065 && y < 94) {
                    for (Direction face : Direction.Plane.HORIZONTAL) {
                        if (!solid(x + face.getStepX(), y, z + face.getStepZ())) continue;
                        var vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(face), true);
                        chunk.setBlockState(pos.set(x, y, z), vine, false);
                        break;
                    }
                }
            }
        }
        trees(chunk);
    }

    /** Sparse small rooted trees, stamped from a shared anchor lattice across chunk borders. */
    private void trees(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int gz = Math.floorDiv(z0 - 3, 16); gz <= Math.floorDiv(z0 + 18, 16); gz++)
        for (int gx = Math.floorDiv(x0 - 3, 16); gx <= Math.floorDiv(x0 + 18, 16); gx++) {
            long h = field.hash(gx, 871, gz);
            if (AllvrSanctuary.unit(h) > .38) continue;
            int ax = gx * 16 + 4 + (int) (h & 7), az = gz * 16 + 4 + (int) ((h >>> 4) & 7);
            var c = field.column(ax, az);
            if (c.radius() < 93 || c.radius() > 210 || c.bridge()) continue;
            int ground = 95;
            while (ground > c.floor() && !solid(ax, ground, az)) ground--;
            if (AllvrSanctuary.suppressSideGroundVegetation(c, ground)) continue;
            // Require a complete root shelf and headroom before stamping any part.
            boolean fits = true;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                if (!solid(ax + dx, ground, az + dz)) fits = false;
                for (int dy = 1; dy <= 7; dy++) if (solid(ax + dx, ground + dy, az + dz)) fits = false;
            }
            if (!fits) continue;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                int x = ax + dx, z = az + dz;
                if (x < x0 || x >= x0 + 16 || z < z0 || z >= z0 + 16) continue;
                for (int dy = 0; dy <= 7; dy++) {
                    BlockState b = null;
                    if (dx == 0 && dz == 0 && dy == 0) b = Blocks.ROOTED_DIRT.defaultBlockState();
                    else if (dx == 0 && dz == 0 && dy <= 5) b = Blocks.OAK_LOG.defaultBlockState();
                    else if (dy >= 4 && dx * dx + dz * dz + (dy - 5) * (dy - 5) <= 7)
                        b = ((h & 16) == 0 ? Blocks.AZALEA_LEAVES : Blocks.FLOWERING_AZALEA_LEAVES)
                            .defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
                    if (b != null) chunk.setBlockState(pos.set(x, ground + dy, z), b, false);
                }
            }
        }
    }
}
