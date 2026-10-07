package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.function.Function;

/** Bounded owner-thread numeric signatures; no packed BlockPos (Sable plot Y can exceed 2047). */
final class PackageMovingCollisionCells {
    static final int MAX_SECTIONS=1024;
    record Cell(int x,int y,int z) {}
    private record Section(int x,int y,int z) {}
    private static final class Captured {
        final BitSet visited=new BitSet(4096);
        final Map<Integer,List<PackageMovingGeometry.Box>> boxes=new HashMap<>();
    }
    private final Map<Section,Captured> sections=new HashMap<>();
    private int boxCount;
    private boolean overflow;
    void clear(){sections.clear();boxCount=0;overflow=false;}
    private Captured section(int x,int y,int z) {
        if(overflow)return null;
        Section key=new Section(x,y,z);Captured found=sections.get(key);
        if(found!=null)return found;
        if(sections.size()==MAX_SECTIONS){sections.clear();boxCount=0;overflow=true;return null;}
        found=new Captured();sections.put(key,found);return found;
    }
    void emptySection(int x,int y,int z){
        Captured found=section(x,y,z);if(found==null)return;
        for(var boxes:found.boxes.values())boxCount-=boxes.size();found.boxes.clear();found.visited.set(0,4096);
    }
    void record(int x,int y,int z,List<PackageMovingGeometry.Box> boxes) {
        Captured found=section(x>>4,y>>4,z>>4);if(found==null)return;
        int at=(y&15)<<8|(z&15)<<4|(x&15);var old=found.boxes.get(at);
        int next=boxCount+boxes.size()-(old==null?0:old.size());
        if(next>PackageMovingGeometry.MAX_CAPTURE_BOXES){sections.clear();boxCount=0;overflow=true;return;}
        boxCount=next;found.visited.set(at);
        if(boxes.isEmpty())found.boxes.remove(at);else found.boxes.put(at,List.copyOf(boxes));
    }
    List<PackageMovingGeometry.Box> get(int x,int y,int z) {
        Captured found=sections.get(new Section(x>>4,y>>4,z>>4));
        int at=(y&15)<<8|(z&15)<<4|(x&15);
        return found==null||!found.visited.get(at)?null:found.boxes.getOrDefault(at,List.of());
    }
    boolean changed(int x,int y,int z,PackageMovingGeometry.Bounds bounds,
                    Function<Cell,List<PackageMovingGeometry.Box>> capture) {
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            long nx=(long)x+dx,ny=(long)y+dy,nz=(long)z+dz;
            if(nx<Integer.MIN_VALUE||nx>Integer.MAX_VALUE||ny<Integer.MIN_VALUE||ny>Integer.MAX_VALUE||nz<Integer.MIN_VALUE||nz>Integer.MAX_VALUE)continue;
            int cx=(int)nx,cy=(int)ny,cz=(int)nz;if(!bounds.contains(cx,cy,cz))continue;
            var known=get(cx,cy,cz);if(known==null)return true;
            var live=capture.apply(new Cell(cx,cy,cz));if(live==null||!known.equals(live))return true;
        }
        return false;
    }
}
