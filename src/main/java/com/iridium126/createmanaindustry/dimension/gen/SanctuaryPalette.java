package com.iridium126.createmanaindustry.dimension.gen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Named, strict classpath BlockState palette, following the existing EpicRedwood palette contract. */
public final class SanctuaryPalette {
    public static final List<String> KEYS=List.of("path","path_slab","bridge_deck","bridge_slab",
        "timber","rope_vertical","light","root_bark","root_core","root_moss","support",
        "wall_base","wall_light","wall_dark","rope_horizontal","lantern");
    // Horizontal ropes are selected by bridge context, so keep them out of the numeric MJ symbol table.
    private static final List<String> SYMBOL_KEYS=KEYS.subList(0,KEYS.indexOf("rope_horizontal"));
    private final BlockStatePalette palette;
    public SanctuaryPalette(JsonObject json) {
        palette = BlockStatePalette.parse(json, KEYS, "sanctuary", (key, state) -> {
                if(state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity())
                    throw new IllegalArgumentException("Sanctuary material must be registered, non-air, dry and without block entity: "+key);
                if(key.equals("rope_vertical") && !(state.getBlock() instanceof FenceBlock))
                    throw new IllegalArgumentException("Vertical rope material must be a fence: "+key);
                if(key.equals("rope_horizontal") && (!(state.getBlock() instanceof FenceGateBlock)
                        || !state.hasProperty(FenceGateBlock.OPEN) || state.getValue(FenceGateBlock.OPEN)
                        || !state.hasProperty(FenceGateBlock.POWERED) || state.getValue(FenceGateBlock.POWERED)))
                    throw new IllegalArgumentException("Horizontal rope material must be a closed, unpowered fence gate: "+key);
                if(key.equals("lantern") && !(state.getBlock() instanceof LanternBlock))
                    throw new IllegalArgumentException("Lantern material must be a lantern: "+key);
                if(key.endsWith("_slab") && (!state.hasProperty(BlockStateProperties.SLAB_TYPE)
                    || state.getValue(BlockStateProperties.SLAB_TYPE)!=SlabType.BOTTOM))
                    throw new IllegalArgumentException("Half-step material must be a bottom slab: "+key);
                if(!key.endsWith("_slab") && !key.equals("rope_vertical") && !key.equals("rope_horizontal") && !key.equals("lantern") && !key.equals("light")
                        && !state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO))
                    throw new IllegalArgumentException("Structural sanctuary material needs a full collision cube: "+key);
            });
    }
    public BlockState get(String key) { return get(key, 0L); }
    public BlockState get(String key, long randomSeed) { return palette.get(key, randomSeed); }
    BlockState verticalRope(long randomSeed) { return get("rope_vertical", randomSeed); }
    BlockState lantern(long randomSeed) {
        BlockState state=get("lantern", randomSeed);
        if(state.hasProperty(BlockStateProperties.HANGING))
            state=state.setValue(BlockStateProperties.HANGING,true);
        if(state.hasProperty(BlockStateProperties.WATERLOGGED))
            state=state.setValue(BlockStateProperties.WATERLOGGED,false);
        return state;
    }
    BlockState horizontalRope(Direction connectionDirection, long randomSeed) {
        BlockState state=get("rope_horizontal", randomSeed);
        // A gate connects along the axis perpendicular to FACING; rotate once so that
        // its connection axis follows the bridge extension direction.
        if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            state=state.setValue(BlockStateProperties.HORIZONTAL_FACING, connectionDirection.getClockWise());
        if(state.hasProperty(FenceGateBlock.OPEN)) state=state.setValue(FenceGateBlock.OPEN,false);
        if(state.hasProperty(FenceGateBlock.POWERED)) state=state.setValue(FenceGateBlock.POWERED,false);
        return state;
    }
    public BlockState material(int symbol) {
        return material(symbol, 0L);
    }
    public BlockState material(int symbol, long randomSeed) {
        return switch(symbol) {
            case SanctuaryNetwork.NONE -> null;
            case SanctuaryNetwork.CLEAR -> Blocks.AIR.defaultBlockState();
            default -> get(SYMBOL_KEYS.get(symbol-2), randomSeed);
        };
    }
    static SanctuaryPalette bundled() { return Holder.INSTANCE; }
    private static final class Holder {
        static final SanctuaryPalette INSTANCE=load();
        private static SanctuaryPalette load() {
            try(var stream=SanctuaryPalette.class.getResourceAsStream("/data/createmanaindustry/markov/sanctuary_palette.json")) {
                if(stream==null) throw new IllegalStateException("Missing sanctuary palette");
                return new SanctuaryPalette(JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject());
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }
}
