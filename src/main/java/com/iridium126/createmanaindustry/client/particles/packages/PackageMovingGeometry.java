package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.*;
import org.joml.Vector3d;

/** Immutable local geometry. Workers receive only these primitive values, never a world/source. */
public final class PackageMovingGeometry {
    // Sable plots commonly contain much more detail than Create contraptions. Keep
    // a bounded but substantially larger BVH so a large plot does not become an
    // unsupported whole-source collider after the worker bake.
    public static final int NODE_BYTES=48,MAX_BOXES=16384,MAX_CAPTURE_BOXES=262144;
    public record Key(int kind,UUID id){public Key{Objects.requireNonNull(id);if(kind<0||kind>1)throw new IllegalArgumentException("Moving collider kind");}}
    public record Bounds(double x0,double y0,double z0,double x1,double y1,double z1) {
        public Bounds {if(!Double.isFinite(x0+y0+z0+x1+y1+z1)||x0>x1||y0>y1||z0>z1)throw new IllegalArgumentException("Moving bounds");}
        public boolean contains(int x,int y,int z){return x>=x0&&y>=y0&&z>=z0&&x<x1&&y<y1&&z<z1;}
    }
    /** Column-major affine transform, captured in double precision around a local geometry origin. */
    public record Pose(double xx,double xy,double xz,double yx,double yy,double yz,double zx,double zy,double zz,double tx,double ty,double tz) {
        public Pose {
            double[] v={xx,xy,xz,yx,yy,yz,zx,zy,zz,tx,ty,tz};for(double n:v)if(!Double.isFinite(n))throw new IllegalArgumentException("Moving pose");
            double a=xx*xx+xy*xy+xz*xz,b=yx*yx+yy*yy+yz*yz,c=zx*zx+zy*zy+zz*zz;
            if(a<1e-8||b<1e-8||c<1e-8||a>1e6||b>1e6||c>1e6
                    ||Math.abs(xx*yx+xy*yy+xz*yz)>1e-6*Math.sqrt(a*b)
                    ||Math.abs(xx*zx+xy*zy+xz*zz)>1e-6*Math.sqrt(a*c)
                    ||Math.abs(yx*zx+yy*zy+yz*zz)>1e-6*Math.sqrt(b*c)
                    ||xx*(yy*zz-yz*zy)-yx*(xy*zz-xz*zy)+zx*(xy*yz-xz*yy)<=0)
                throw new IllegalArgumentException("Moving pose is not an orthogonal positive-scale transform");
        }
        public Vector3d transform(double x,double y,double z){return new Vector3d(xx*x+yx*y+zx*z+tx,xy*x+yy*y+zy*z+ty,xz*x+yz*y+zz*z+tz);}
        public Pose interpolate(Pose next,double fraction){
            if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Moving pose fraction");
            if(fraction==0)return this;if(fraction==1)return next;
            var a=new org.joml.Matrix3d(xx,xy,xz,yx,yy,yz,zx,zy,zz);
            var b=new org.joml.Matrix3d(next.xx,next.xy,next.xz,next.yx,next.yy,next.yz,next.zx,next.zy,next.zz);
            var rotation=a.getUnnormalizedRotation(new org.joml.Quaterniond()).slerp(b.getUnnormalizedRotation(new org.joml.Quaterniond()),fraction);
            var scale=a.getScale(new Vector3d()).lerp(b.getScale(new Vector3d()),fraction);
            var matrix=new org.joml.Matrix3d().rotation(rotation).scale(scale);
            return new Pose(matrix.m00(),matrix.m01(),matrix.m02(),matrix.m10(),matrix.m11(),matrix.m12(),matrix.m20(),matrix.m21(),matrix.m22(),
                    tx+(next.tx-tx)*fraction,ty+(next.ty-ty)*fraction,tz+(next.tz-tz)*fraction);
        }
        public void put(ByteBuffer out,double ox,double oy,double oz) {
            if(!Float.isFinite((float)(tx-ox))||!Float.isFinite((float)(ty-oy))||!Float.isFinite((float)(tz-oz)))throw new IllegalArgumentException("Moving local origin overflow");
            out.putFloat((float)xx).putFloat((float)xy).putFloat((float)xz).putInt(0);
            out.putFloat((float)yx).putFloat((float)yy).putFloat((float)yz).putInt(0);
            out.putFloat((float)zx).putFloat((float)zy).putFloat((float)zz).putInt(0);
            out.putFloat((float)(tx-ox)).putFloat((float)(ty-oy)).putFloat((float)(tz-oz)).putInt(0);
        }
    }
    public record Box(float x0,float y0,float z0,float x1,float y1,float z1,float friction,int flags) {
        public Box{if(!Float.isFinite(x0+y0+z0+x1+y1+z1+friction)||x0>=x1||y0>=y1||z0>=z1||friction<0)
            throw new IllegalArgumentException("Moving collision shape");}
    }
    public record Snapshot(long revision,ByteBuffer nodes,int count) {
        public Snapshot{if(revision<=0||count<0||nodes.remaining()!=count*NODE_BYTES)throw new IllegalArgumentException("Moving geometry layout");nodes=nodes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        @Override public ByteBuffer nodes(){return nodes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
    }
    private record Node(float x0,float y0,float z0,float x1,float y1,float z1,int end,Box box){}
    /** Balanced, stackless preorder BVH. Geometry is sorted here, never package state. */
    public static Snapshot bake(long revision,List<Box> captured) {
        if(captured.size()>MAX_CAPTURE_BOXES)throw new IllegalArgumentException("Moving capture capacity");
        Box[] boxes=captured.toArray(Box[]::new);
        // Union only exact adjacent slabs with identical material/flags. Holes and
        // stair/slab detail survive; large solid platforms need very few GPU leaves.
        for(int axis=0;axis<3;axis++)boxes=merge(boxes,axis);
        if(boxes.length>MAX_BOXES)throw new IllegalArgumentException("Moving geometry capacity after exact merging");
        var nodes=new ArrayList<Node>(Math.max(0,boxes.length*2-1));
        if(boxes.length>0)build(boxes,0,boxes.length,nodes);
        var data=ByteBuffer.allocateDirect(nodes.size()*NODE_BYTES).order(ByteOrder.nativeOrder());
        for(Node n:nodes) {
            data.putFloat(n.x0).putFloat(n.y0).putFloat(n.z0).putInt(n.end);
            data.putFloat(n.x1).putFloat(n.y1).putFloat(n.z1).putInt(n.box==null?0:1);
            data.putFloat(n.box==null?0:n.box.friction).putInt(n.box==null?0:n.box.flags).putLong(0);
        }
        data.flip();return new Snapshot(revision,data,nodes.size());
    }
    private static float coordinate(Box b,int axis,boolean high){return axis==0?(high?b.x1:b.x0):axis==1?(high?b.y1:b.y0):(high?b.z1:b.z0);}
    private static Box[] merge(Box[] boxes,int axis) {
        int a=(axis+1)%3,b=(axis+2)%3;
        Arrays.sort(boxes,(left,right)->{
            int n=Integer.compare(left.flags,right.flags);if(n!=0)return n;n=Float.compare(left.friction,right.friction);if(n!=0)return n;
            n=Float.compare(coordinate(left,a,false),coordinate(right,a,false));if(n!=0)return n;
            n=Float.compare(coordinate(left,a,true),coordinate(right,a,true));if(n!=0)return n;
            n=Float.compare(coordinate(left,b,false),coordinate(right,b,false));if(n!=0)return n;
            n=Float.compare(coordinate(left,b,true),coordinate(right,b,true));if(n!=0)return n;
            return Float.compare(coordinate(left,axis,false),coordinate(right,axis,false));
        });
        var out=new ArrayList<Box>();Box current=null;
        for(Box next:boxes) {
            if(current!=null&&current.flags==next.flags&&current.friction==next.friction
                    &&coordinate(current,a,false)==coordinate(next,a,false)&&coordinate(current,a,true)==coordinate(next,a,true)
                    &&coordinate(current,b,false)==coordinate(next,b,false)&&coordinate(current,b,true)==coordinate(next,b,true)
                    &&coordinate(next,axis,false)<=coordinate(current,axis,true)) {
                float hi=Math.max(coordinate(current,axis,true),coordinate(next,axis,true));
                current=new Box(current.x0,current.y0,current.z0,axis==0?hi:current.x1,axis==1?hi:current.y1,axis==2?hi:current.z1,current.friction,current.flags);
            } else {if(current!=null)out.add(current);current=next;}
        }
        if(current!=null)out.add(current);return out.toArray(Box[]::new);
    }
    private static void build(Box[] boxes,int first,int end,List<Node> nodes) {
        float x0=Float.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Float.NEGATIVE_INFINITY,y1=x1,z1=x1;
        for(int i=first;i<end;i++){Box b=boxes[i];x0=Math.min(x0,b.x0);y0=Math.min(y0,b.y0);z0=Math.min(z0,b.z0);x1=Math.max(x1,b.x1);y1=Math.max(y1,b.y1);z1=Math.max(z1,b.z1);}
        int index=nodes.size();nodes.add(null);
        if(end-first>1) {
            int axis=y1-y0>x1-x0?1:0;if(z1-z0>(axis==0?x1-x0:y1-y0))axis=2;final int split=axis;
            Arrays.sort(boxes,first,end,Comparator.comparingDouble(b->split==0?b.x0+b.x1:split==1?b.y0+b.y1:b.z0+b.z1));
            int middle=(first+end)>>>1;build(boxes,first,middle,nodes);build(boxes,middle,end,nodes);
        }
        nodes.set(index,new Node(x0,y0,z0,x1,y1,z1,nodes.size(),end-first==1?boxes[first]:null));
    }
    private PackageMovingGeometry(){}
}
