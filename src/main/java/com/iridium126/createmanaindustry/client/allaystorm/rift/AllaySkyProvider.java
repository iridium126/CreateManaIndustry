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
    private static long reactiveUntil;
    private AllaySkyProvider() {}
    private static void world() {
        var level = Minecraft.getInstance().level;
        if (world != level) { world = level; RIFT.clear(); context = AllaySkyContext.EMPTY; receivedAt = level == null ? 0 : level.getGameTime();reactiveUntil=receivedAt+4; }
    }
    public static void snapshot(RiftSnapshotPacket packet) {
        world();
        if (world == null || !world.dimension().location().equals(packet.snapshot().identity().dimension())) return;
        var old=RIFT.current();
        if (RIFT.accept(packet.snapshot())) {
            if(old==null||old.state()!=packet.snapshot().state()||!old.identity().equals(packet.snapshot().identity()))reactiveUntil=world.getGameTime()+4;
            context = packet.snapshot().sky(); receivedAt = world.getGameTime();
        }
    }
    public static void context(AllaySkyPacket packet) {
        world();
        if (!AllvrDimensions.isAllay(world)) return;
        context = packet.sky(); receivedAt = world.getGameTime();
    }
    public static StormRiftSnapshot rift() { world(); return RIFT.current(); }
    public static boolean dimension() { world(); return AllvrDimensions.isAllay(world); }
    public static boolean reactive(){world();return world!=null&&world.getGameTime()<=reactiveUntil;}
    public static Vector4f sky(float partial) {
        world();
        double elapsed = world == null || !context.daylightCycle() ? 0 : Math.max(0, world.getGameTime() - receivedAt) + partial;
        float phase = (float)(Math.floorMod(context.dayTime(), 24000) + elapsed) / 24000f;
        return new Vector4f(phase, context.rain(), context.thunder(), (context.visualSeed() & 0xffffffL)*0.00001f);
    }
    public static Vector4f rift(Vec3 camera, float partial) {
        var snapshot = rift();
        if (snapshot == null || snapshot.state() != StormRiftSnapshot.OPEN) return new Vector4f(0,0,0,-1);
        float age = snapshot.openedAt() == 0 ? 2 : (float)Math.max(0, (world.getGameTime() - snapshot.openedAt() + partial) / 20.0);
        return new Vector4f((float)(snapshot.x()-camera.x), (float)(snapshot.planeY()-camera.y), (float)(snapshot.z()-camera.z), age);
    }
    public static void clear() { world = null; RIFT.clear(); context = AllaySkyContext.EMPTY; }
    public static String report() {
        var snapshot=rift();
        return "rift-summary="+(snapshot==null?"none":snapshot.identity()+" sequence="+snapshot.sequence()+" state="+snapshot.state())
                +"; sky="+(VanillaRiftRenderer.nativePack()?com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current().riftReason():"vanilla")
                +"; transfer=server-coordinated group transaction (protocol 19)";
    }
}
