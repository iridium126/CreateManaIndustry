package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Page;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Immutable input for a changed collision revision. Missing sections stay unknown. */
final class StormControlTerrain {
    private final int rows,pages;
    private final byte[] packed;
    private StormControlTerrain(int rows,int pages,byte[] packed){this.rows=rows;this.pages=pages;this.packed=packed;}
    static StormControlTerrain capture(Map<Section,Page> source){
        var lookup=new HashMap<Section,Integer>();
        var mixed=new ArrayList<Page>();
        for(var entry:source.entrySet()){
            var page=entry.getValue();
            if(page.kind()==StormCollisionPages.UNKNOWN)continue;
            int slot=page.kind();
            if(slot>=0){
                if(mixed.size()>=STORM_TERRAIN_MAX_PAGES)throw new IllegalStateException("Native Storm collision pages exceed capacity");
                slot=mixed.size();mixed.add(page);
            }
            lookup.put(entry.getKey(),slot);
        }
        if(lookup.size()>STORM_TERRAIN_MAX_ROWS/2)throw new IllegalStateException("Native Storm collision table exceeds capacity");
        var table=StormCollisionPages.table(lookup);
        int rows=table.length/4;
        if(rows>STORM_TERRAIN_MAX_ROWS)throw new IllegalStateException("Native Storm collision hash exceeds probe budget");
        var buffer=ByteBuffer.allocate(rows*STORM_TERRAIN_ROW_BYTES+mixed.size()*STORM_TERRAIN_PAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for(int entry:table)buffer.putInt(entry);
        for(var page:mixed)for(int word:page.words())buffer.putInt(word);
        return new StormControlTerrain(rows,mixed.size(),buffer.array());
    }
    int rows(){return rows;}
    int pages(){return pages;}
    int bytes(){return packed.length;}
    void write(ByteBuffer out){out.put(packed);}
}
