package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import static com.iridium126.createmanaindustry.content.allaystorm.StormRingGeometry.*;

public record ClientboundStormRingPacket(StormIdentity identity, long tick, List<Frame> frames)
        implements CustomPacketPayload {
    public record Shot(long tick, float distance, Direction face) {}
    public record Frame(int slot, int id, int phase, long startTick, Vec3 center, Vec3 axis, Vec3 right,
                        Vec3 velocity, int[] roster, Shot shot) {}

    private static final ClientPayloadHandler<ClientboundStormRingPacket> CLIENT = new ClientPayloadHandler<>();
    public static final Type<ClientboundStormRingPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_ring"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormRingPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormRingPacket::encode, ClientboundStormRingPacket::decode);

    private static void encode(RegistryFriendlyByteBuf out, ClientboundStormRingPacket packet) {
        out.writeResourceLocation(packet.identity.dimension());
        out.writeLong(packet.identity.epoch());
        out.writeVarLong(packet.tick);
        out.writeVarInt(packet.frames.size());
        for (Frame frame : packet.frames) {
            out.writeByte(frame.slot);
            out.writeVarInt(frame.id);
            out.writeByte(frame.phase);
            if (frame.phase == END) continue;
            out.writeVarLong(frame.startTick);
            out.writeDouble(frame.center.x).writeDouble(frame.center.y).writeDouble(frame.center.z);
            vector(out, frame.axis);
            vector(out, frame.right);
            vector(out, frame.velocity);
            out.writeBoolean(frame.roster.length != 0);
            for (int member : frame.roster) out.writeVarInt(member);
            out.writeBoolean(frame.shot != null);
            if (frame.shot != null) {
                out.writeVarLong(frame.shot.tick);
                out.writeFloat(frame.shot.distance);
                out.writeByte(frame.shot.face == null ? 0 : 1 + frame.shot.face.get3DDataValue());
            }
        }
    }

    private static ClientboundStormRingPacket decode(RegistryFriendlyByteBuf in) {
        var identity = new StormIdentity(in.readResourceLocation(), in.readLong());
        long tick = in.readVarLong();
        int count = in.readVarInt();
        if (tick < 0 || count < 1 || count > CAPACITY) throw new DecoderException("Invalid storm ring batch");
        var frames = new ArrayList<Frame>(count);
        var seen = new BitSet(CAPACITY);
        for (int i = 0; i < count; i++) {
            int slot = in.readUnsignedByte(), id = in.readVarInt(), phase = in.readUnsignedByte();
            if (slot >= CAPACITY || seen.get(slot) || id <= 0 || id >= (1 << 24) || phase < GATHER || phase > END)
                throw new DecoderException("Invalid storm ring identity");
            seen.set(slot);
            if (phase == END) {
                frames.add(new Frame(slot, id, phase, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, new int[0], null));
                continue;
            }
            long start = in.readVarLong();
            Vec3 center = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
            Vec3 axis = vector(in), right = vector(in), velocity = vector(in);
            if (start < 0 || start > tick + 4 || tick - start > 1800 || !finite(center, 32_000_000)
                    || !finite(axis, 1.01) || !finite(right, 1.01) || !finite(velocity, 6.01)
                    || Math.abs(axis.lengthSqr()-1) > .01 || Math.abs(right.lengthSqr()-1) > .01 || Math.abs(axis.dot(right)) > .01)
                throw new DecoderException("Invalid storm ring reference");
            var roster = new int[in.readBoolean() ? MEMBERS : 0];
            var members = new BitSet();
            for (int rank = 0; rank < roster.length; rank++) {
                int member = in.readVarInt();
                if (member < 0 || member >= AllayStarfall.MAX_MEMBERS || members.get(member))
                    throw new DecoderException("Invalid storm ring roster");
                members.set(member);
                roster[rank] = member;
            }
            Shot shot = null;
            if (in.readBoolean()) {
                long shotTick = in.readVarLong();
                float distance = in.readFloat();
                int face = in.readUnsignedByte();
                if (phase != FIRING || shotTick < start || shotTick > tick || !Float.isFinite(distance)
                        || distance < 0 || distance > BEAM_RANGE || face > 6)
                    throw new DecoderException("Invalid storm ring shot");
                shot = new Shot(shotTick, distance, face == 0 ? null : Direction.from3DDataValue(face - 1));
            }
            frames.add(new Frame(slot, id, phase, start, center, axis, right, velocity, roster, shot));
        }
        return new ClientboundStormRingPacket(identity, tick, List.copyOf(frames));
    }

    private static boolean finite(Vec3 value, double bound) {
        return Math.abs(value.x) <= bound && Math.abs(value.y) <= bound && Math.abs(value.z) <= bound;
    }
    private static void vector(RegistryFriendlyByteBuf out, Vec3 value) {
        out.writeFloat((float) value.x).writeFloat((float) value.y).writeFloat((float) value.z);
    }
    private static Vec3 vector(RegistryFriendlyByteBuf in) {
        return new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
    }

    public static void installClientHandler(Consumer<? super ClientboundStormRingPacket> handler) { CLIENT.install(handler); }
    public static void handle(ClientboundStormRingPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CLIENT.dispatch(packet));
    }
    @Override public Type<ClientboundStormRingPacket> type() { return TYPE; }
}
