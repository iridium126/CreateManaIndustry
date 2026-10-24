package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** A short, immutable, C1 rigid-body segment. All clocks are attack-local seconds. */
public record HelixGatherFrame(int version,float seconds,Vec3 center,Vec3 velocity,Vec3 nextVelocity,
                              double qx,double qy,double qz,double qw,Vec3 omega,Vec3 nextOmega,Vec3 endpoint) {
    public static final float STEP=.25f,DELAY=.25f,EXTRAPOLATE=.25f;
    public static final double TURN=Math.toRadians(8);
    public static final int WORDS=32;
    public record Sample(Vec3 center,Vec3 velocity,Quaterniond rotation,Vec3 omega) {
        public Vec3 axis(){return rotate(rotation,new Vec3(1,0,0));}
        public Vec3 side(){return rotate(rotation,new Vec3(0,1,0));}
        public Vec3 point(Vec3 local){return center.add(rotate(rotation,local));}
        public Vec3 motion(Vec3 local){return velocity.add(omega.cross(rotate(rotation,local)));}
    }
    public HelixGatherFrame(int version,float seconds,Vec3 center,Vec3 velocity,Vec3 nextVelocity,double qx,double qy,double qz,double qw,Vec3 omega,Vec3 nextOmega){this(version,seconds,center,velocity,nextVelocity,qx,qy,qz,qw,omega,nextOmega,center.add(velocity.add(nextVelocity).scale(STEP*.5)));}
    public HelixGatherFrame {
        if(version<0||version>=1<<24||!Float.isFinite(seconds)||seconds<0||seconds>600)throw new IllegalArgumentException("Helix reference identity");
        for(Vec3 v:new Vec3[]{center,velocity,nextVelocity,omega,nextOmega,endpoint})if(!Double.isFinite(v.x)||!Double.isFinite(v.y)||!Double.isFinite(v.z))throw new IllegalArgumentException("Helix reference vector");
        if(velocity.length()>2.001||nextVelocity.length()>2.001||omega.length()>TURN+.00001||nextOmega.length()>TURN+.00001||!Double.isFinite(qx+qy+qz+qw)||Math.abs(qx*qx+qy*qy+qz*qz+qw*qw-1)>.0001)throw new IllegalArgumentException("Helix reference bounds");
    }
    public static HelixGatherFrame initial(Vec3 center,Vec3 axis,Vec3 velocity){
        Vec3 side=(Math.abs(axis.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0)).cross(axis).normalize(),up=axis.cross(side);
        var q=new Quaterniond().setFromNormalized(new org.joml.Matrix3d(axis.x,axis.y,axis.z,side.x,side.y,side.z,up.x,up.y,up.z)).normalize();
        return new HelixGatherFrame(1,0,center,velocity,velocity,q.x,q.y,q.z,q.w,Vec3.ZERO,Vec3.ZERO);
    }
    public static HelixGatherFrame frozen(int version,float seconds,Sample sample){
        var q=sample.rotation();return new HelixGatherFrame(version,seconds,sample.center(),Vec3.ZERO,Vec3.ZERO,q.x,q.y,q.z,q.w,Vec3.ZERO,Vec3.ZERO);
    }
    public HelixGatherFrame next(Vec3 aim,Vec3 speed){return next(aim,speed,sample(seconds+STEP).center());}
    public HelixGatherFrame next(Vec3 aim,Vec3 speed,Vec3 bodyCenter){
        Sample end=sample(seconds+STEP);Vec3 axis=end.axis(),direction=aim.subtract(end.center).normalize(),cross=axis.cross(direction);
        double angle=Math.acos(Math.clamp(axis.dot(direction),-1,1));
        if(cross.lengthSqr()<1e-12)cross=angle<1e-8?Vec3.ZERO:axis.cross(Math.abs(axis.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0));
        Vec3 goal=direction.lengthSqr()<.5?Vec3.ZERO:cross.normalize().scale(Math.min(TURN,angle/STEP));
        // Bound angular acceleration as well as rate; interpolation cannot jump at updates.
        Vec3 delta=goal.subtract(end.omega);if(delta.length()>TURN*STEP*4)delta=delta.normalize().scale(TURN*STEP*4);
        Vec3 w=end.omega.add(delta);if(w.length()>TURN)w=w.normalize().scale(TURN);
        Quaterniond q=end.rotation;
        return new HelixGatherFrame(version+1,seconds+STEP,end.center,end.velocity,speed,q.x,q.y,q.z,q.w,end.omega,w,bodyCenter.add(speed.scale(STEP)));
    }
    public Sample sample(double time){
        double t=Math.clamp(time-seconds,0,STEP),u=t/STEP;
        Vec3 dv=nextVelocity.subtract(velocity);
        // Hermite center follows the body endpoint without changing the incoming tangent.
        double u2=u*u,u3=u2*u;
        Vec3 displacement=endpoint.subtract(center);
        Vec3 p=center.add(velocity.scale(STEP*(u3-2*u2+u))).add(displacement.scale(-2*u3+3*u2)).add(nextVelocity.scale(STEP*(u3-u2)));
        Vec3 v=velocity.scale(3*u2-4*u+1).add(displacement.scale((-6*u2+6*u)/STEP)).add(nextVelocity.scale(3*u2-2*u));
        Vec3 r=omega.scale(t).add(nextOmega.subtract(omega).scale(t*t/(2*STEP)));
        Vec3 rd=omega.add(nextOmega.subtract(omega).scale(u));
        Quaterniond rotation=exp(r).mul(new Quaterniond(qx,qy,qz,qw)).normalize();
        Vec3 w=jacobian(r,rd);
        double extra=Math.clamp(time-seconds-STEP,0,EXTRAPOLATE),fade=1-extra/EXTRAPOLATE;
        if(time>seconds+STEP){double travel=extra-extra*extra/(2*EXTRAPOLATE);p=p.add(v.scale(travel));rotation=exp(w.scale(travel)).mul(rotation).normalize();v=v.scale(fade);w=w.scale(fade);}
        return new Sample(p,v,rotation,w);
    }
    static Vec3 jacobian(Vec3 r,Vec3 v){double a=r.length();if(a<1e-8)return v;return v.add(r.cross(v).scale((1-Math.cos(a))/(a*a))).add(r.cross(r.cross(v)).scale((a-Math.sin(a))/(a*a*a)));}
    static Quaterniond exp(Vec3 r){double a=r.length(),s=a<1e-8?.5:Math.sin(a*.5)/a;return new Quaterniond(r.x*s,r.y*s,r.z*s,Math.cos(a*.5));}
    static Vec3 rotate(Quaterniond q,Vec3 v){var p=q.transform(new Vector3d(v.x,v.y,v.z));return new Vec3(p.x,p.y,p.z);}
    public float[] words(){return new float[]{version,seconds,STEP,1,(float)center.x,(float)center.y,(float)center.z,0,(float)velocity.x,(float)velocity.y,(float)velocity.z,0,(float)nextVelocity.x,(float)nextVelocity.y,(float)nextVelocity.z,0,(float)qx,(float)qy,(float)qz,(float)qw,(float)omega.x,(float)omega.y,(float)omega.z,0,(float)nextOmega.x,(float)nextOmega.y,(float)nextOmega.z,0,(float)(endpoint.x-center.x),(float)(endpoint.y-center.y),(float)(endpoint.z-center.z),0};}
    public void write(RegistryFriendlyByteBuf b){for(float f:words())b.writeFloat(f);}
    public static HelixGatherFrame read(RegistryFriendlyByteBuf b){float[] f=new float[WORDS];for(int i=0;i<f.length;i++){f[i]=b.readFloat();if(!Float.isFinite(f[i]))throw new io.netty.handler.codec.DecoderException("Nonfinite helix reference");}if(f[0]!=(int)f[0]||f[2]!=STEP||f[3]!=1)throw new io.netty.handler.codec.DecoderException("Invalid helix reference header");try{return new HelixGatherFrame((int)f[0],f[1],vec(f,4),vec(f,8),vec(f,12),f[16],f[17],f[18],f[19],vec(f,20),vec(f,24),vec(f,4).add(vec(f,28)));}catch(IllegalArgumentException e){throw new io.netty.handler.codec.DecoderException(e);}}
    private static Vec3 vec(float[] f,int i){return new Vec3(f[i],f[i+1],f[i+2]);}
}
