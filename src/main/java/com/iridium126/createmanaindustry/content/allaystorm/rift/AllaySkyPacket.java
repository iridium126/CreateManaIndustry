package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.function.Consumer;

/** Independent target context updates also cover command/portal arrivals that do not use a rift. */
public record AllaySkyPacket(AllaySkyContext sky) implements CustomPacketPayload {
    public static final Type<AllaySkyPacket> TYPE = new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"allay_sky"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AllaySkyPacket> STREAM_CODEC = StreamCodec.of((b,p)->p.sky.write(b),b->new AllaySkyPacket(AllaySkyContext.read(b)));
    private static final ClientPayloadHandler<AllaySkyPacket> CLIENT = new ClientPayloadHandler<>();
    public static void install(Consumer<AllaySkyPacket> handler) { CLIENT.install(handler); }
    public static void handle(AllaySkyPacket packet, IPayloadContext context) { context.enqueueWork(()->CLIENT.dispatch(packet)); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
