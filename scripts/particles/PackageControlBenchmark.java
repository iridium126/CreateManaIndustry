import com.iridium126.createmanaindustry.client.particles.packages.PackageControlQueue;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Actual control queue and stream codec, deterministic identity-map receiver. No game world,
 * sockets, compression or GPU. Allocations include queue/serialization/decoding/lookup only. */
public final class PackageControlBenchmark {
    static long blackhole;
    static long p95(long[] values){var copy=values.clone();Arrays.sort(copy);return copy[(int)Math.ceil(copy.length*.95)-1];}
    static long p50(long[] values){var copy=values.clone();Arrays.sort(copy);return copy[(copy.length-1)/2];}
    static final class Rig implements AutoCloseable {
        final List<PackageAuthorityRegion.Baseline> rows=new ArrayList<>();
        final Map<PackageLease.Identity,PackageAuthorityRegion.Baseline> members=new HashMap<>();
        final PackageControlQueue.Namespace ns=new PackageControlQueue.Namespace(new PackageRegion(-20,40,3),0x3456789000000001L,1);
        final PackageControlQueue queue=new PackageControlQueue();
        final RegistryFriendlyByteBuf wire=new RegistryFriendlyByteBuf(Unpooled.buffer(32768),RegistryAccess.EMPTY);
        final PackageControlBatchCodec.Visitor visitor=this::received;
        int action,seen,packets;long payload;
        Rig(int count,boolean mixed) {
            for(int i=0;i<count;i++) {
                var identity=new PackageLease.Identity(mixed?(i%2==0?Long.MAX_VALUE-i:1L+i):0x1234567800000001L+i,mixed?1L+i%3:17);
                var baseline=new PackageAuthorityRegion.Baseline(i,identity,mixed?1L+i%5:19,mixed?1L+i%7:2,null);
                rows.add(baseline);members.put(identity,baseline);
            }
        }
        void received(int receivedAction,int index,long id,long generation,long lease,long revision) {
            var target=members.get(new PackageLease.Identity(id,generation));
            if(receivedAction!=action||target==null||target.index()!=index||target.leaseEpoch()!=lease||target.revision()!=revision)throw new AssertionError("Identity/phase mismatch");
            seen++;blackhole^=id+generation+lease+revision+index;
        }
        void transport(ServerboundPackagePacket packet) {
            wire.clear();ServerboundPackagePacket.STREAM_CODEC.encode(wire,packet);payload+=wire.readableBytes();packets++;
            var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(wire);if(wire.isReadable())throw new AssertionError("Control envelope tail");
            if(decoded.action()==ServerboundPackagePacket.CONTROL_BATCH)PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(decoded.changes()),visitor);
            else received(decoded.action(),decoded.index(),decoded.identity().id(),decoded.identity().generation(),decoded.leaseEpoch(),decoded.revision());
        }
        void sample(int action,boolean batch) {
            this.action=action;seen=packets=0;payload=0;
            if(!batch)for(var row:rows)transport(ServerboundPackagePacket.control(action,ns.region(),ns.epoch(),row));
            else {
                for(var row:rows)queue.offer(ns,action,row,0);
                if(queue.flush(0,message->{
                    transport(message.single()==null?ServerboundPackagePacket.controls(ns.region(),ns.epoch(),ns.revision(),message.body()):
                            ServerboundPackagePacket.control(message.action(),ns.region(),ns.epoch(),message.single()));return true;
                })!=PackageControlQueue.Result.SENT)throw new AssertionError("Queue not flushed");
            }
            if(seen!=rows.size())throw new AssertionError("Dropped control");
        }
        public void close(){wire.release();}
    }
    public static void main(String[] args)throws Exception {
        var jvmBean=java.lang.management.ManagementFactory.getThreadMXBean();
        var memory=jvmBean instanceof com.sun.management.ThreadMXBean m&&m.isThreadAllocatedMemorySupported()?m:null;
        if(memory!=null&&!memory.isThreadAllocatedMemoryEnabled())memory.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        var csv=new ArrayList<String>();csv.add("records,shape,action,variant,run,pipeline_cpu_p50_ms,pipeline_cpu_p95_ms,allocation_p50_bytes,allocation_p95_bytes,payload_bytes,packets");
        for(int n:new int[]{1,64,256,4096})for(boolean mixed:new boolean[]{false,true})try(var rig=new Rig(n,mixed)) {
            for(int action:new int[]{1,2,4,9})for(int run=0;run<3;run++) {
                // Interleave reference/new samples to reduce thermal/GC/order bias.
                long[][] time={new long[60],new long[60]},allocation={new long[60],new long[60]};
                long[] bytes=new long[2];int[] packets=new int[2];
                for(int sample=-32;sample<60;sample++)for(int turn=0;turn<2;turn++) {
                    int variant=(turn+Math.floorMod(sample,2))&1;
                    long before=memory==null?0:memory.getThreadAllocatedBytes(thread),started=System.nanoTime();
                    rig.sample(action,variant==1);long elapsed=System.nanoTime()-started;
                    long allocated=memory==null?-1:memory.getThreadAllocatedBytes(thread)-before;
                    if(sample>=0){time[variant][sample]=elapsed;allocation[variant][sample]=allocated;bytes[variant]=rig.payload;packets[variant]=rig.packets;}
                }
                for(int variant=0;variant<2;variant++) {
                    String row=n+","+(mixed?"mixed_fields":"shared_fields")+","+action+","+(variant==0?"single":"batch")+","+run+","
                            +String.format(Locale.ROOT,"%.6f,%.6f",p50(time[variant])/1e6,p95(time[variant])/1e6)+","+p50(allocation[variant])+","+p95(allocation[variant])+","+bytes[variant]+","+packets[variant];
                    csv.add(row);System.out.println(row);
                }
            }
        }
        Files.write(Path.of("build/package-control-benchmark.csv"),csv);
    }
}
