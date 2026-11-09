package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionCache;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Page;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.Section;
import java.nio.ByteBuffer;
import java.util.Map;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Tick-bound immutable page changes. Index and spatial geometry are built on the native worker. */
final class StormControlTerrain {
    private final StormCollisionCache.Changes changes;
    private final int pages;
    private StormControlTerrain(StormCollisionCache.Changes changes){
        this.changes=changes;
        pages=(int)changes.entries().stream().filter(e->e.page()!=null&&e.page().kind()>=0).count();
        if(rows()>STORM_TERRAIN_MAX_ROWS||pages>STORM_TERRAIN_MAX_PAGES)
            throw new IllegalStateException("Native Storm collision delta rows="+rows()+"/"+STORM_TERRAIN_MAX_ROWS+", pages="+pages+"/"+STORM_TERRAIN_MAX_PAGES);
    }
    static StormControlTerrain capture(StormCollisionCache.Changes changes){return new StormControlTerrain(changes);}
    static StormControlTerrain capture(Map<Section,Page> source){
        var entries=source.entrySet().stream().filter(e->e.getValue().kind()!=StormCollisionPages.UNKNOWN)
                .map(e->new StormCollisionCache.Change(e.getKey(),e.getValue())).toList();
        if(entries.size()>StormCollisionPages.MAX_SECTIONS)throw new IllegalStateException("Native Storm collision sections="+entries.size()+" exceed limit="+StormCollisionPages.MAX_SECTIONS);
        return capture(new StormCollisionCache.Changes(-1,0,true,entries));
    }
    long baseRevision(){return changes.reset()?-1:changes.baseRevision();}
    long revision(){return changes.revision();}
    int rows(){return changes.entries().size();}
    int pages(){return pages;}
    int bytes(){return rows()*STORM_TERRAIN_ROW_BYTES+pages*STORM_TERRAIN_PAGE_BYTES;}
    void write(ByteBuffer out){
        int mixed=0;
        for(var e:changes.entries()){
            var key=e.section();var page=e.page();
            out.putInt(key.x()).putInt(key.y()).putInt(key.z()).putInt(page==null?-1:page.kind()>=0?mixed++:page.kind());
        }
        for(var e:changes.entries())if(e.page()!=null&&e.page().kind()>=0)for(int word:e.page().words())out.putInt(word);
    }
}
