package com.iridium126.createmanaindustry.client.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormCenterPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormDamagePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormPositionsPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormStatePacket;

import net.minecraft.world.phys.Vec3;

/**
 * Client receiver for the Allay Storm network protocol. Payload handlers run
 * on the render thread ({@code enqueueWork} — the Minecraft main thread IS
 * the render thread), which is the thread every storm state lives on, so the
 * owner calls below are direct.
 * <p>
 * Storm state and policy belong to {@link AllayStormRuntime}; this adapter unwraps
 * packet fields (quantized values arrive pre-scaled by the codecs) and
 * forwards. It also decodes the dead-member bitmap into the Storm domain's mirror
 * on ACTIVATE (identity/counts are then a pure client-side derivation).
 */
public final class AllayStormClientHandler {

    private AllayStormClientHandler() {
    }

    /** {@link ClientboundStormStatePacket}: lifecycle + authority flips. */
    public static void onState(ClientboundStormStatePacket packet) {
        AllayStormRuntime e = AllayStormRuntime.INSTANCE;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) e.applyStormState(packet, level.dimension().location());
    }

    /** {@link ClientboundStormDamagePacket}: authoritative damage + hit correction. */
    public static void onDamage(ClientboundStormDamagePacket packet) {
        AllayStormRuntime e = AllayStormRuntime.INSTANCE;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || !packet.dimension().equals(mc.level.dimension().location()) || !packet.identity().equals(e.identity())) return;
        Vec3 position = new Vec3(packet.x(), packet.y(), packet.z());
        e.applyStormDamage(packet.memberIdx(), packet.damage(), packet.kbX(), packet.kbZ(),
                packet.light(), packet.died(), position, gameClock());
        com.iridium126.createmanaindustry.client.particles.interaction.ModelInteractionRuntime.INSTANCE.spawnCombatVisuals(packet.memberIdx(), packet.light(),
                packet.crit(), packet.magic(), packet.hearts());
        if (mc.player != null && mc.player.getId() == packet.attackerId() && (packet.kbX() != 0 || packet.kbZ() != 0))
            mc.player.setSprinting(false); // velocity comes from the server motion packet
    }

    /** {@link ClientboundStormPositionsPacket}: relayed authority snapshot. */
    public static void onPositions(ClientboundStormPositionsPacket packet) {
        com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.applyCorrections(packet);
    }

    /** {@link ClientboundStormCenterPacket}: continuous chased-center + growth snapshot. */
    public static void onCenter(ClientboundStormCenterPacket packet) {
        com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.applyStormCenter(packet);
    }

    /** {@link ClientboundStormWavePacket}: dive-wave launch / abort event. */
    public static void onWave(
            com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket packet) {
        com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.applyWave(packet);
    }

    /** The client's shared simulation clock (game ticks; the engine mod-masks). */
    private static long gameClock() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }
}
