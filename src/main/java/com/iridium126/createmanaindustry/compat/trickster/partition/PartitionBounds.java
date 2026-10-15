package com.iridium126.createmanaindustry.compat.trickster.partition;

/** Inclusive block coordinates. Geometry deliberately uses doubles rather than packed BlockPos. */
public record PartitionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public PartitionBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Reversed partition");
    }
    public static PartitionBounds between(double ax,double ay,double az,double bx,double by,double bz) {
        int x=floor(ax),y=floor(ay),z=floor(az),u=floor(bx),v=floor(by),w=floor(bz);
        return new PartitionBounds(Math.min(x,u),Math.min(y,v),Math.min(z,w),Math.max(x,u),Math.max(y,v),Math.max(z,w));
    }
    private static int floor(double value) {
        double f=Math.floor(value);
        if (!Double.isFinite(f) || f<Integer.MIN_VALUE || f>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid coordinate");
        return (int)f;
    }
    public long blocks() { return Math.multiplyExact(Math.multiplyExact((long)maxX-minX+1,(long)maxY-minY+1),(long)maxZ-minZ+1); }
    public long cost() { return Math.multiplyExact(blocks(),1000); }
    public double low(int axis) { return axis==0?minX:axis==1?minY:minZ; }
    public double high(int axis) { return (axis==0?(double)maxX:axis==1?(double)maxY:maxZ)+1; }
    public boolean contains(double x,double y,double z) {
        return x>=minX&&x<high(0)&&y>=minY&&y<high(1)&&z>=minZ&&z<high(2);
    }
    public boolean crosses(double ax,double ay,double az,double bx,double by,double bz) { return contains(ax,ay,az)!=contains(bx,by,bz); }
    public boolean conflicts(PartitionBounds b) {
        boolean disjoint=false,inside=true,outside=true;
        for(int i=0;i<3;i++) {
            disjoint|=high(i)<b.low(i)||b.high(i)<low(i);
            inside&=low(i)>b.low(i)&&high(i)<b.high(i);
            outside&=b.low(i)>low(i)&&b.high(i)<high(i);
        }
        return !disjoint&&!inside&&!outside;
    }
    /** Whether a body intersects a surface, excluding mere touching. */
    public boolean straddles(double[] lo,double[] hi) {
        for(int axis=0;axis<3;axis++) for(double face:new double[]{low(axis),high(axis)}) {
            int j=(axis+1)%3,k=(axis+2)%3;
            if(lo[axis]<face-1e-7&&hi[axis]>face+1e-7&&hi[j]>low(j)&&lo[j]<high(j)&&hi[k]>low(k)&&lo[k]<high(k))return true;
        }
        return false;
    }
    /** Earliest continuous contact with a finite face; catches diagonal traversal with both endpoints outside. */
    public double contact(int axis,double[] move,double[] lo,double[] hi) {
        if(move[axis]==0)return Double.POSITIVE_INFINITY;
        double earliest=Double.POSITIVE_INFINITY;
        int j=(axis+1)%3,k=(axis+2)%3;
        for(double face:new double[]{low(axis),high(axis)}) {
            double t;
            if(move[axis]>0&&hi[axis]<=face+1e-7)t=(face-hi[axis])/move[axis];
            else if(move[axis]<0&&lo[axis]>=face-1e-7)t=(face-lo[axis])/move[axis];
            else continue;
            if(t< -1e-7||t>1)continue;t=Math.max(0,t);
            if(overlapsAt(j,t,move,lo,hi)&&overlapsAt(k,t,move,lo,hi))earliest=Math.min(earliest,t);
        }
        return earliest;
    }
    private boolean overlapsAt(int axis,double t,double[] move,double[] lo,double[] hi) {
        double l=lo[axis]+move[axis]*t,h=hi[axis]+move[axis]*t;
        if(h<low(axis)-1e-7||l>high(axis)+1e-7)return false;
        // Entering an edge/corner counts; tangential touching and movement away do not.
        return !(h<=low(axis)+1e-7&&move[axis]<=0)&&!(l>=high(axis)-1e-7&&move[axis]>=0);
    }
    public String encode() { return minX+","+minY+","+minZ+","+maxX+","+maxY+","+maxZ; }
    public static PartitionBounds decode(String value) {
        String[] f=value.split(",",-1);if(f.length!=6)throw new IllegalArgumentException("Invalid partition bounds");
        return new PartitionBounds(Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]));
    }
}
