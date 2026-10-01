package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayList;
import java.util.List;

/** Bounded, owner-thread copy of a mod-provided unit-voxel dynamic collider. */
public final class PackageMovingDynamicBoxes {
    static final int MAX_BOXES_PER_BLOCK=256;
    @FunctionalInterface public interface Builder {void build(Sink sink);}
    public interface Sink {
        void add(double minX,double minY,double minZ,double maxX,double maxY,double maxZ);
        void clear();
    }
    private PackageMovingDynamicBoxes() {}

    public static List<PackageMovingGeometry.Box> capture(Builder builder,int blockX,int blockY,int blockZ,
            int originX,int originY,int originZ,float friction) {
        var boxes=new ArrayList<PackageMovingGeometry.Box>(4);boolean[] rejected={
                !Float.isFinite(friction)||friction<0};
        Sink sink=new Sink() {
            @Override public void add(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
                if(rejected[0])return;
                if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)
                        ||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)
                        ||minX<0||minY<0||minZ<0||maxX>1||maxY>1||maxZ>1
                        ||minX>=maxX||minY>=maxY||minZ>=maxZ
                        ||boxes.size()>=MAX_BOXES_PER_BLOCK) {
                    rejected[0]=true;boxes.clear();return;
                }
                boxes.add(new PackageMovingGeometry.Box((float)((double)blockX-originX+minX),
                        (float)((double)blockY-originY+minY),(float)((double)blockZ-originZ+minZ),
                        (float)((double)blockX-originX+maxX),(float)((double)blockY-originY+maxY),
                        (float)((double)blockZ-originZ+maxZ),friction,0));
            }
            @Override public void clear(){boxes.clear();rejected[0]=!Float.isFinite(friction)||friction<0;}
        };
        try {builder.build(sink);} catch(RuntimeException|LinkageError unavailable) {rejected[0]=true;}
        if(rejected[0])return List.of(new PackageMovingGeometry.Box(blockX-originX,blockY-originY,blockZ-originZ,
                blockX-originX+1,blockY-originY+1,blockZ-originZ+1,
                Float.isFinite(friction)&&friction>=0?friction:.6f,PackageCollisionCache.UNSUPPORTED));
        return List.copyOf(boxes);
    }
}
