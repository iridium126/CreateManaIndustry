package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Visual backing records only. Inventory never travels in this packet. */
public record ClientboundLightPackagePacket(ResourceLocation dimension,List<Row> rows) implements CustomPacketPayload {
    public record Row(PackageLease.Identity identity,boolean removed,ResourceLocation model,float width,float height,PackageLease.Pose pose) {}
    public static final Type<ClientboundLightPackagePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"light_packages"));
    private static final ClientPayloadHandler<ClientboundLightPackagePacket> CLIENT=new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundLightPackagePacket> STREAM_CODEC=StreamCodec.of(ClientboundLightPackagePacket::encode,ClientboundLightPackagePacket::decode);
    public ClientboundLightPackagePacket {rows=List.copyOf(rows);if(dimension==null||rows.size()>128)throw new IllegalArgumentException("Light package batch");for(var r:rows)if(r.identity()==null||!r.removed()&&(r.model()==null||r.pose()==null||!Float.isFinite(r.width())||!Float.isFinite(r.height())||r.width()<=0||r.height()<=0||r.width()>16||r.height()>16))throw new IllegalArgumentException("Light package row");}
    private static void encode(RegistryFriendlyByteBuf b,ClientboundLightPackagePacket p){b.writeResourceLocation(p.dimension);b.writeVarInt(p.rows.size());for(var r:p.rows){b.writeVarLong(r.identity.id());b.writeVarLong(r.identity.generation());b.writeBoolean(r.removed);if(r.removed)continue;b.writeResourceLocation(r.model);b.writeFloat(r.width);b.writeFloat(r.height);var q=r.pose;b.writeDouble(q.x());b.writeDouble(q.y());b.writeDouble(q.z());b.writeFloat(q.vx());b.writeFloat(q.vy());b.writeFloat(q.vz());b.writeFloat(q.yaw());}}
    private static ClientboundLightPackagePacket decode(RegistryFriendlyByteBuf b){var dim=b.readResourceLocation();int n=b.readVarInt();if(n<0||n>128)throw new io.netty.handler.codec.DecoderException("Light package count");var rows=new java.util.ArrayList<Row>(n);for(int i=0;i<n;i++){var id=new PackageLease.Identity(b.readVarLong(),b.readVarLong());boolean removed=b.readBoolean();rows.add(removed?new Row(id,true,null,0,0,null):new Row(id,false,b.readResourceLocation(),b.readFloat(),b.readFloat(),new PackageLease.Pose(b.readDouble(),b.readDouble(),b.readDouble(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat())));}return new ClientboundLightPackagePacket(dim,rows);}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundLightPackagePacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundLightPackagePacket p,IPayloadContext c){c.enqueueWork(()->CLIENT.dispatch(p));}
}
