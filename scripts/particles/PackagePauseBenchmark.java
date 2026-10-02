import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import java.nio.file.*;
import java.util.*;

/** Server authority lifecycle microbenchmark, not a Minecraft tick/network/frame benchmark. */
public class PackagePauseBenchmark {
    static final int COUNT=131072,BATCH=64;
    static final UUID OWNER=new UUID(1,1);
    static final class Target implements PackageAuthorityRegion.Target {
        final PackageLease.Identity identity;
        final PackageAuthorityRegion.Snapshot saved=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(4,4,4,0,0,0,0),0);
        int releases;
        Target(int index){identity=new PackageLease.Identity(index+1,1);}
        public PackageLease.Identity identity(){return identity;}
        public PackageAuthorityRegion.Snapshot snapshot(){return saved;}
        public boolean eligible(){return true;}
        public void apply(PackageAuthorityRegion.Snapshot value){throw new AssertionError("CPU motion or pose recovery was attempted");}
        public void released(PackageAuthorityRegion.Baseline baseline){if(!baseline.snapshot().equals(saved))throw new AssertionError("Saved state changed");releases++;}
    }
    static void acquire(PackageAuthorityRegion region,Target target,long tick){
        var offer=region.offer(target,tick);if(offer==null)throw new AssertionError("Offer rejected");
        var baseline=region.prepared(OWNER,region.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),tick);
        if(baseline==null||!region.finalReady(OWNER,region.epoch(),baseline.index(),target.identity,baseline.leaseEpoch(),baseline.revision(),tick))throw new AssertionError("Acquire rejected");
    }
    static double percentile(List<Long> nanos,double quantile){nanos.sort(Long::compare);return nanos.get((int)Math.ceil(nanos.size()*quantile)-1)/1e6;}
    public static void main(String[] args)throws Exception{
        var raw=new ArrayList<String>();raw.add("operation,run,batch,records,cpu_ms");
        var summary=new ArrayList<String>();summary.add("operation,run,records,pause_cpu_ms,retire_batch_p50_ms,retire_batch_p95_ms,reacquire_batch_p50_ms,reacquire_batch_p95_ms,maximum_batch,cpu_motion_calls");
        for(int run=1;run<=3;run++)for(String operation:List.of("disconnect","gpu_disabled","authority_migration")){
            var region=new PackageAuthorityRegion(new PackageRegion(0,0,0),OWNER,1,1,0);var targets=new Target[COUNT];
            for(int i=0;i<COUNT;i++){targets[i]=new Target(i);acquire(region,targets[i],0);}
            long start=System.nanoTime();region.beginClose();long pause=System.nanoTime()-start;
            if(region.simulated(targets[0].identity,0))throw new AssertionError("Closed region kept authority");
            var retirement=new ArrayList<Long>();int batch=0;
            while(region.size()>0){start=System.nanoTime();int drained=region.drainClose(BATCH);long elapsed=System.nanoTime()-start;if(drained!=BATCH)throw new AssertionError("Unbounded or missing retirement");retirement.add(elapsed);raw.add(operation+","+run+","+batch+++","+drained+","+elapsed/1e6);}
            var next=new PackageAuthorityRegion(new PackageRegion(0,0,0),OWNER,2,1,0);var acquisition=new ArrayList<Long>();
            for(int first=0;first<COUNT;first+=BATCH){start=System.nanoTime();for(int i=first;i<first+BATCH;i++)acquire(next,targets[i],0);long elapsed=System.nanoTime()-start;acquisition.add(elapsed);raw.add("reacquire_after_"+operation+","+run+","+first/BATCH+","+BATCH+","+elapsed/1e6);}
            for(var target:targets)if(target.releases!=1||target.snapshot()!=target.saved)throw new AssertionError("Inventory lifecycle lost or duplicated");
            summary.add(operation+","+run+","+COUNT+","+pause/1e6+","+percentile(retirement,.5)+","+percentile(retirement,.95)+","+percentile(acquisition,.5)+","+percentile(acquisition,.95)+","+BATCH+",0");
        }
        var directory=Path.of("docs/benchmarks/package-pause-2026-10-02");Files.createDirectories(directory);Files.write(directory.resolve("samples.csv"),raw);Files.write(directory.resolve("summary.csv"),summary);summary.forEach(System.out::println);
    }
}
