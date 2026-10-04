import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import java.nio.ByteBuffer;
import java.util.*;

/** Server-thread CPU/allocation benchmark for a production-size relative package delta. */
public final class PackageServerValidationBenchmark {
    static final int RECORDS=512,WARMUP=200,SAMPLES=1200,RUNS=3;
    static final UUID OWNER=new UUID(7,9);
    static final PackageRegion REGION=new PackageRegion(0,0,0);
    static final class Target implements PackageAuthorityRegion.Target {
        final PackageLease.Identity identity;
        PackageAuthorityRegion.Snapshot state;
        Target(int index) {
            identity=new PackageLease.Identity(index+1,1);
            var pose=new PackageLease.Pose(2+(index%16)*2.5,5,2+(index/16)*1.6,1.5f,0,0,0);
            state=new PackageAuthorityRegion.Snapshot(pose,0);
        }
        public PackageLease.Identity identity(){return identity;}
        public PackageAuthorityRegion.Snapshot snapshot(){return state;}
        public boolean eligible(){return true;}
        public void apply(PackageAuthorityRegion.Snapshot next){state=next;}
    }
    static PackageAuthorityRegion region(Target[] targets) {
        var authority=new PackageAuthorityRegion(REGION,OWNER,10,1,0);
        for(var target:targets) {
            var offered=authority.offer(target,0);
            var baseline=authority.prepared(OWNER,10,offered.index(),target.identity,offered.leaseEpoch(),offered.revision(),0);
            if(baseline==null||!authority.finalReady(OWNER,10,baseline.index(),target.identity,baseline.leaseEpoch(),baseline.revision(),0))
                throw new AssertionError("Could not initialize server validation fixture");
        }
        return authority;
    }
    static ServerboundPackagePacket packet() {
        var bytes=ByteBuffer.allocate(ServerboundPackagePacket.MAX_BYTES);
        var writer=new PackageBatchDeltaCodec.Writer().reset(bytes,RECORDS);
        for(int i=0;i<RECORDS;i++)writer.entry(i,PackageDeltaCodec.POSITION,4,0,0,0,0,0,0,0);
        writer.finish();
        byte[] body=Arrays.copyOf(bytes.array(),bytes.position());
        return new ServerboundPackagePacket(ServerboundPackagePacket.RELATIVE_DELTA,0,REGION,10,0,null,0,1,1,body,1);
    }
    static void run(ServerboundPackagePacket packet,PackageAuthorityRegion authority,int firstTick,int count,
                    long[] nanos,long[] allocated,boolean measure,ArrayList<PackageDeltaCodec.Entry> changes,
                    PackageAuthorityRegion.DeltaWorkspace workspace) {
        var threadBean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().threadId();
        for(int sample=0;sample<count;sample++) {
            int tick=firstTick+sample;
            long before=measure?System.nanoTime():0,bytes=measure?threadBean.getThreadAllocatedBytes(thread):0;
            ByteBuffer wire=packet.changesView();
            PackageBatchDeltaCodec.decodeInto(wire,PackageDeltaCodec.MAX_ENTRIES,changes);
            if(wire.hasRemaining())throw new AssertionError("Unexpected trailing delta bytes");
            var result=authority.deltaStepped(OWNER,10,1,tick,tick,changes,4,1,tick,workspace);
            if(result!=PackageAuthorityRegion.Result.ACCEPTED)throw new AssertionError("Server rejected valid fixture at tick "+tick+": "+result+" "+authority.lastDeltaRejection());
            changes.clear();
            if(measure){nanos[sample]=System.nanoTime()-before;allocated[sample]=threadBean.getThreadAllocatedBytes(thread)-bytes;}
        }
    }
    static double percentile(long[] values,double percentile) {
        long[] sorted=values.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(percentile*sorted.length)-1];
    }
    public static void main(String[] args) {
        var threadBean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(!threadBean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Thread allocation counter unavailable");
        threadBean.setThreadAllocatedMemoryEnabled(true);
        System.out.println("records,run,cpu_p50_us,cpu_p95_us,allocated_bytes_per_packet,accepted_packets");
        for(int run=1;run<=RUNS;run++) {
            var targets=new Target[RECORDS];for(int i=0;i<RECORDS;i++)targets[i]=new Target(i);
            var authority=region(targets);var packet=packet();
            var changes=new ArrayList<PackageDeltaCodec.Entry>(RECORDS);
            var workspace=new PackageAuthorityRegion.DeltaWorkspace();
            run(packet,authority,1,WARMUP,new long[0],new long[0],false,changes,workspace);
            long[] nanos=new long[SAMPLES],allocated=new long[SAMPLES];
            run(packet,authority,WARMUP+1,SAMPLES,nanos,allocated,true,changes,workspace);
            long allocation=0;for(long sample:allocated)allocation+=sample;
            System.out.printf(Locale.ROOT,"%d,%d,%.3f,%.3f,%.1f,%d%n",RECORDS,run,
                    percentile(nanos,.50)/1000.0,percentile(nanos,.95)/1000.0,(double)allocation/SAMPLES,SAMPLES);
            var finalPose=targets[RECORDS-1].state.pose();
            if(finalPose.x()<=2+(RECORDS-1)%16*2.5)throw new AssertionError("Fixture made no committed progress");
            authority.close();
        }
    }
}
