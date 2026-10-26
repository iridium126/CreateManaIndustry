package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.function.Consumer;

public record RiftSnapshotPacket(StormRiftSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<RiftSnapshotPacket> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"storm_rift"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RiftSnapshotPacket> STREAM_CODEC=StreamCodec.of((b,p)->p.snapshot.write(b),b->new RiftSnapshotPacket(StormRiftSnapshot.read(b)));
    private static final ClientPayloadHandler<RiftSnapshotPacket> CLIENT=new ClientPayloadHandler<>();
    public static void install(Consumer<RiftSnapshotPacket> handler){CLIENT.install(handler);}
    public static void handle(RiftSnapshotPacket packet,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(packet));}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
