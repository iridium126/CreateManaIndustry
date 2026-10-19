package com.iridium126.createmanaindustry.client.particles.engine;

/** Immutable query input copied alongside an asynchronous hit result, never reconstructed from a newer camera. */
public record ParticleHitRay(float x,float y,float z,float dx,float dy,float dz,float reach) {
    public net.minecraft.world.phys.Vec3 origin(){return new net.minecraft.world.phys.Vec3(x,y,z);}
    public net.minecraft.world.phys.Vec3 direction(){return new net.minecraft.world.phys.Vec3(dx,dy,dz);}
    public net.minecraft.world.phys.Vec3 point(double distance){return origin().add(direction().scale(distance));}
    public boolean matches(ParticleHitRay current){
        if(current==null)return false;
        double sx=x-current.x,sy=y-current.y,sz=z-current.z;
        double dot=(double)dx*current.dx+(double)dy*current.dy+(double)dz*current.dz;
        return sx*sx+sy*sy+sz*sz<=.0225&&dot>=.9999995&&reach>=current.reach-.001f;
    }
}
