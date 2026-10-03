package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded downstream pose batches. Stable identity/model are sent once on introduction; items,
 * addresses, inventory and Create callbacks are never part of this protocol. */
public record ClientboundPackageObserverPacket(ResourceLocation dimension,PackageRegion region,long epoch,long revision,
        long stream,long sequence,int flags,List<PackageObserverFeed.Member<Visual>> baselines,List<PackageDeltaCodec.Entry> changes,
        long serverTick,PackageObserverTimes stateTicks)
        implements CustomPacketPayload {
    public static final int RESET=1,COMPLETE=2,CLOSE=4,MAX_RECORDS=128,MAX_DELTA_BYTES=8192;
    public record Visual(int entityId,UUID entityUuid,ResourceLocation model,float width,float height) {
        public Visual {
            if(entityId< -1 || entityUuid==null || model==null || model.toString().length()>256
                    || !Float.isFinite(width) || !Float.isFinite(height) || width<=0 || height<=0 || width>16 || height>16)
                throw new IllegalArgumentException("Observer visual");
        }
    }
    public static final Type<ClientboundPackageObserverPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(
            CreateManaIndustry.MODID,"package_observer_down"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundPackageObserverPacket> STREAM_CODEC=StreamCodec.of(
            ClientboundPackageObserverPacket::encode,ClientboundPackageObserverPacket::decode);
    private static final ClientPayloadHandler<ClientboundPackageObserverPacket> CLIENT=new ClientPayloadHandler<>();
    private static final ThreadLocal<ByteBuffer> ENCODE_SCRATCH=ThreadLocal.withInitial(()->ByteBuffer.allocate(MAX_DELTA_BYTES));
    /** Untimed fixtures only; production supplies server commit times explicitly. */
    public ClientboundPackageObserverPacket(ResourceLocation dimension,PackageRegion region,long epoch,long revision,
            long stream,long sequence,int flags,List<PackageObserverFeed.Member<Visual>> baselines,List<PackageDeltaCodec.Entry> changes) {
        this(dimension,region,epoch,revision,stream,sequence,flags,baselines,changes,0,PackageObserverTimes.zeros(changes.size()));
    }
    public ClientboundPackageObserverPacket {
        if(dimension==null || region==null || epoch<=0 || revision<=0 || stream<=0 || sequence<0 || (flags&~7)!=0
                || (flags&RESET)!=0 && sequence!=0 || (flags&CLOSE)!=0 && (flags!=CLOSE || !baselines.isEmpty() || !changes.isEmpty()))
            throw new IllegalArgumentException("Observer envelope");
        baselines=List.copyOf(baselines);changes=List.copyOf(changes);
        if(serverTick<0 || stateTicks==null || stateTicks.size()!=changes.size())throw new IllegalArgumentException("Observer time envelope");
        for(var member:baselines)if(member.stateTick()>serverTick)throw new IllegalArgumentException("Observer future baseline");
        for(int i=0;i<stateTicks.size();i++)if(stateTicks.get(i)>serverTick)throw new IllegalArgumentException("Observer future delta");
        if(baselines.size()+changes.size()>MAX_RECORDS)throw new IllegalArgumentException("Observer batch count");
        int previous=-1;
        for(var change:changes) {
            if(change.id()<=previous || (change.mask()&PackageDeltaCodec.FLAGS)!=0
                    && (change.value().flags()&~PackageAuthorityRegion.STATE_FLAGS)!=0)
                throw new IllegalArgumentException("Observer changes");
            previous=change.id();
        }
    }
    private static void encode(RegistryFriendlyByteBuf b,ClientboundPackageObserverPacket p) {
        b.writeResourceLocation(p.dimension);ServerboundPackagePacket.writeRegion(b,p.region);
        b.writeVarLong(p.epoch);b.writeVarLong(p.revision);b.writeVarLong(p.stream);b.writeVarLong(p.sequence);b.writeByte(p.flags);
        b.writeVarLong(p.serverTick);
        long commonTick=!p.baselines.isEmpty()?p.baselines.getFirst().stateTick():!p.changes.isEmpty()?p.stateTicks.get(0):p.serverTick;
        boolean shared=true;
        for(var member:p.baselines)if(member.stateTick()!=commonTick){shared=false;break;}
        if(shared)for(int i=0;i<p.stateTicks.size();i++)if(p.stateTicks.get(i)!=commonTick){shared=false;break;}
        // One age for the common all-confirmed-this-tick case, rather than a byte per package.
        b.writeByte(shared?0:1);if(shared)b.writeVarLong(p.serverTick-commonTick);
        b.writeVarInt(p.baselines.size());
        for(var member:p.baselines) {
            b.writeVarInt(member.index());b.writeVarLong(member.identity().id());b.writeVarLong(member.identity().generation());
            b.writeVarLong(member.leaseEpoch());b.writeVarLong(member.revision());writeState(b,member.state());
            if(!shared)b.writeVarLong(p.serverTick-member.stateTick());
            var v=member.metadata();b.writeVarInt(v.entityId());b.writeUUID(v.entityUuid());b.writeUtf(v.model().toString(),256);
            b.writeFloat(v.width());b.writeFloat(v.height());
        }
        // One compact field-mask codec is shared with the authority uplink. The count is bounded
        // before allocation; worst-case 128 records fit comfortably in this fixed scratch buffer.
        var bytes=ENCODE_SCRATCH.get();bytes.clear();PackageDeltaCodec.encode(bytes,p.changes);
        b.writeVarInt(bytes.position());b.writeBytes(bytes.array(),0,bytes.position());
        if(!shared)for(int i=0;i<p.stateTicks.size();i++)b.writeVarLong(p.serverTick-p.stateTicks.get(i));
    }
    private static ClientboundPackageObserverPacket decode(RegistryFriendlyByteBuf b) {
        try {
            var dimension=b.readResourceLocation();var region=ServerboundPackagePacket.readRegion(b);
            long epoch=b.readVarLong(),revision=b.readVarLong(),stream=b.readVarLong(),sequence=b.readVarLong();int flags=b.readUnsignedByte();
            long tick=b.readVarLong();if(tick<0)throw new IllegalArgumentException("Observer server tick");
            int timeMode=b.readUnsignedByte();if(timeMode>1)throw new IllegalArgumentException("Observer time mode");
            long sharedTick=timeMode==0?readStateTick(b,tick):-1;
            int count=b.readVarInt();if(count<0 || count>MAX_RECORDS)throw new IllegalArgumentException("Observer baseline count");
            var members=new ArrayList<PackageObserverFeed.Member<Visual>>(count);
            for(int i=0;i<count;i++) {
                int index=b.readVarInt();var identity=new PackageLease.Identity(b.readVarLong(),b.readVarLong());
                long lease=b.readVarLong(),base=b.readVarLong();var state=readState(b);long stateTick=timeMode==0?sharedTick:readStateTick(b,tick);
                var visual=new Visual(b.readVarInt(),b.readUUID(),ResourceLocation.parse(b.readUtf(256)),b.readFloat(),b.readFloat());
                members.add(new PackageObserverFeed.Member<>(index,identity,lease,base,state,visual,stateTick));
            }
            var bytes=ByteBuffer.wrap(b.readByteArray(MAX_DELTA_BYTES));var changes=PackageDeltaCodec.decode(bytes,MAX_RECORDS-count);
            if(bytes.hasRemaining())throw new IllegalArgumentException("Trailing observer delta bytes");
            long[] times=new long[changes.size()];for(int i=0;i<times.length;i++)times[i]=timeMode==0?sharedTick:readStateTick(b,tick);
            return new ClientboundPackageObserverPacket(dimension,region,epoch,revision,stream,sequence,flags,members,changes,tick,new PackageObserverTimes(times));
        }catch(IllegalArgumentException | java.nio.BufferUnderflowException | IndexOutOfBoundsException invalid){throw new DecoderException("Invalid observer batch",invalid);}
    }
    private static long readStateTick(RegistryFriendlyByteBuf b,long serverTick) {
        long age=b.readVarLong();if(age<0 || age>serverTick)throw new IllegalArgumentException("Observer state age");
        return serverTick-age;
    }
    private static void writeState(RegistryFriendlyByteBuf b,PackageDeltaCodec.Quantized q) {
        b.writeInt(q.x());b.writeInt(q.y());b.writeInt(q.z());b.writeInt(q.vx());b.writeInt(q.vy());b.writeInt(q.vz());b.writeShort(q.yaw());b.writeByte(q.flags());
    }
    private static PackageDeltaCodec.Quantized readState(RegistryFriendlyByteBuf b) {
        return new PackageDeltaCodec.Quantized(b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readShort(),b.readUnsignedByte());
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundPackageObserverPacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundPackageObserverPacket packet,IPayloadContext ctx){ctx.enqueueWork(()->CLIENT.dispatch(packet));}
}
