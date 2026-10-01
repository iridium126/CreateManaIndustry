package com.iridium126.createmanaindustry.client.particles.packages;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

/** Copies native light storage only on the client thread. Null sky layers need inherited queries. */
public final class PackageWorldLightSource implements PackageLightCache.Source {
    private final Thread owner=Thread.currentThread();
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
    public PackageWorldLightSource(ClientLevel level){this.level=level;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("World light capture off client thread");}
    @Override public byte[] copy(PackageCollisionCache.Section section) {
        owner();position.set(section.x()*16,section.y()*16,section.z()*16);
        if(!level.hasChunkAt(position))return null;
        var engine=level.getLightEngine();var pos=SectionPos.of(section.x(),section.y(),section.z());
        var block=engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
        var sky=engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
        // Absent block data is zero. Absent sky data can inherit a nonuniform upper layer.
        if(sky==null && level.dimensionType().hasSkyLight())return null;
        byte[] data=new byte[PackageLightCache.BYTES];
        if(block!=null)System.arraycopy(block.copy().getData(),0,data,0,2048);
        if(sky!=null)System.arraycopy(sky.copy().getData(),0,data,2048,2048);
        return data;
    }
    @Override public int sample(PackageCollisionCache.Section section,int index) {
        owner();position.set(section.x()*16+(index&15),section.y()*16+(index>>>8),section.z()*16+((index>>>4)&15));
        if(!level.hasChunkAt(position))return -1;
        return level.getBrightness(LightLayer.BLOCK,position)|(level.getBrightness(LightLayer.SKY,position)<<4);
    }
}
