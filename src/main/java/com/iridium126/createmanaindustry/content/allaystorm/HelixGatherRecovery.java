package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/** Local C1 correction after sampling an extrapolated segment. Never a server reference. */
public record HelixGatherRecovery(float seconds,Vec3 offset,Vec3 velocity,Vec3 rotation,Vec3 tangent) {
    public static final int WORDS=20;
    public static final float DURATION=.25f;
    public static HelixGatherRecovery between(float seconds,HelixGatherFrame.Sample before,HelixGatherFrame.Sample after){
        Quaterniond q=new Quaterniond(before.rotation()).mul(new Quaterniond(after.rotation()).conjugate()).normalize();
        if(q.w<0)q.set(-q.x,-q.y,-q.z,-q.w);
        double half=Math.acos(Math.clamp(q.w,-1,1)),s=Math.sin(half);
        Vec3 r=Math.abs(s)<1e-9?Vec3.ZERO:new Vec3(q.x,q.y,q.z).scale(2*half/s);
        Vec3 w=before.omega().subtract(HelixGatherFrame.rotate(q,after.omega()));double a=r.length();
        Vec3 rd=a<1e-7?w:w.subtract(r.cross(w).scale(.5)).add(r.cross(r.cross(w)).scale((1-.5*a/Math.tan(a*.5))/(a*a)));
        return new HelixGatherRecovery(seconds,before.center().subtract(after.center()),before.velocity().subtract(after.velocity()),r,rd);
    }
    public HelixGatherFrame.Sample apply(HelixGatherFrame.Sample raw,double time){
        double u=Math.clamp((time-seconds)/DURATION,0,1),u2=u*u,u3=u2*u;
        double h=2*u3-3*u2+1,g=DURATION*(u3-2*u2+u),dh=(6*u2-6*u)/DURATION,dg=3*u2-4*u+1;
        Vec3 r=rotation.scale(h).add(tangent.scale(g)),rd=rotation.scale(dh).add(tangent.scale(dg));
        Quaterniond q=HelixGatherFrame.exp(r);
        return new HelixGatherFrame.Sample(raw.center().add(offset.scale(h)).add(velocity.scale(g)),raw.velocity().add(offset.scale(dh)).add(velocity.scale(dg)),new Quaterniond(q).mul(raw.rotation()).normalize(),HelixGatherFrame.jacobian(r,rd).add(HelixGatherFrame.rotate(q,raw.omega())));
    }
    public float[] words(){return new float[]{seconds,DURATION,0,1,(float)offset.x,(float)offset.y,(float)offset.z,0,(float)velocity.x,(float)velocity.y,(float)velocity.z,0,(float)rotation.x,(float)rotation.y,(float)rotation.z,0,(float)tangent.x,(float)tangent.y,(float)tangent.z,0};}
}
