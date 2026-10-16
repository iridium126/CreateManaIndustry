package com.iridium126.createmanaindustry.compat.trickster.net;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;
import java.util.function.Consumer;

/** Bounded batches: relations are lifecycle events, poses are shared between all their edges. */
public record ClientboundSharedFatePacket(ResourceLocation dimension, List<Link> links, List<Pose> poses)
        implements CustomPacketPayload {
    public static final int ADD = 0, REMOVE = 1, UPDATE = 2;
    public record Link(UUID id, UUID caster, UUID target, int action, boolean animate) {}
    public record Pose(UUID entity, int entityId, Vec3 position, float height) {}
    public static final int MAX_LINKS = 256, MAX_POSES = 512;
    public static final Type<ClientboundSharedFatePacket> TYPE = new Type<>(CreateManaIndustry.modLoc("shared_fate"));
    private static final ClientPayloadHandler<ClientboundSharedFatePacket> CLIENT = new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSharedFatePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundSharedFatePacket::encode, ClientboundSharedFatePacket::decode);
    public ClientboundSharedFatePacket {
        links = List.copyOf(links); poses = List.copyOf(poses);
        if (links.size() > MAX_LINKS || poses.size() > MAX_POSES) throw new IllegalArgumentException("Shared-fate batch too large");
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void installClientHandler(Consumer<ClientboundSharedFatePacket> handler) { CLIENT.install(handler); }
    public static void handle(ClientboundSharedFatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CLIENT.dispatch(packet));
    }
    private static void encode(RegistryFriendlyByteBuf b, ClientboundSharedFatePacket p) {
        b.writeResourceLocation(p.dimension); b.writeVarInt(p.links.size());
        for (Link link : p.links) { b.writeUUID(link.id); b.writeUUID(link.caster); b.writeUUID(link.target); b.writeByte(link.action); b.writeBoolean(link.animate); }
        b.writeVarInt(p.poses.size());
        for (Pose pose : p.poses) {
            b.writeUUID(pose.entity); b.writeVarInt(pose.entityId);
            b.writeDouble(pose.position.x); b.writeDouble(pose.position.y); b.writeDouble(pose.position.z); b.writeFloat(pose.height);
        }
    }
    private static ClientboundSharedFatePacket decode(RegistryFriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); int count = bounded(b.readVarInt(), MAX_LINKS);
        var links = new ArrayList<Link>(count);
        for (int i=0;i<count;i++) {
            UUID id=b.readUUID(), caster=b.readUUID(), target=b.readUUID(); int action=b.readUnsignedByte();
            if (action>UPDATE) throw new IllegalArgumentException("Invalid shared-fate action");
            links.add(new Link(id,caster,target,action,b.readBoolean()));
        }
        count=bounded(b.readVarInt(),MAX_POSES); var poses=new ArrayList<Pose>(count);
        for(int i=0;i<count;i++) {
            UUID id=b.readUUID(); int entity=b.readVarInt(); var position=new Vec3(b.readDouble(),b.readDouble(),b.readDouble()); float height=b.readFloat();
            if(!Double.isFinite(position.x)||!Double.isFinite(position.y)||!Double.isFinite(position.z)||!Float.isFinite(height)||height<0)
                throw new IllegalArgumentException("Invalid shared-fate pose");
            poses.add(new Pose(id,entity,position,height));
        }
        return new ClientboundSharedFatePacket(dimension,links,poses);
    }
    private static int bounded(int count,int maximum) {
        if(count<0||count>maximum)throw new IllegalArgumentException("Invalid shared-fate count");
        return count;
    }
}
