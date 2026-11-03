package com.iridium126.createmanaindustry.client.particles.engine.hardware;

/** Integer spatial data suitable for lossless media transport. Authority stays
 * in current FP32 positions; these bounds are only a conservative broad phase. */
public final class ConservativeCoordinates {
    public static final double STEP=1.0/64;
    private ConservativeCoordinates(){}
    public static int quantize(float position){
        if(!Float.isFinite(position))throw new IllegalArgumentException("Unknown coordinate");
        double value=Math.floor((double)position/STEP);
        if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Coordinate exceeds bounded quantization");
        return (int)value;
    }
    public static double lower(int coordinate){return Math.nextDown(coordinate*STEP);}
    public static double upper(int coordinate){return Math.nextUp(((double)coordinate+1)*STEP);}
    public static boolean contains(int coordinate,float current){return Float.isFinite(current)&&current>=lower(coordinate)&&current<=upper(coordinate);}
    public static boolean reusable(int cachedIdentity,int currentIdentity,int[] cached,float[] current){
        if(cachedIdentity!=currentIdentity||cached.length!=3||current.length!=3)return false;
        for(int k=0;k<3;k++)if(!contains(cached[k],current[k]))return false;
        return true;
    }
    public static boolean overlaps(int[] cell,float[] origin,float radius){
        if(cell.length!=3||origin.length!=3||!Float.isFinite(radius)||radius<0)throw new IllegalArgumentException("Invalid broad phase");
        for(int k=0;k<3;k++){
            if(!Float.isFinite(origin[k]))return true;
            // An AABB, expanded outwards in FP64, overcovers the exact sphere.
            double pad=1e-4+(double)radius*1e-5+Math.ulp(origin[k]);
            if(upper(cell[k])<Math.nextDown((double)origin[k]-radius-pad)||lower(cell[k])>Math.nextUp((double)origin[k]+radius+pad))return false;
        }
        return true;
    }
}
