import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Downstream CPU/wire microbenchmark, not a game-tick, socket or GPU benchmark. The consumer
 * drains all batches per wave here; production's time budget is deliberately not bypassed. */
public final class PackageObserverBenchmark {
    private static final PackageRegion REGION=new PackageRegion(0,0,0);
    private static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld"),MODEL=ResourceLocation.parse("create:cardboard_package_10x8");
    private static volatile long sink;
    private static PackageDeltaCodec.Quantized state(int index,int wave) {
        return new PackageDeltaCodec.Quantized((index%60)*4096+wave,20000,9000,(short)1024,(short)0,(short)0,(short)0,0);
    }
    private static final class Scenario implements AutoCloseable {
        final int count,dirty;
        final PackageObserverFeed<ClientboundPackageObserverPacket.Visual> feed=new PackageObserverFeed<>();
        final PackageObserverReplica<ClientboundPackageObserverPacket.Visual> replica;
        final PackageObserverFeed.Cursor cursor;
        long tick;
        final RegistryFriendlyByteBuf bytes=new RegistryFriendlyByteBuf(Unpooled.buffer(24576),RegistryAccess.EMPTY);
        final ArrayList<PackageDeltaCodec.Entry> changes=new ArrayList<>(PackageDeltaCodec.MAX_ENTRIES);
        long lastWire,lastBatches,baselineWire,baselineCpu;
        Scenario(int count,int dirty) {
            this.count=count;this.dirty=dirty;replica=new PackageObserverReplica<>(count);
            for(int i=0;i<count;i++)feed.activate(new PackageObserverFeed.Member<>(i,new PackageLease.Identity(i+1L,1),1,2,state(i,0),
                    new ClientboundPackageObserverPacket.Visual(i,new UUID(100,i),MODEL,1,.75f)));
            cursor=feed.subscribe(10);long started=System.nanoTime();drain();baselineCpu=System.nanoTime()-started;baselineWire=lastWire;
            if(replica.size()!=count)throw new AssertionError("Initial observer count");
        }
        long accept(int wave) {
            long started=System.nanoTime();changes.clear();tick++;
            for(int i=0;i<dirty;i++) {
                int index=dirty==count?i:(int)((long)i*count/dirty);
                changes.add(new PackageDeltaCodec.Entry(index,PackageDeltaCodec.POSITION,state(index,wave)));
                if(changes.size()==PackageDeltaCodec.MAX_ENTRIES){feed.accepted(changes,tick);changes.clear();}
            }
            if(!changes.isEmpty())feed.accepted(changes,tick);
            return System.nanoTime()-started;
        }
        long drain() {
            long started=System.nanoTime();lastWire=lastBatches=0;
            for(;;) {
                var batch=feed.poll(cursor,ClientboundPackageObserverPacket.MAX_RECORDS);if(batch==null)break;
                var packet=new ClientboundPackageObserverPacket(DIM,REGION,1,1,batch.stream(),batch.sequence(),
                        (batch.reset()?1:0)|(batch.complete()?2:0),batch.baselines(),batch.changes(),tick,batch.stateTicks());
                bytes.clear();ClientboundPackageObserverPacket.STREAM_CODEC.encode(bytes,packet);lastWire+=bytes.readableBytes();lastBatches++;
                var decoded=ClientboundPackageObserverPacket.STREAM_CODEC.decode(bytes);
                var result=replica.apply(new PackageObserverFeed.Batch<>(decoded.stream(),decoded.sequence(),(decoded.flags()&1)!=0,
                        (decoded.flags()&2)!=0,decoded.baselines(),decoded.changes(),decoded.stateTicks()));
                if(result!=PackageObserverReplica.Result.ACCEPTED || bytes.readableBytes()!=0)throw new AssertionError("Observer wire commit "+result);
            }
            sink=replica.sequence();return System.nanoTime()-started;
        }
        public void close(){feed.unsubscribe(cursor);bytes.release();}
    }
    private static double percentile(double[] values,double p){var copy=values.clone();Arrays.sort(copy);return copy[(int)Math.ceil(copy.length*p)-1];}
    public static void main(String[] args) throws Exception {
        var summaries=new ArrayList<String>();var samples=new ArrayList<String>();
        summaries.add("packages,changed,repeat,samples,accept_p50_ms,accept_p95_ms,downstream_p50_ms,downstream_p95_ms,total_p95_ms,allocated_bytes_per_wave,wire_bytes_per_wave,batches_per_wave,initial_bytes,initial_cpu_ms");
        samples.add("packages,changed,repeat,sample,accept_ms,downstream_ms,total_ms,allocated_bytes,wire_bytes,batches");
        var bean=ManagementFactory.getThreadMXBean();var allocation=bean instanceof com.sun.management.ThreadMXBean b?b:null;
        if(allocation!=null && allocation.isThreadAllocatedMemorySupported() && !allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        for(int count:new int[]{10000,65536,131072})for(int dirty:new int[]{0,count/100,count})for(int repeat=1;repeat<=3;repeat++)try(var scenario=new Scenario(count,dirty)) {
            long until=System.nanoTime()+500_000_000L;int wave=1;
            while(System.nanoTime()<until || wave<8){scenario.accept(wave++);scenario.drain();}
            // Wall-clock warmup performs different iteration counts on different implementations.
            // Reset the pose sequence before measurement so encoded values/byte widths are fixed.
            scenario.accept(1000);scenario.drain();wave=1001;
            int n=dirty==count?32:256;double[] accept=new double[n],downstream=new double[n],total=new double[n];long allocated=0,wire=0,batches=0;
            for(int sample=0;sample<n;sample++) {
                long before=allocation==null?0:allocation.getThreadAllocatedBytes(thread);
                accept[sample]=scenario.accept(wave++)/1e6;downstream[sample]=scenario.drain()/1e6;total[sample]=accept[sample]+downstream[sample];
                long bytes=allocation==null?-1:allocation.getThreadAllocatedBytes(thread)-before;
                allocated+=bytes;wire+=scenario.lastWire;batches+=scenario.lastBatches;
                samples.add(count+","+dirty+","+repeat+","+sample+","+accept[sample]+","+downstream[sample]+","+total[sample]+","+bytes+","+scenario.lastWire+","+scenario.lastBatches);
            }
            if(scenario.replica.size()!=count || dirty>0 && !scenario.replica.member(0).state().equals(state(0,wave-1)))throw new AssertionError("Final observer pose");
            String row=count+","+dirty+","+repeat+","+n+","+percentile(accept,.5)+","+percentile(accept,.95)+","+percentile(downstream,.5)+","+percentile(downstream,.95)
                    +","+percentile(total,.95)+","+(allocated/(double)n)+","+(wire/(double)n)+","+(batches/(double)n)+","+scenario.baselineWire+","+(scenario.baselineCpu/1e6);
            summaries.add(row);System.out.println(row);
        }
        Files.write(Path.of("build/package-observers.csv"),summaries);Files.write(Path.of("build/package-observers-samples.csv"),samples);
    }
}
