package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Three signed axes; avoids BlockPos's packed vertical range in the Allay dimension. */
public record PackageRegion(int x,int y,int z) {
    public static final int SIZE=64;
    public static PackageRegion at(PackageLease.Pose p) {return new PackageRegion(axis(p.x()),axis(p.y()),axis(p.z()));}
    private static int axis(double coordinate) {
        double cell=Math.floor(coordinate/SIZE);
        if(!Double.isFinite(cell) || cell<Integer.MIN_VALUE || cell>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Package region outside supported coordinates");
        return (int)cell;
    }
    public double originX(){return (double)x*SIZE;}
    public double originY(){return (double)y*SIZE;}
    public double originZ(){return (double)z*SIZE;}
    public boolean contains(PackageLease.Pose p){return equals(at(p));}
}
