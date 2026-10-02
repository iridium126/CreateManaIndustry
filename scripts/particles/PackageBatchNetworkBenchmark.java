import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.VarInt;
import net.minecraft.resources.ResourceLocation;

/** Byte-exact encoding and Minecraft zlib/frame comparison against native motion fixtures.
 * Fixtures exclude acquisition, observer streams, connected gameplay and transport headers;
 * partial coverage cannot certify total network traffic. */
public final class PackageBatchNetworkBenchmark {
    static final PackageRegion REGION=new PackageRegion(0,0,0);
    // Create AllEntityTypes.PACKAGE sets updateFrequency=3. Stable fixtures exclude impulses,
    // dirty metadata, ground changes and teleports; these can force extra native updates.
    static final int TICKS=60,BATCH=512,NATIVE_INTERVAL=3;
    static int scatter(int i,int salt) {int x=i*0x9e3779b9+salt;x^=x>>>16;x*=0x85ebca6b;x^=x>>>13;return Integer.remainderUnsigned(x,230000);}
    static PackageDeltaCodec.Entry entry(int i,int tick,String scene) {
        int d=(int)Math.round(tick*.05*4096);boolean diagonal=!scene.equals("line_x"),divergent=scene.equals("divergent_xyz"),random=scene.equals("dispersed_xyz")||divergent;
        int x=(random?scatter(i,1):(i%60)*4096)+d;
        int y=(random?scatter(i,2):20000)+(diagonal?d:0);
        int z=(random?scatter(i,3):9000+(i/60%60)*4096)+(diagonal?d:0);
        if(divergent) {
            x=32768+scatter(i,1)%170000+(int)Math.round(tick*.05*4096*((scatter(i,101)&2047)-1024)/1024.0);
            y=32768+scatter(i,2)%170000+(int)Math.round(tick*.05*4096*((scatter(i,102)&2047)-1024)/1024.0);
            z=32768+scatter(i,3)%170000+(int)Math.round(tick*.05*4096*((scatter(i,103)&2047)-1024)/1024.0);
        }
        return new PackageDeltaCodec.Entry(i,1,new PackageDeltaCodec.Quantized(x,y,z,(short)0,(short)0,(short)0,(short)0,0));
    }
    public static void main(String[] args) throws Exception {
        boolean framing=Arrays.asList(args).contains("--framing");
        var frameRows=new ArrayList<String>();
        frameRows.add("packages,scene,cadence_ticks,compression_threshold,clients,logical_seconds,native_framed_bytes,relative_up_framed_bytes,ack_framed_bytes,retained_native_plus_gpu_framed_bytes,retained_ratio,hypothetical_authority_down_suppressed_framed_bytes,hypothetical_suppressed_ratio");
        FrameProbe[] probes=framing?new FrameProbe[]{new FrameProbe(-1),new FrameProbe(256)}:new FrameProbe[0];
        var predictedRows=new ArrayList<String>();predictedRows.add("packages,scene,cadence_ticks,logical_seconds,native_one_client_payload_bytes,relative_up_payload_bytes,predicted_up_payload_bytes,predicted_to_native_ratio,predicted_body_bytes");
        var predictedFrameRows=new ArrayList<String>();predictedFrameRows.add(frameRows.getFirst().replace("relative_up_framed_bytes","predicted_up_framed_bytes"));
        var acceptanceRows=new ArrayList<String>();
        acceptanceRows.add("packages,scene,cadence_ticks,compression_threshold,clients,encoding,evidence,native_component_bytes,gpu_component_bytes,ratio,verdict");
        var rows=new ArrayList<String>();rows.add("packages,scene,cadence_ticks,logical_seconds,native_one_client_payload_bytes,old_up_payload_bytes,packed_up_payload_bytes,relative_up_payload_bytes,packed_to_native_ratio,relative_to_native_ratio,old_body_bytes,packed_body_bytes,relative_body_bytes");
        var out=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        var old=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);var packed=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);
        var relative=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);
        var predicted=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);
        var changes=new ArrayList<PackageDeltaCodec.Entry>(BATCH);var writer=new PackageBatchDeltaCodec.Writer();
        try {
            for(int count:new int[]{10000,65536,131072})for(String scene:List.of("line_x","diagonal_xyz","dispersed_xyz","divergent_xyz"))for(int cadence:new int[]{1,3}) {
                long nativeBytes=0,oldBytes=0,packedBytes=0,relativeBytes=0,oldBodies=0,packedBodies=0,relativeBodies=0;
                long predictedBytes=0,predictedBodies=0;
                long[] nativeFrames=new long[probes.length],relativeFrames=new long[probes.length],ackFrames=new long[probes.length];
                long[] predictedFrames=new long[probes.length];
                for(int tick=1;tick<=TICKS;tick++) {
                    if(tick%NATIVE_INTERVAL==0)for(int i=0;i<count;i++) {
                        var q=entry(i,tick,scene).value();var before=entry(i,tick-NATIVE_INTERVAL,scene).value();out.clear();
                        ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(out,new ClientboundMoveEntityPacket.Pos(i+1,
                                (short)(q.x()-before.x()),(short)(q.y()-before.y()),(short)(q.z()-before.z()),true));nativeBytes+=out.readableBytes();
                        for(int k=0;k<probes.length;k++) {
                            int bytes=probes[k].nativeFrame(out);nativeFrames[k]+=bytes;
                        }
                    }
                    if(tick%cadence!=0)continue;
                    var acks=framing?new PackageAckRanges.Builder():null;
                    for(int first=0;first<count;first+=BATCH) {
                        changes.clear();int end=Math.min(first+BATCH,count);for(int i=first;i<end;i++)changes.add(entry(i,tick,scene));
                        old.clear();PackageDeltaCodec.encode(old,changes);oldBodies+=old.position();
                        packed.clear();writer.reset(packed,changes.size());
                        for(var e:changes){var v=e.value();writer.entry(e.id(),e.mask(),v.x(),v.y(),v.z(),v.vx(),v.vy(),v.vz(),v.yaw(),v.flags());}writer.finish();packedBodies+=packed.position();
                        // Verify actual decoded absolute fields for every batch, before accounting.
                        var decoded=PackageBatchDeltaCodec.decode(packed.duplicate().flip());if(!decoded.equals(changes))throw new AssertionError("packed fixture mismatch");
                        relative.clear();writer.reset(relative,changes.size());
                        var residuals=new ArrayList<PackageDeltaCodec.Entry>(changes.size());
                        for(var e:changes) {
                            var v=e.value();var base=entry(e.id(),tick-cadence,scene).value();
                            var residual=new PackageDeltaCodec.Quantized(v.x()-base.x(),v.y()-base.y(),v.z()-base.z(),(short)0,(short)0,(short)0,(short)0,0);
                            residuals.add(new PackageDeltaCodec.Entry(e.id(),1,residual));
                            writer.entry(e.id(),1,residual.x(),residual.y(),residual.z(),0,0,0,0,0);
                        }
                        writer.finish();relativeBodies+=relative.position();
                        if(!PackageBatchDeltaCodec.decode(relative.duplicate().flip()).equals(residuals))throw new AssertionError("relative fixture mismatch");
                        predicted.clear();writer.reset(predicted,changes.size());var errors=new ArrayList<PackageDeltaCodec.Entry>(changes.size());
                        for(var e:changes) {
                            var q=e.value();var base=entry(e.id(),tick-cadence,scene).value();
                            var previous=tick==cadence?base:entry(e.id(),tick-2*cadence,scene).value();
                            int dx=base.x()-previous.x(),dy=base.y()-previous.y(),dz=base.z()-previous.z();
                            var error=new PackageDeltaCodec.Quantized(q.x()-base.x()-dx,q.y()-base.y()-dy,q.z()-base.z()-dz,(short)0,(short)0,(short)0,(short)0,0);
                            var record=new PackageDeltaCodec.Entry(e.id(),1,error);errors.add(record);
                            if(!PackageDeltaCodec.mergePredictedPosition(base,record,dx,dy,dz).equals(q))throw new AssertionError("predicted absolute restoration mismatch");
                            writer.entry(e.id(),1,error.x(),error.y(),error.z(),0,0,0,0,0);
                        }
                        writer.finish();predictedBodies+=predicted.position();
                        if(!PackageBatchDeltaCodec.decode(predicted.duplicate().flip()).equals(errors))throw new AssertionError("predicted fixture mismatch");
                        for(int action:new int[]{ServerboundPackagePacket.DELTA,ServerboundPackagePacket.BATCH_DELTA,ServerboundPackagePacket.RELATIVE_DELTA,ServerboundPackagePacket.PREDICTED_DELTA}) {
                            var encoded=action==ServerboundPackagePacket.DELTA?old:action==ServerboundPackagePacket.BATCH_DELTA?packed:action==ServerboundPackagePacket.RELATIVE_DELTA?relative:predicted;
                            byte[] body=Arrays.copyOf(encoded.array(),encoded.position());
                            long sequence=(long)(tick/cadence-1)*((count+511)/512)+first/512;
                            var packet=new ServerboundPackagePacket(action,0,REGION,0x3456789000000001L,0,null,0,1,sequence,body);
                            out.clear();ServerboundPackagePacket.STREAM_CODEC.encode(out,packet);
                            if(action==ServerboundPackagePacket.DELTA)oldBytes+=out.readableBytes();
                            else if(action==ServerboundPackagePacket.BATCH_DELTA)packedBytes+=out.readableBytes();else if(action==ServerboundPackagePacket.RELATIVE_DELTA)relativeBytes+=out.readableBytes();else predictedBytes+=out.readableBytes();
                            if(action==ServerboundPackagePacket.RELATIVE_DELTA && framing) {
                                for(int k=0;k<probes.length;k++)relativeFrames[k]+=probes[k].uplink(packet);
                                if(!acks.add(sequence))throw new AssertionError("framing fixture ACK bound");
                            }
                            if(action==ServerboundPackagePacket.PREDICTED_DELTA && framing)for(int k=0;k<probes.length;k++)predictedFrames[k]+=probes[k].uplink(packet);
                        }
                    }
                    if(framing) {
                        var packet=new ClientboundPackageAckPacket(ResourceLocation.parse("minecraft:overworld"),REGION,0x3456789000000001L,1,acks.snapshot());
                        for(int k=0;k<probes.length;k++)ackFrames[k]+=probes[k].ack(packet);
                    }
                }
                String row=count+","+scene+","+cadence+",3,"+nativeBytes+","+oldBytes+","+packedBytes+","+relativeBytes+","
                        +String.format(Locale.ROOT,"%.6f,%.6f",(double)packedBytes/nativeBytes,(double)relativeBytes/nativeBytes)+","+oldBodies+","+packedBodies+","+relativeBodies;
                rows.add(row);System.out.println(row);
                String prediction=count+","+scene+","+cadence+",3,"+nativeBytes+","+relativeBytes+","+predictedBytes+","+String.format(Locale.ROOT,"%.6f",(double)predictedBytes/nativeBytes)+","+predictedBodies;
                predictedRows.add(prediction);System.out.println("predicted,"+prediction);
                for(int k=0;k<probes.length;k++)for(int clients:new int[]{1,4}) {
                    long reference=nativeFrames[k]*clients,extra=relativeFrames[k]+ackFrames[k];
                    long retained=reference+extra,suppressed=nativeFrames[k]*(clients-1)+extra;
                    String framed=count+","+scene+","+cadence+","+probes[k].threshold+","+clients+",3,"+reference+","+relativeFrames[k]+","+ackFrames[k]+","+retained+","+String.format(Locale.ROOT,"%.6f",(double)retained/reference)+","+suppressed+","+String.format(Locale.ROOT,"%.6f",(double)suppressed/reference);
                    frameRows.add(framed);System.out.println("framed,"+framed);
                    long predictedExtra=predictedFrames[k]+ackFrames[k],predictedRetained=reference+predictedExtra,predictedSuppressed=nativeFrames[k]*(clients-1)+predictedExtra;
                    String predictedFramed=count+","+scene+","+cadence+","+probes[k].threshold+","+clients+",3,"+reference+","+predictedFrames[k]+","+ackFrames[k]+","+predictedRetained+","+String.format(Locale.ROOT,"%.6f",(double)predictedRetained/reference)+","+predictedSuppressed+","+String.format(Locale.ROOT,"%.6f",(double)predictedSuppressed/reference);
                    predictedFrameRows.add(predictedFramed);System.out.println("predicted-framed,"+predictedFramed);
                    // These are codec fixtures, not captured connected gameplay. The omitted
                    // traffic families and GPU exposure must stay explicit in the acceptance API.
                    var workload=new PackageNetworkComparison.Workload(scene+"-threshold-"+probes[k].threshold,count,clients,TICKS,(long)count*TICKS);
                    var coverage=EnumSet.of(PackageNetworkComparison.Coverage.MOTION,PackageNetworkComparison.Coverage.ACK_CONTROL);
                    var referenceMeasurement=new PackageNetworkComparison.Measurement(workload,PackageNetworkComparison.Scope.PROTOCOL_FRAMES,0,reference,0,coverage);
                    for(boolean usePrediction:new boolean[]{false,true})for(boolean hypothetical:new boolean[]{false,true}) {
                        long up=usePrediction?predictedFrames[k]:relativeFrames[k];
                        long down=ackFrames[k]+nativeFrames[k]*(hypothetical?clients-1:clients);
                        var measured=new PackageNetworkComparison.Measurement(workload,PackageNetworkComparison.Scope.PROTOCOL_FRAMES,up,down,0,coverage);
                        var comparison=PackageNetworkComparison.compare(referenceMeasurement,measured);
                        if(comparison.passed())throw new AssertionError("Incomplete component evidence certified network budget");
                        acceptanceRows.add(count+","+scene+","+cadence+","+probes[k].threshold+","+clients+","+(usePrediction?"predicted":"relative")
                                +","+(hypothetical?"hypothetical_suppression":"retained_native")+","+comparison.nativeBytes()+","+comparison.gpuBytes()
                                +","+String.format(Locale.ROOT,"%.6f",comparison.ratio())+","+comparison.verdict());
                    }
                }
            }
        }finally{out.release();for(var probe:probes)probe.close();}
        Files.write(Path.of("build/package-batch-network.csv"),rows);
        Files.write(Path.of("build/package-predicted-network.csv"),predictedRows);
        if(framing)Files.write(Path.of("build/package-network-framing.csv"),frameRows);
        if(framing)Files.write(Path.of("build/package-predicted-network-framing.csv"),predictedFrameRows);
        if(framing)Files.write(Path.of("build/package-network-acceptance.csv"),acceptanceRows);
    }

    /** Actual current PLAY IDs, custom-payload identifier/codec and Minecraft zlib/frame handlers.
     * No fabricated packet IDs, transport socket, bundler, gameplay or network timing. */
    static final class FrameProbe implements AutoCloseable {
        final int threshold,nativeId,upId,downId;
        final RegistryFriendlyByteBuf packet=new RegistryFriendlyByteBuf(Unpooled.buffer(32768),RegistryAccess.EMPTY);
        final ByteBuf compressed=Unpooled.buffer(32768),framed=Unpooled.buffer(32768);
        final Compressor compressor;
        final Prepender prepender=new Prepender();
        final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf,CustomPacketPayload> up=CustomPacketPayload.codec(
                id->{throw new AssertionError("Unknown payload "+id);},List.of(new CustomPacketPayload.TypeAndCodec<>(ServerboundPackagePacket.TYPE,ServerboundPackagePacket.STREAM_CODEC)),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND);
        final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf,CustomPacketPayload> down=CustomPacketPayload.codec(
                id->{throw new AssertionError("Unknown payload "+id);},List.of(new CustomPacketPayload.TypeAndCodec<>(ClientboundPackageAckPacket.TYPE,ClientboundPackageAckPacket.STREAM_CODEC)),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND);
        final Set<Integer> verifiedNativeSizes=new HashSet<>();
        FrameProbe(int threshold) {
            this.threshold=threshold;compressor=threshold<0?null:new Compressor(threshold);
            int[] ids=playIds();
            nativeId=ids[0];upId=ids[1];downId=ids[2];
            System.out.println("framing IDs: native="+nativeId+" up="+upId+" down="+downId+" threshold="+threshold);
        }
        int nativeFrame(RegistryFriendlyByteBuf payload) {
            int bytes=VarInt.getByteSize(nativeId)+payload.readableBytes();
            // At tested thresholds -1/256 every native Pos is uncompressed; the size is exact
            // regardless of coordinates. Exercise real handlers once for every entity-ID width.
            if(threshold>=0 && bytes>=threshold)throw new AssertionError("Native fast-size path requires uncompressed Pos");
            int predicted=bytes+(compressor==null?0:1);predicted+=VarInt.getByteSize(predicted);
            if(verifiedNativeSizes.add(bytes)) {
                packet.clear();packet.writeVarInt(nativeId);packet.writeBytes(payload,payload.readerIndex(),payload.readableBytes());
                if(frame()!=predicted)throw new AssertionError("Native framing size mismatch");
            }
            return predicted;
        }
        int uplink(ServerboundPackagePacket value) {packet.clear();packet.writeVarInt(upId);up.encode(packet,value);return frame();}
        int ack(ClientboundPackageAckPacket value) {packet.clear();packet.writeVarInt(downId);down.encode(packet,value);return frame();}
        int frame() {
            var input=packet.duplicate();compressed.clear();framed.clear();
            if(compressor!=null){compressor.apply(input,compressed);prepender.apply(compressed,framed);}else prepender.apply(input,framed);
            int size=framed.readableBytes();var view=framed.duplicate();
            if(VarInt.read(view)!=view.readableBytes())throw new AssertionError("Frame length mismatch");
            if(compressor==null) {
                if(!view.equals(packet))throw new AssertionError("Uncompressed frame mismatch");
            }else {
                int length=VarInt.read(view);
                if(length==0){if(!view.equals(packet))throw new AssertionError("Compression bypass mismatch");}
                else {
                    if(length!=packet.readableBytes())throw new AssertionError("Compression source length mismatch");
                    byte[] source=new byte[view.readableBytes()],restored=new byte[length];view.readBytes(source);
                    var inflater=new java.util.zip.Inflater();
                    try {
                        inflater.setInput(source);int offset=0;
                        while(offset<length && !inflater.finished()){int n=inflater.inflate(restored,offset,length-offset);if(n==0)throw new AssertionError("Stalled decompressor");offset+=n;}
                        if(offset!=length || !inflater.finished() || inflater.getRemaining()!=0)throw new AssertionError("Compressed frame extent mismatch");
                        for(int i=0;i<length;i++)if(restored[i]!=packet.getByte(packet.readerIndex()+i))throw new AssertionError("Compressed frame contents mismatch");
                    }catch(java.util.zip.DataFormatException bad){throw new AssertionError("Compressed frame invalid",bad);}finally{inflater.end();}
                }
            }
            return size;
        }
        @Override public void close(){packet.release();compressed.release();framed.release();}
    }
    /** Read resolved PLAY registration order without initializing unrelated world/item registries.
     * withBundlePacket adds one delimiter codec. Reject unsupported registration bytecode instead
     * of guessing IDs; this harness does not emulate NeoForge startup or payload negotiation. */
    static int[] playIds() {
        int[] ids={-1,-1,-1},setups={0};
        try(var stream=PackageBatchNetworkBenchmark.class.getResourceAsStream("/net/minecraft/network/protocol/game/GameProtocols.class")) {
            if(stream==null)throw new AssertionError("Resolved PLAY registration class missing");
            new org.objectweb.asm.ClassReader(stream).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.startsWith("lambda$static$") || !descriptor.equals("(Lnet/minecraft/network/protocol/ProtocolInfoBuilder;)V"))return null;
                    setups[0]++;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        int index;String pending;
                        @Override public void visitFieldInsn(int opcode,String owner,String name,String descriptor) {
                            if(opcode==org.objectweb.asm.Opcodes.GETSTATIC && descriptor.equals("Lnet/minecraft/network/protocol/PacketType;")) {
                                if(pending!=null)throw new AssertionError("Ambiguous PLAY registration");pending=name;
                            }
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface) {
                            if(!owner.equals("net/minecraft/network/protocol/ProtocolInfoBuilder"))return;
                            if(!name.equals("addPacket") && !name.equals("withBundlePacket"))throw new AssertionError("Unsupported PLAY registration method "+name);
                            if(pending==null)throw new AssertionError("PLAY packet type missing");
                            int destination=switch(pending){case "CLIENTBOUND_MOVE_ENTITY_POS"->0;case "SERVERBOUND_CUSTOM_PAYLOAD"->1;case "CLIENTBOUND_CUSTOM_PAYLOAD"->2;default->-1;};
                            if(destination>=0){if(ids[destination]>=0)throw new AssertionError("Duplicate PLAY packet registration");ids[destination]=index;}
                            pending=null;index++;
                        }
                    };
                }
            },org.objectweb.asm.ClassReader.SKIP_DEBUG|org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }catch(java.io.IOException invalid){throw new AssertionError("Cannot read resolved PLAY registration",invalid);}
        if(setups[0]!=2 || ids[0]<0 || ids[1]<0 || ids[2]<0)throw new AssertionError("PLAY packet IDs missing/unsupported setup");
        return ids;
    }
    static final class Compressor extends CompressionEncoder {Compressor(int threshold){super(threshold);}void apply(ByteBuf in,ByteBuf out){super.encode(null,in,out);}}
    static final class Prepender extends Varint21LengthFieldPrepender {void apply(ByteBuf in,ByteBuf out){super.encode(null,in,out);}}
}
