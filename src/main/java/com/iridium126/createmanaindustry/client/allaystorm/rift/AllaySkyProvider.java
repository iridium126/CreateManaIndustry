package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/** Shared render uniforms without another ClientLevel, world seed, Veil or particle simulation. */
public final class AllaySkyProvider {
    private static final RiftClientState RIFT = new RiftClientState();
    private static ClientLevel world;
    private static AllaySkyContext context = AllaySkyContext.EMPTY;
    private static long receivedAt;
    private static StormCloudFrame cloudFrame=StormCloudFrame.EMPTY;
    private static double cloudTime=Double.NaN;
    private static StormRiftSnapshot cloudSnapshot;
    private static long removedAt;
    private static boolean cloudEnabled=true;
    private static boolean cloudProfiling;
    private static long cloudProfileRevision;
    public static long cloudProfileRevision(){return cloudProfileRevision;}
    public static boolean cloudProfiling(){return cloudProfiling;}
    public static void cloudProfiling(boolean enabled){cloudProfiling=enabled;if(enabled)cloudProfileRevision++;}
    public static boolean cloudEnabled(){return cloudEnabled;}
    public static void cloudEnabled(boolean enabled){cloudEnabled=enabled;}
    private AllaySkyProvider() {}
    private static void world() {
        var level = Minecraft.getInstance().level;
        if (world != level) { VanillaSkyView.clear();world = level; RIFT.clear(); context = AllaySkyContext.EMPTY; receivedAt = level == null ? 0 : level.getGameTime();cloudTime=Double.NaN; }
    }
    public static void snapshot(RiftSnapshotPacket packet) {
        world();
        if (world == null || !world.dimension().location().equals(packet.snapshot().identity().dimension())) return;

        if (RIFT.accept(packet.snapshot())) {
            if(packet.snapshot().state()==StormRiftSnapshot.REMOVE)removedAt=world.getGameTime();
            if(packet.snapshot().state()!=StormRiftSnapshot.REMOVE)
                com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView.timeline().offer(packet.snapshot().identity(),packet.snapshot().previous(),packet.snapshot().current());
            context = packet.snapshot().sky(); receivedAt = world.getGameTime();
        }
    }
    public static void context(AllaySkyPacket packet) {
        world();
        if (!AllvrDimensions.isAllay(world)) return;
        context = packet.sky(); receivedAt = world.getGameTime();
        ((AllayClientTime)world).cmi$skyTime(context);
    }
    public static StormRiftSnapshot rift() {
        world();var current=RIFT.current();
        if(current!=null&&current.state()!=StormRiftSnapshot.REMOVE
                &&!com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView.timeline().contains(current.identity()))return null;
        return current;
    }
    public static boolean dimension() { world(); return AllvrDimensions.isAllay(world); }
    public static Vector4f stormCenter(Vec3 camera,float partial) {
        var frame=stormCloudFrame(partial);
        return frame==StormCloudFrame.EMPTY?new Vector4f():new Vector4f((float)(frame.x()-camera.x),(float)(frame.y()-camera.y),(float)(frame.z()-camera.z),(float)frame.radius());
    }
    public static StormCloudFrame stormCloudFrame(float partial) {
        var snapshot=rift();
        if(snapshot==null||world==null||dimension()||!world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))return StormCloudFrame.EMPTY;
        double time=world.getGameTime()+(double)partial;
        if(cloudSnapshot==snapshot&&cloudTime==time)return cloudFrame;
        cloudSnapshot=snapshot;cloudTime=time;
        var timeline=com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView.timeline();
        var frame=timeline.contains(snapshot.identity())?timeline.sample(world.getGameTime(),partial):null;
        var shape=JuliaProjection.shape();
        if(frame==null||shape==null)return cloudFrame=StormCloudFrame.EMPTY;
        double slope=shape.cloudConeSlope(frame.radius(),frame.y(),snapshot.planeY());
        double stop=snapshot.state()==StormRiftSnapshot.REMOVE?40-(time-removedAt):40;
        float strength=StormCloudFrame.strength(frame.count(),time-snapshot.definition().createdAt(),stop);
        return cloudFrame=new StormCloudFrame(frame.x(),frame.y(),frame.z(),frame.radius(),slope,snapshot.planeY(),strength,
                slope>0?"native density only; long-half-axis opening; max=2; history=stock":"invalid Julia reference; native density unchanged");
    }
    public static Vector4f stormCloud(Vec3 camera,float partial) {
        var frame=stormCloudFrame(partial);
        return new Vector4f((float)frame.coneSlope(),(float)(frame.planeY()-64),cloudEnabled?frame.strength():0,(float)(camera.y-64));
    }
    public static Vector4f sky(float partial) {
        world();
        double elapsed = world == null || !context.daylightCycle() ? 0 : Math.max(0, world.getGameTime() - receivedAt) + partial;
        float phase = (float)((Math.floorMod(context.dayTime(), 24000) + context.fraction() + elapsed*(context.perTick()<0?1:context.perTick()))/24000.0);
        return new Vector4f(phase, context.rain(), context.thunder(), (context.visualSeed() & 0xffffffL)*0.00001f);
    }
    public static Vector4f rift(Vec3 camera, float partial) {
        var snapshot = rift();
        if (snapshot == null || snapshot.state() != StormRiftSnapshot.OPEN) return new Vector4f(0,0,0,-1);
        float age = snapshot.openedAt() == 0 ? 2 : (float)Math.max(0, (world.getGameTime() - snapshot.openedAt() + partial) / 20.0);
        var frame=com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView.timeline().sample(world.getGameTime(),partial);
        double x=frame==null?snapshot.x():frame.x(),z=frame==null?snapshot.z():frame.z();
        return new Vector4f((float)(x-camera.x), (float)(snapshot.planeY()-camera.y), (float)(z-camera.z), age);
    }
    public static void clear() { VanillaSkyView.clear();cloudTime=Double.NaN;cloudSnapshot=null;cloudFrame=StormCloudFrame.EMPTY;world = null; RIFT.clear(); context = AllaySkyContext.EMPTY; }
    public static String report() {
        var snapshot=rift();
        var frame=world==null?null:com.iridium126.createmanaindustry.client.allaystorm.visual.StormCenterView.timeline().sample(world.getGameTime(),0);
        var shape=JuliaProjection.shape();
        String fit=snapshot==null||frame==null?"awaiting canonical samples":shape==null?"Julia resources unavailable":shape.referenceValid(frame.radius(),frame.y(),snapshot.planeY())?"1.2R frontal circle fitted; uniform scale="+shape.scale(frame.radius(),frame.y(),snapshot.planeY(),2):"entrance coverage only: reference height invalid or outside full weak-perspective range";
        return "rift-summary="+(snapshot==null?"none":snapshot.identity()+" sequence="+snapshot.sequence()+" state="+snapshot.state()+" samples="+snapshot.previous().tick()+"/"+snapshot.tick())
                +"; center=shared delayed-20tick timeline; envelope=1.2R circle; Julia=q^2-0.5 projection"
                +"\ncircle-fit="+fit
                +"\nJulia-projection="+(shape==null?"resources unavailable":"original boundary; object-centred weak perspective; near physical transition=128..256; shared hit-distance texture")
                +"; sky="+(VanillaRiftRenderer.nativePack()?com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current().riftReason():"vanilla")
                +"; storm-cloud="+(cloudEnabled?"on":"bypassed")+"; allay-cloud=stock; source vanilla cloud=rift clipping only; target vanilla="+VanillaSkyView.cloudReport()
                +"; vanilla-path="+VanillaRiftRenderer.report()+"; transfer=server-coordinated group transaction (protocol 20)";
    }
}
