package com.iridium126.createmanaindustry.dimension.gen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Named, strict classpath BlockState palette, following the existing EpicRedwood palette contract. */
public final class SanctuaryPalette {
    public static final List<String> KEYS=List.of("path","path_slab","bridge_deck","bridge_slab",
        "timber","rope","light","root_bark","root_core","root_moss","support",
        "wall_base","wall_light","wall_dark");
    private final Map<String,BlockState> states;
    public SanctuaryPalette(JsonObject json) {
        var parsed=new HashMap<String,BlockState>();
        for(String key:json.keySet()) if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown sanctuary material: "+key);
        for(String key:KEYS) {
            if(!json.has(key)) throw new IllegalArgumentException("Missing sanctuary material: "+key);
            BlockState state=BlockState.CODEC.parse(JsonOps.INSTANCE,json.get(key)).getOrThrow();
            if(state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity())
                throw new IllegalArgumentException("Sanctuary material must be registered, non-air, dry and without block entity: "+key);
            if(key.endsWith("_slab") && (!state.hasProperty(BlockStateProperties.SLAB_TYPE)
                || state.getValue(BlockStateProperties.SLAB_TYPE)!=SlabType.BOTTOM))
                throw new IllegalArgumentException("Half-step material must be a bottom slab: "+key);
            if(!key.endsWith("_slab") && !key.equals("rope") && !key.equals("light")
                    && !state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO))
                throw new IllegalArgumentException("Structural sanctuary material needs a full collision cube: "+key);
            parsed.put(key,state);
        }
        states=Map.copyOf(parsed);
    }
    public BlockState get(String key) { return Objects.requireNonNull(states.get(key),key); }
    public BlockState material(int symbol) {
        return switch(symbol) {
            case SanctuaryNetwork.NONE -> null;
            case SanctuaryNetwork.CLEAR -> Blocks.AIR.defaultBlockState();
            default -> get(KEYS.get(symbol-2));
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
