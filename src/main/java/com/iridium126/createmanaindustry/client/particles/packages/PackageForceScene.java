package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;

/** Immutable external sources only. Neither this worker BVH nor its GPU consumer scans packages. */
public final class PackageForceScene {
    public static final int MAX_SOURCES=4096,NODE_BYTES=32,SOURCE_BYTES=64,FRAME_BYTES=64;
    public static final int ENTITY=1,FAN=2,NOZZLE=3;
    public record Source(int kind,double x0,double y0,double z0,double x1,double y1,double z1,
                         double x,double y,double z,float strength,float fx,float fy,float fz,float range,Frame frame) {
        public Source(int kind,double x0,double y0,double z0,double x1,double y1,double z1,
                      double x,double y,double z,float strength,float fx,float fy,float fz,float range){
            this(kind,x0,y0,z0,x1,y1,z1,x,y,z,strength,fx,fy,fz,range,null);
        }
        public Source {
            if(kind!=ENTITY&&kind!=FAN&&kind!=NOZZLE||!Double.isFinite(x0+y0+z0+x1+y1+z1+x+y+z)
                    ||x0>=x1||y0>=y1||z0>=z1||!Float.isFinite(strength+fx+fy+fz+range)
                    ||strength<0||range<0||kind==ENTITY&&(strength!=0&&strength!=1)
                    ||(kind==FAN||kind==NOZZLE)&&(range<=0||fx*fx+fy*fy+fz*fz!=1)
                    ||kind==NOZZLE&&strength<=0)
                throw new IllegalArgumentException("Package force source");
        }
        public Source framed(Frame frame){return new Source(kind,x0,y0,z0,x1,y1,z1,x,y,z,strength,fx,fy,fz,range,Objects.requireNonNull(frame));}
    }
    /** Pose translation is the WORLD position of this raw plot origin, not a plot offset. */
    public record Frame(PackageMovingGeometry.Pose pose,double ox,double oy,double oz){
        public Frame{Objects.requireNonNull(pose);if(!Double.isFinite(ox+oy+oz))throw new IllegalArgumentException("Package force frame origin");}
        public org.joml.Vector3d project(double x,double y,double z){return pose.transform(x-ox,y-oy,z-oz);}
        public org.joml.Vector3d inverseRow(int axis){
            double x=axis==0?pose.xx():axis==1?pose.yx():pose.zx(),y=axis==0?pose.xy():axis==1?pose.yy():pose.zy(),z=axis==0?pose.xz():axis==1?pose.yz():pose.zz();
            return new org.joml.Vector3d(x,y,z).div(x*x+y*y+z*z);
        }
    }
    public record Snapshot(long tick,int nodes,int sources,int frames,ByteBuffer data) {
        public Snapshot {
            if(tick<0||sources<0||sources>MAX_SOURCES||nodes!=(sources==0?0:sources*2-1)
                    ||frames<0||frames>sources||!data.isDirect()||data.remaining()!=nodes*NODE_BYTES+sources*SOURCE_BYTES+frames*FRAME_BYTES)
                throw new IllegalArgumentException("Package force layout");
            data=data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
        @Override public ByteBuffer data(){return data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
    }
    private record Indexed(Source source,int index,PackageMovingGeometry.Bounds bounds) {}
    private record Node(double x0,double y0,double z0,double x1,double y1,double z1,int end,int source) {}
    /** Coarse interest filter shared by tick-thread entity queries and force-source pruning. */
    public static boolean intersectsRegions(Source source,Collection<PackageRegion> regions,double margin) {
        if(source==null||regions==null||!Double.isFinite(margin)||margin<0)throw new IllegalArgumentException("Package force interest");
        if(regions.isEmpty())return false;
        var b=bounds(source);
        for(var region:regions) {
            double x=region.originX(),y=region.originY(),z=region.originZ();
            if(b.x1()>=x-margin&&b.x0()<=x+PackageRegion.SIZE+margin
                    &&b.y1()>=y-margin&&b.y0()<=y+PackageRegion.SIZE+margin
                    &&b.z1()>=z-margin&&b.z0()<=z+PackageRegion.SIZE+margin)return true;
        }
        return false;
    }
    public static Snapshot bake(long tick,List<Source> sources,double ox,double oy,double oz) {
        if(sources.size()>MAX_SOURCES||!Double.isFinite(ox+oy+oz))throw new IllegalArgumentException("Package force capacity/origin");
        var entities=new ArrayList<Indexed>();var fans=new ArrayList<Indexed>();
        var worldBounds=new PackageMovingGeometry.Bounds[sources.size()];
        int frameCount=0;
        for(int i=0;i<sources.size();i++){var source=sources.get(i);worldBounds[i]=bounds(source);var indexed=new Indexed(source,i,worldBounds[i]);(source.kind==ENTITY?entities:fans).add(indexed);
            if((source.kind==FAN||source.kind==NOZZLE)&&source.frame!=null)frameCount++;}
        var nodes=new ArrayList<Node>(Math.max(0,sources.size()*2-1));
        if(!entities.isEmpty()&&!fans.isEmpty()){
            nodes.add(null);build(entities.toArray(Indexed[]::new),0,entities.size(),nodes,true);build(fans.toArray(Indexed[]::new),0,fans.size(),nodes,false);
            var e=nodes.get(1);var f=nodes.get(e.end);
            nodes.set(0,new Node(Math.min(e.x0,f.x0),Math.min(e.y0,f.y0),Math.min(e.z0,f.z0),Math.max(e.x1,f.x1),Math.max(e.y1,f.y1),Math.max(e.z1,f.z1),nodes.size(),0));
        }else if(!entities.isEmpty())build(entities.toArray(Indexed[]::new),0,entities.size(),nodes,true);
        else if(!fans.isEmpty())build(fans.toArray(Indexed[]::new),0,fans.size(),nodes,false);
        var out=ByteBuffer.allocateDirect(nodes.size()*NODE_BYTES+sources.size()*SOURCE_BYTES+frameCount*FRAME_BYTES).order(ByteOrder.nativeOrder());
        for(var n:nodes){xyz(out,n.x0-ox,n.y0-oy,n.z0-oz);out.putInt(n.end);xyz(out,n.x1-ox,n.y1-oy,n.z1-oz);out.putInt(n.source);}
        int frameIndex=0;
        int sourceIndex=0;
        for(var s:sources){var b=worldBounds[sourceIndex++];boolean framed=(s.kind==FAN||s.kind==NOZZLE)&&s.frame!=null;
            xyz(out,b.x0()-ox,b.y0()-oy,b.z0()-oz);out.putInt(s.kind|(framed?++frameIndex<<2:0));xyz(out,b.x1()-ox,b.y1()-oy,b.z1()-oz);out.putFloat(s.strength);
            if(s.frame==null)xyz(out,s.x-ox,s.y-oy,s.z-oz);
            else {var centre=s.frame.project(s.x,s.y,s.z);xyz(out,centre.x-ox,centre.y-oy,centre.z-oz);}
            out.putFloat(s.strength);
            if(framed&&s.kind==FAN){var p=s.frame.pose;xyz(out,p.xx()*s.fx+p.yx()*s.fy+p.zx()*s.fz,p.xy()*s.fx+p.yy()*s.fy+p.zy()*s.fz,p.xz()*s.fx+p.yz()*s.fy+p.zz()*s.fz);}
            else out.putFloat(s.fx).putFloat(s.fy).putFloat(s.fz);
            out.putFloat(s.range);}
        for(var s:sources)if((s.kind==FAN||s.kind==NOZZLE)&&s.frame!=null){
            var centre=s.frame.project((s.x0+s.x1)*.5,(s.y0+s.y1)*.5,(s.z0+s.z1)*.5);
            xyz(out,centre.x-ox,centre.y-oy,centre.z-oz);out.putInt(0);
            for(int axis=0;axis<3;axis++){var row=s.frame.inverseRow(axis);xyz(out,row.x,row.y,row.z);
                out.putFloat((float)((axis==0?s.x1-s.x0:axis==1?s.y1-s.y0:s.z1-s.z0)*.5));}}
        out.flip();return new Snapshot(tick,nodes.size(),sources.size(),frameCount,out);
    }
    private static PackageMovingGeometry.Bounds bounds(Source s){
        if(s.frame==null)return new PackageMovingGeometry.Bounds(s.x0,s.y0,s.z0,s.x1,s.y1,s.z1);
        var centre=s.frame.project((s.x0+s.x1)*.5,(s.y0+s.y1)*.5,(s.z0+s.z1)*.5);var p=s.frame.pose;
        double x=(s.x1-s.x0)*.5,y=(s.y1-s.y0)*.5,z=(s.z1-s.z0)*.5;
        double ex=Math.abs(p.xx())*x+Math.abs(p.yx())*y+Math.abs(p.zx())*z,
               ey=Math.abs(p.xy())*x+Math.abs(p.yy())*y+Math.abs(p.zy())*z,
               ez=Math.abs(p.xz())*x+Math.abs(p.yz())*y+Math.abs(p.zz())*z;
        return new PackageMovingGeometry.Bounds(centre.x-ex,centre.y-ey,centre.z-ez,centre.x+ex,centre.y+ey,centre.z+ez);
    }
    private static void xyz(ByteBuffer out,double x,double y,double z){
        if(!Float.isFinite((float)x)||!Float.isFinite((float)y)||!Float.isFinite((float)z))throw new IllegalArgumentException("Package force local origin");
        out.putFloat((float)x).putFloat((float)y).putFloat((float)z);
    }
    private static void build(Indexed[] order,int first,int end,List<Node> nodes,boolean spatial) {
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1,z1=x1;
        for(int i=first;i<end;i++){var b=order[i].bounds;x0=Math.min(x0,b.x0());y0=Math.min(y0,b.y0());z0=Math.min(z0,b.z0());x1=Math.max(x1,b.x1());y1=Math.max(y1,b.y1());z1=Math.max(z1,b.z1());}
        int index=nodes.size();nodes.add(null);
        if(end-first>1){int axis=y1-y0>x1-x0?1:0;if(z1-z0>(axis==0?x1-x0:y1-y0))axis=2;final int a=axis;
            if(spatial)Arrays.sort(order,first,end,Comparator.comparingDouble(v->a==0?v.bounds.x0()+v.bounds.x1():a==1?v.bounds.y0()+v.bounds.y1():v.bounds.z0()+v.bounds.z1()));
            int middle=(first+end)>>>1;build(order,first,middle,nodes,spatial);build(order,middle,end,nodes,spatial);}
        nodes.set(index,new Node(x0,y0,z0,x1,y1,z1,nodes.size(),end-first==1?order[first].index+1:0));
    }
    private PackageForceScene(){}
}
