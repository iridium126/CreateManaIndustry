package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The distant sky subscription deliberately never touches the member simulation or authority. */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class StormRiftServer {
    private static final Map<ServerLevel, Subscription> LEVELS = new HashMap<>();
    private static final Map<ServerLevel, Long> SEQUENCES = new HashMap<>();
    private static final Map<ServerLevel, com.iridium126.createmanaindustry.content.allaystorm.StormCenterTimeline> CENTERS=new HashMap<>();
    public static void centerSample(ServerLevel level,com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormCenterPacket packet) {
        CENTERS.computeIfAbsent(level,k->new com.iridium126.createmanaindustry.content.allaystorm.StormCenterTimeline()).offer(packet.identity(),packet.sample());
    }
    private static final Set<UUID> SKY_RECIPIENTS = new HashSet<>();
    private static final class Subscription {
        final ServerLevel source;
        StormRiftSnapshot snapshot;
        long sequence;
        boolean targetAvailable;
        final HashSet<UUID> recipients = new HashSet<>();
        Subscription(ServerLevel source) { this.source=source; }
    }
    private StormRiftServer() {}

    public static void tick(ServerLevel source, AllayStormData data, double x, double z) {
        if (source.dimension() != Level.OVERWORLD || !data.active) { stop(source); return; }
        if (!Double.isFinite(x) || !Double.isFinite(z)) return;
        Subscription subscription = LEVELS.computeIfAbsent(source, Subscription::new);
        StormIdentity identity = StormIdentity.of(source, data);
        if (subscription.snapshot != null && !subscription.snapshot.identity().equals(identity)) {
            stop(source);
            subscription = LEVELS.computeIfAbsent(source, Subscription::new);
        }
        ServerLevel target = source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        if (data.count >= RiftRules.OPEN_COUNT && data.riftOpenedAt < 0) data.riftOpenedAt = source.getGameTime();
        int state = data.count < RiftRules.OPEN_COUNT ? StormRiftSnapshot.GROWING
                : target == null ? StormRiftSnapshot.UNAVAILABLE : StormRiftSnapshot.OPEN;
        boolean changed = subscription.snapshot == null || subscription.snapshot.state() != state || subscription.targetAvailable != (target != null);
        // Sample the target world's clock and weather, never the source world's sky state.
        var centers=CENTERS.get(source);
        if(centers==null||!centers.contains(identity)) {
            centerSample(source,new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormCenterPacket(
                    identity.dimension(),identity.epoch(),x,com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.stormChaseY,z,0,0,data.count,source.getGameTime()));
            centers=CENTERS.get(source);
        }
        // One periodic source: delivery follows the canonical center stream, not a second timer.
        boolean update=changed||subscription.snapshot.tick()!=centers.current().tick();
        if (update) subscription.snapshot = new StormRiftSnapshot(identity, sequence(subscription),centers.previous(),centers.current(),
                source.getMaxBuildHeight() + 64.0,data.riftOpenedAt,state,
                target == null ? AllaySkyContext.EMPTY : AllaySkyProfile.context(target),StormVisualDefinition.of(data));
        subscription.targetAvailable = target != null;
        var present = new HashSet<UUID>();
        for (var player : source.players()) {
            UUID id = player.getUUID();
            boolean previous = subscription.recipients.contains(id);
            if (RiftRules.subscribed(previous, player.getX(), player.getZ(), x, z)) {
                present.add(id);
                if (!previous && !update) next(subscription);
                if (update || !previous) PacketDistributor.sendToPlayer(player, new RiftSnapshotPacket(subscription.snapshot));
            } else if (previous) {
                next(subscription);
                PacketDistributor.sendToPlayer(player, new RiftSnapshotPacket(subscription.snapshot.removal(subscription.sequence)));
            }
        }
        subscription.recipients.clear();
        subscription.recipients.addAll(present);
        StormRiftTransfers.consider(source, data, x, z, target);
    }

    private static void next(Subscription subscription) {
        subscription.snapshot = subscription.snapshot.resequence(sequence(subscription));
    }
    private static long sequence(Subscription subscription) { return subscription.sequence=SEQUENCES.merge(subscription.source,1L,Long::sum); }

    public static void stop(ServerLevel level) {
        CENTERS.remove(level);
        Subscription old = LEVELS.remove(level);
        if (old == null || old.snapshot == null) return;
        var removal = new RiftSnapshotPacket(old.snapshot.removal(sequence(old)));
        for (var player : level.players()) if (old.recipients.contains(player.getUUID())) PacketDistributor.sendToPlayer(player, removal);
        StormRiftTransfers.cancelSource(level, old.snapshot.identity());
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { stop(level);SEQUENCES.remove(level); }
    }
    @SubscribeEvent public static void targetTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !AllvrDimensions.isAllay(level)) return;
        var present = new HashSet<UUID>();
        AllaySkyPacket packet = null;
        for (var player : level.players()) {
            present.add(player.getUUID());
            if (!SKY_RECIPIENTS.contains(player.getUUID()) || level.getGameTime() % 10 == 0) {
                if (packet == null) packet = new AllaySkyPacket(AllaySkyProfile.context(level));
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
        SKY_RECIPIENTS.clear(); SKY_RECIPIENTS.addAll(present);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LEVELS.clear(); SEQUENCES.clear(); CENTERS.clear(); SKY_RECIPIENTS.clear(); }
}
