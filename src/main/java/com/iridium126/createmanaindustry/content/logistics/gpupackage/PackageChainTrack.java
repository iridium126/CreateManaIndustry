package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;

/** Immutable track header, built on the owning world thread. Kinematics follow Create 6.0.10;
 * package contents, address matching and route/port side effects stay outside GPU geometry. */
public record PackageChainTrack(Vec3 start,Vec3 end,float radius,float length,float rate,
                                boolean looping,boolean reversed,float yaw,int firstNode,int nodes,long revision) {
    public PackageChainTrack {
        if(start==null || end==null || !finite(start) || !finite(end) || !Float.isFinite(radius) || radius<0
                || !Float.isFinite(length) || length<0 || !Float.isFinite(rate) || !Float.isFinite(yaw)
                || firstNode<0 || nodes<0 || nodes>32 || revision<=0 || !looping && rate<0)
            throw new IllegalArgumentException("Chain track header");
    }
    public static PackageChainTrack loop(BlockPos conveyor,float kineticSpeed,int firstNode,int nodes,long revision) {
        float speed=kineticSpeed/360f;
        return new PackageChainTrack(Vec3.atBottomCenterOf(conveyor).add(0,6/16f,0),Vec3.ZERO,.875f,0,
                (speed/(Mth.PI*1.5f))*360f*20f,true,kineticSpeed<0,0,firstNode,nodes,revision);
    }
    /** Must be called on the world's owning thread; prepareStats does no per-package scan. */
    public static PackageChainTrack travel(ChainConveyorBlockEntity conveyor,BlockPos connection,int firstNode,int nodes,long revision) {
        conveyor.prepareStats();var stats=conveyor.connectionStats.get(connection);
        if(stats==null)throw new IllegalArgumentException("Unknown Create chain connection");
        Vec3 direction=stats.end().subtract(stats.start()).normalize();
        float yaw=Mth.wrapDegrees((float)Mth.atan2(direction.x,direction.z)*Mth.RAD_TO_DEG-90);
        return new PackageChainTrack(stats.start(),stats.end(),0,stats.chainLength(),Math.abs(conveyor.getSpeed()/360f)*20,
                false,conveyor.reversed,yaw,firstNode,nodes,revision);
    }
    public void write(ByteBuffer target,double ox,double oy,double oz) {
        if(target==null || !target.isDirect() || target.isReadOnly() || target.remaining()!=64
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Chain header output/origin");
        var v=target.duplicate().order(ByteOrder.nativeOrder());int p=v.position();
        float[] values={(float)(start.x-ox),(float)(start.y-oy),(float)(start.z-oz),radius,
                looping?0:(float)(end.x-ox),looping?0:(float)(end.y-oy),looping?0:(float)(end.z-oz),length,
                rate,looping?1:0,reversed?1:0,yaw};
        for(float value:values)if(!Float.isFinite(value))throw new IllegalArgumentException("Chain header requires a local origin");
        for(int i=0;i<values.length;i++)v.putFloat(p+i*4,values[i]);
        v.putInt(p+48,firstNode).putInt(p+52,nodes).putLong(p+56,revision);
    }
    private static boolean finite(Vec3 v){return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);}
}
