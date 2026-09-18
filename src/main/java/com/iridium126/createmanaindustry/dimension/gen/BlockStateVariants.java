package com.iridium126.createmanaindustry.dimension.gen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.level.block.state.BlockState;

/** Weighted, deterministic block-state choices used by developer palettes. */
public final class BlockStateVariants {
    public record Variant(BlockState state, int weight) {}

    private final List<Variant> variants;
    private final long totalWeight;

    private BlockStateVariants(List<Variant> variants) {
        this.variants=List.copyOf(variants);
        long total=0;
        for(Variant variant:variants) total=Math.addExact(total,variant.weight());
        totalWeight=total;
    }

    public static BlockStateVariants parse(JsonElement element,String label,Consumer<BlockState> validator) {
        var variants=new ArrayList<Variant>();
        if(!element.isJsonObject()) throw new IllegalArgumentException(label+" must be a block state object or a variants object");
        JsonObject object=element.getAsJsonObject();
        if(object.has("variants")) {
            for(String key:object.keySet()) if(!key.equals("variants"))
                throw new IllegalArgumentException(label+" variants object has unknown key: "+key);
            JsonElement raw=object.get("variants");
            if(!raw.isJsonArray() || raw.getAsJsonArray().isEmpty())
                throw new IllegalArgumentException(label+" variants must be a non-empty array");
            JsonArray array=raw.getAsJsonArray();
            for(int i=0;i<array.size();i++) {
                JsonElement entryElement=array.get(i);
                if(!entryElement.isJsonObject()) throw new IllegalArgumentException(label+" variant "+i+" must be an object");
                JsonObject entry=entryElement.getAsJsonObject();
                if(!entry.has("weight") || !entry.has("state"))
                    throw new IllegalArgumentException(label+" variant "+i+" requires weight and state");
                for(String key:entry.keySet()) if(!key.equals("weight") && !key.equals("state"))
                    throw new IllegalArgumentException(label+" variant "+i+" has unknown key: "+key);
                int weight=parseWeight(entry.get("weight"),label+" variant "+i);
                BlockState state=parseState(entry.get("state"),label+" variant "+i);
                validator.accept(state);
                variants.add(new Variant(state,weight));
            }
        } else {
            BlockState state=parseState(element,label);
            validator.accept(state);
            variants.add(new Variant(state,1));
        }
        return new BlockStateVariants(variants);
    }

    public BlockState choose(long randomSeed) {
        long mixed=mix64(randomSeed);
        long rejection=Long.remainderUnsigned(-totalWeight,totalWeight);
        while(Long.compareUnsigned(mixed,rejection)<0) mixed=mix64(mixed);
        long pick=Long.remainderUnsigned(mixed,totalWeight);
        for(Variant variant:variants) {
            if(pick<variant.weight()) return variant.state();
            pick-=variant.weight();
        }
        throw new AssertionError("Weighted palette selection overflow");
    }

    public List<Variant> variants() { return variants; }

    public static long coordinateSeed(long worldSeed,int x,int y,int z) {
        long value=worldSeed ^ (long)x*0x9E3779B97F4A7C15L ^ (long)y*0xC2B2AE3D27D4EB4FL
            ^ (long)z*0x165667B19E3779F9L;
        return mix64(value);
    }

    private static int parseWeight(JsonElement element,String label) {
        if(!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(label+" weight must be a positive integer");
        double value=element.getAsDouble();
        if(!Double.isFinite(value) || value<1 || value>Integer.MAX_VALUE || value!=Math.rint(value))
            throw new IllegalArgumentException(label+" weight must be a positive integer");
        return (int)value;
    }

    private static BlockState parseState(JsonElement element,String label) {
        if(!element.isJsonObject()) throw new IllegalArgumentException(label+" state must be an object");
        try {
            return BlockState.CODEC.parse(JsonOps.INSTANCE,element).getOrThrow();
        } catch(RuntimeException exception) {
            throw new IllegalArgumentException(label+" contains an invalid block state",exception);
        }
    }

    private static long mix64(long value) {
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }
}
