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
    private static final int LANTERN_MIN_CLEARANCE = 5;
    private static final int LANTERN_MAX_CLEARANCE = 8;
    private static final double LANTERN_CHANCE = .05;
    // Inner root-access joins recess to roughly r=74; include their full path width.
    private static final double LANTERN_SCAN_MIN_RADIUS = 68;
    private static final double LANTERN_SCAN_MAX_RADIUS = 230;
    final AllvrSanctuary field;
    final SanctuaryNetwork network;
    private final SanctuaryPalette palette;
    private final SanctuaryGeodes geodes;
    private final long seed;
    private final int seaLevel;
    private final BlockState fluid;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    BlockState material(int symbol) { return palette.material(symbol); }

    BlockState material(int symbol, int x, int y, int z) {
        return palette.material(symbol, BlockStateVariants.coordinateSeed(seed, x, y, z));
    }

    AllvrSanctuaryGenerator(long seed, int seaLevel, BlockState fluid) {
        this(seed,seaLevel,fluid,SanctuaryPalette.bundled());
    }

    AllvrSanctuaryGenerator(long seed, int seaLevel, BlockState fluid, SanctuaryPalette palette) {
        this.seed = seed;
        field = new AllvrSanctuary(seed);
        network = new SanctuaryNetwork(seed);
        this.palette = palette;
        geodes = new SanctuaryGeodes(this);
        this.seaLevel = seaLevel;
        this.fluid = fluid;
    }

    void sculpt(ChunkAccess chunk, boolean restoreRing) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z < z0 + 16; z++) for (int x = x0; x < x0 + 16; x++) {
            double radius = Math.hypot(x, z);
            if (radius >= AllvrSanctuary.BLEND_RADIUS || restoreRing && (radius < 70 || radius > 248)) continue;
            int old = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            int top = AllvrSanctuary.surface(x, z, old);
            var c = field.column(x, z);
            boolean ring = radius >= 70 && radius <= 248;
            int bottom = ring ? -16 : Math.min(old, top) - 8;
            for (int y = Math.max(bottom, chunk.getMinBuildHeight()); y < chunk.getMaxBuildHeight(); y++) {
                BlockState state;
                if (ring) state = naturalState(c, y);
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

    BlockState naturalState(AllvrSanctuary.Column c, int y) {
        if (y > 95) return AIR;
        if (field.cavity(c, y)) return AIR;
        if (y == 95) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (y > 91 && c.radius() < 96) return Blocks.DIRT.defaultBlockState();
        double strata = field.noise(c.x() / 27.0, y / 5.0, c.z() / 27.0);
        return palette.get(strata > .32 ? "wall_light" : strata < -.35 ? "wall_dark" : "wall_base",
            BlockStateVariants.coordinateSeed(seed, c.x(), y, c.z()));
    }

    void structures(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk) {
        geodes.apply(level, chunk);
        if (!network.hasChunk(chunk.getPos().x, chunk.getPos().z)) return;
        var pos = new BlockPos.MutableBlockPos();
        for (int y=-16;y<=110;y++) for (int z=chunk.getPos().getMinBlockZ();z<chunk.getPos().getMinBlockZ()+16;z++)
        for (int x=chunk.getPos().getMinBlockX();x<chunk.getPos().getMinBlockX()+16;x++) {
            var state=structureState(network.get(x,y,z), x, y, z);
            if (state!=null) chunk.setBlockState(pos.set(x,y,z),state,false);
        }
    }

    private BlockState structureState(int symbol, int x, int y, int z) {
        long randomSeed=BlockStateVariants.coordinateSeed(seed, x, y, z);
        if(symbol==SanctuaryNetwork.ROPE) {
            if(network.isVerticalRope(x,y,z)) {
                BlockState state=palette.verticalRope(randomSeed);
                for(Direction direction:Direction.Plane.HORIZONTAL)
                    state=state.setValue(fenceProperty(direction),network.verticalRopeConnects(x,y,z,direction));
                return state;
            }
            Direction direction=network.ropeConnectionDirection(x,y,z);
            return palette.horizontalRope(direction==null ? Direction.NORTH : direction, randomSeed);
        }
        return palette.material(symbol, randomSeed);
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty fenceProperty(Direction direction) {
        return switch(direction) {
            case NORTH -> BlockStateProperties.NORTH;
            case EAST -> BlockStateProperties.EAST;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            default -> throw new IllegalArgumentException("Fence connections must be horizontal");
        };
    }

    private boolean solid(int x, int y, int z) {
        return solid(field.column(x, z), y);
    }

    private boolean solid(AllvrSanctuary.Column c, int y) {
        int material=network.get(c.x(),y,c.z());
        if (material!=SanctuaryNetwork.NONE) return material!=SanctuaryNetwork.CLEAR && material!=SanctuaryNetwork.ROPE;
        return y <= 95 && !field.cavity(c, y);
    }

    /** Side vines need a full face; a bottom slab leaves a visible half-block gap. */
    static boolean canHostSideVine(int material) {
        return material!=SanctuaryNetwork.PATH_SLAB && material!=SanctuaryNetwork.DECK_SLAB;
    }

    private boolean vineSupport(int x, int y, int z) {
        return canHostSideVine(network.get(x,y,z)) && solid(x,y,z);
    }

    void decorate(ChunkAccess chunk) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z < z0 + 16; z++) for (int x = x0; x < x0 + 16; x++) {
            var c = field.column(x, z);
            if (c.radius() < 91 || c.radius() > 214) continue;
            for (int y = c.floor(); y <= 95; y++) {
                int reserved=network.get(x,y,z);
                if (geodes.near(x,y,z) || reserved==SanctuaryNetwork.CLEAR || reserved==SanctuaryNetwork.ROPE || reserved==SanctuaryNetwork.LIGHT) continue;
                boolean here = solid(c, y), above = solid(c, y + 1);
                double choice = AllvrSanctuary.unit(field.hash(x, y, z));
                if (here && !above && !AllvrSanctuary.suppressSideGroundVegetation(c, y)
                        && network.get(x,y+1,z)!=SanctuaryNetwork.CLEAR
                        && network.get(x,y,z)!=SanctuaryNetwork.ROPE && network.get(x,y,z)!=SanctuaryNetwork.LIGHT && choice < .19) {
                    chunk.setBlockState(pos.set(x, y, z), Blocks.MOSS_BLOCK.defaultBlockState(), false);
                    BlockState plant = (choice < .018 ? Blocks.FLOWERING_AZALEA : choice < .045 ? Blocks.AZALEA
                        : choice < .075 ? Blocks.OXEYE_DAISY : choice < .11 ? Blocks.FERN
                        : Blocks.SHORT_GRASS).defaultBlockState();
                    chunk.setBlockState(pos.set(x, y + 1, z), plant, false);
                }
                // Hanging berries originate on a real ceiling, with a head at the free tip.
                if (!here && above && choice < .14) {
                    int length = 4 + (int) (choice * 60);
                    for (int d = 0; d < length && y - d > c.floor() && !solid(c, y - d) && network.get(x,y-d,z)!=SanctuaryNetwork.CLEAR; d++) {
                        boolean tip = d == length - 1 || solid(c, y - d - 1);
                        var vine = (tip ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).defaultBlockState()
                            .setValue(BlockStateProperties.BERRIES, d % 3 == 0 || tip);
                        chunk.setBlockState(pos.set(x, y - d, z), vine, false);
                    }
                }
                if (!here && !above && choice < .065 && y < 94) {
                    for (Direction face : Direction.Plane.HORIZONTAL) {
                        if (!vineSupport(x + face.getStepX(), y, z + face.getStepZ())) continue;
                        var vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(face), true);
                        chunk.setBlockState(pos.set(x, y, z), vine, false);
                        break;
                    }
                }
            }
        }
        trees(chunk);
        pathLanterns(chunk);
    }

    private void pathLanterns(ChunkAccess chunk) {
        int x0=chunk.getPos().getMinBlockX(), z0=chunk.getPos().getMinBlockZ();
        var pos=new BlockPos.MutableBlockPos();
        for(int z=z0;z<z0+16;z++) for(int x=x0;x<x0+16;x++) {
            var c=field.column(x,z);
            // Includes the inward excursion of layer ramps while avoiding a full
            // height scan in unrelated outer terrain chunks.
            if(c.radius()<LANTERN_SCAN_MIN_RADIUS || c.radius()>LANTERN_SCAN_MAX_RADIUS) continue;
            for(int y=Math.max(c.floor(),chunk.getMinBuildHeight());y<=95;y++) {
                int material=network.get(x,y,z);
                if(material!=SanctuaryNetwork.PATH && material!=SanctuaryNetwork.PATH_SLAB) continue;
                // Seeded world-space randomness is stable across chunk borders while
                // allowing lamps on ramps and other inter-layer passages.
                if(AllvrSanctuary.unit(BlockStateVariants.coordinateSeed(seed,x,y,z))>=LANTERN_CHANCE) continue;
                int ceiling=findPathCeiling(c,x,y,z);
                int clearance=ceiling-y;
                // Berry vines illuminate the broad galleries; lanterns belong only
                // to the low, enclosed rock passages with usable headroom.
                if(ceiling<0 || clearance<LANTERN_MIN_CLEARANCE || clearance>LANTERN_MAX_CLEARANCE) continue;
                int chainLength=lanternChainLength(seed,x,ceiling,z);
                int chainY=ceiling-1,lanternY=chainY-chainLength;
                if(!isPathClearanceCell(network.get(x,chainY,z))
                    || !isPathClearanceCell(network.get(x,lanternY,z))) continue;
                if(!chunk.getBlockState(pos.set(x,chainY,z)).isAir()
                    || !chunk.getBlockState(pos.set(x,lanternY,z)).isAir()) continue;
                if(chainLength==1) {
                    BlockState chain=Blocks.CHAIN.defaultBlockState()
                        .setValue(BlockStateProperties.AXIS,Direction.Axis.Y);
                    chunk.setBlockState(pos.set(x,chainY,z),chain,false);
                }
                chunk.setBlockState(pos.set(x,lanternY,z),palette.lantern(
                    BlockStateVariants.coordinateSeed(seed,x,lanternY,z)),false);
            }
        }
    }

    private static boolean isPathClearanceCell(int material) {
        return material==SanctuaryNetwork.CLEAR;
    }

    /** Deterministic 50/50 choice so adjacent chunks do not change fixture layout. */
    static int lanternChainLength(long worldSeed,int x,int ceilingY,int z) {
        return (int)(BlockStateVariants.coordinateSeed(worldSeed,x,ceilingY,z)&1L);
    }

    private int findPathCeiling(AllvrSanctuary.Column c,int x,int floorY,int z) {
        for(int y=floorY+3;y<=95;y++) {
            int material=network.get(x,y,z);
            if(material==SanctuaryNetwork.NONE && !field.cavity(c,y)) return y;
            // Root paths can run beneath the generated root crown rather than
            // beneath limestone. Treat a solid root/support voxel as their roof.
            if(material>=SanctuaryNetwork.ROOT_BARK && material<=SanctuaryNetwork.SUPPORT) return y;
        }
        return -1;
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
            if (c.radius() < 93 || c.radius() > 210 || network.get(ax,95,az)!=SanctuaryNetwork.NONE) continue;
            int ground = 95;
            while (ground > c.floor() && !solid(ax, ground, az)) ground--;
            if (AllvrSanctuary.suppressSideGroundVegetation(c, ground)) continue;
            // Require a complete root shelf and headroom before stamping any part.
            boolean fits = true;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                if (!solid(ax + dx, ground, az + dz)) fits = false;
                for (int dy = 1; dy <= 7; dy++) if (solid(ax + dx, ground + dy, az + dz)) fits = false;
            }
            if (geodes.near(ax,ground,az)) continue;
            for (int dz=-2;dz<=2;dz++) for (int dx=-2;dx<=2;dx++) for (int dy=0;dy<=7;dy++)
                if (network.get(ax+dx,ground+dy,az+dz)==SanctuaryNetwork.CLEAR) fits=false;
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
