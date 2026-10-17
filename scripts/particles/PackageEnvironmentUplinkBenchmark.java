import com.iridium126.createmanaindustry.client.particles.packages.PackageEnvironmentUplink;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** CPU staging + actual envelope codec + journal decode A/B. No sockets, world
 * transactions, GPU or render frame; never use this as whole-path acceptance. */
public final class PackageEnvironmentUplinkBenchmark {
    static final class Rig implements AutoCloseable {
        final int count,regions;
        final ByteBuffer[] raw;
        final PackageRegion[] routes;
        final PackageEnvironmentEvent[] expected;
        final int[] next;
        final RegistryFriendlyByteBuf wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
        final PackageEnvironmentUplink uplink;
        boolean batched;
        int packets,records;
        long bytes;
        Rig(int count,int regions) {
            this.count=count;this.regions=regions;next=new int[regions];raw=new ByteBuffer[count];routes=new PackageRegion[regions];expected=new PackageEnvironmentEvent[count];
            for(int r=0;r<regions;r++)routes[r]=new PackageRegion(r-4,-2,3);
            for(int i=0;i<count;i++) {
                var b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
                b.putLong(0,i+1).putLong(8,7).putLong(16,3).putInt(24,1).putInt(40,i+1).putLong(56,9);
                b.putInt(64,2).putLong(80,100).putFloat(92,5).putFloat(96,1).putFloat(100,2).putFloat(104,3).putInt(108,1);raw[i]=b;
                expected[i]=PackageEnvironmentEvent.decode(PackageEnvironmentEvent.regionPayload(b,64,128,256,routes[i%regions]));
            }
            uplink=new PackageEnvironmentUplink(regions,this::transport);
        }
        void transport(ServerboundPackagePacket packet) {
            wire.clear();ServerboundPackagePacket.STREAM_CODEC.encode(wire,packet);bytes+=wire.readableBytes();packets++;
            var decoded=ServerboundPackagePacket.STREAM_CODEC.decode(wire);
            if(wire.isReadable()||decoded.epoch()!=11+decoded.region().x()+4||decoded.revision()!=23)throw new AssertionError("Envelope identity/tail");
            if(batched)for(var event:PackageEnvironmentBatchCodec.decode(decoded.changesView()))received(decoded.region(),event);
            else received(decoded.region(),PackageEnvironmentEvent.decode(decoded.changesView()));
        }
        void received(PackageRegion region,PackageEnvironmentEvent event) {
            int r=region.x()+4,index=r+next[r]*regions;
            if(index>=count||!expected[index].equals(event))throw new AssertionError("Reordered/lost/changed lifecycle journal");
            next[r]++;records++;
        }
        void sample(boolean batch) {
            batched=batch;packets=records=0;bytes=0;Arrays.fill(next,0);
            for(int i=0;i<count;i++) {
                int r=i%regions;
                if(batch)uplink.append(raw[i],64,128,256,routes[r],11+r,23);
                else transport(new ServerboundPackagePacket(ServerboundPackagePacket.ENVIRONMENT,0,routes[r],11+r,0,null,0,23,0,
                        PackageEnvironmentEvent.regionPayload(raw[i],64,128,256,routes[r])));
            }
            if(batch)uplink.flush();
            int wanted=0;for(int r=0;r<regions;r++){int rows=(count+regions-1-r)/regions;wanted+=(rows+23)/24;}
            if(records!=count||packets!=(batch?wanted:count))throw new AssertionError("Dropped work / unexpected packet count");
        }
        public void close(){wire.release();}
    }
    static long percentile(long[] input,double q){var copy=input.clone();Arrays.sort(copy);return copy[Math.max(0,(int)Math.ceil(copy.length*q)-1)];}
    public static void main(String[] args)throws Exception {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        var allocation=bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()?b:null;
        if(allocation!=null&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        var summary=new ArrayList<String>();var samples=new ArrayList<String>();
        summary.add("records,regions,trial,variant,cpu_p50_ms,cpu_p95_ms,cpu_p99_ms,allocation_p50_bytes,packets,wire_bytes");
        samples.add("records,regions,trial,variant,sample,cpu_ns,allocation_bytes");
        for(int n:new int[]{0,1,17,24,25,512})for(int regions:new int[]{1,8})try(var rig=new Rig(n,regions)) {
            for(int trial=0;trial<5;trial++) {
                for(int warm=0;warm<5;warm++)for(int step=0;step<20;step++){rig.sample(false);rig.sample(true);}
                long[][] time={new long[60],new long[60]},alloc={new long[60],new long[60]};long[] bytes=new long[2];int[] packets=new int[2];
                for(int sample=0;sample<60;sample++)for(int turn=0;turn<2;turn++) {
                    int variant=(trial+sample+turn)&1;
                    long before=allocation==null?0:allocation.getThreadAllocatedBytes(thread),started=System.nanoTime();
                    rig.sample(variant==1);long elapsed=System.nanoTime()-started;
                    long allocated=allocation==null?-1:allocation.getThreadAllocatedBytes(thread)-before;
                    time[variant][sample]=elapsed;alloc[variant][sample]=allocated;bytes[variant]=rig.bytes;packets[variant]=rig.packets;
                    samples.add(n+","+regions+","+trial+","+variant+","+sample+","+elapsed+","+allocated);
                }
                for(int variant=0;variant<2;variant++) {
                    String row=n+","+regions+","+trial+","+(variant==0?"single":"batch")+","+String.format(Locale.ROOT,"%.6f,%.6f,%.6f,",
                            percentile(time[variant],.5)/1e6,percentile(time[variant],.95)/1e6,percentile(time[variant],.99)/1e6)
                            +percentile(alloc[variant],.5)+","+packets[variant]+","+bytes[variant];summary.add(row);System.out.println(row);
                }
            }
        }
        var directory=Path.of(System.getProperty("cmi.benchmark.output","build/benchmarks/current"));Files.createDirectories(directory);
        Files.write(directory.resolve("package-environment-uplink.csv"),summary);Files.write(directory.resolve("package-environment-uplink-samples.csv"),samples);
    }
}
