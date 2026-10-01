import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOwnershipList;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** CPU container traversal microbenchmark. No game tick, inventory, GL, rendering or network.
 * The GPU-owner callback does no simulation here; active GPU motion is measured separately. */
public final class PackageChainContainerBenchmark {
    private static final class Box {final float progress;Box(int index){progress=(index*0.61803399f)%360;}}
    private static final PackageOwnershipList.Owner<Box> OWNER=new PackageOwnershipList.Owner<>() {
        public void materialize(Box box){throw new AssertionError("Normal traversal scanned an owned checkpoint");}
        public void released(Box box,boolean removed){}
    };
    private static volatile double sink;
    private static double scan(List<Box> boxes){double sum=0;for(Box box:boxes)sum+=box.progress;return sum;}
    private static double percentile(double[] values,double quantile){var sorted=values.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(values.length*quantile)-1];}
    private static void measure(int count,String scenario,String path,int repeat,List<Box> values,
                                List<String> summaries,List<String> samples) {
        long warmUntil=System.nanoTime()+1_000_000_000L;int warm=0;
        while(System.nanoTime()<warmUntil || warm<1000){sink=scan(values);warm++;}
        var bean=ManagementFactory.getThreadMXBean();var allocations=bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported()?b:null;
        if(allocations!=null && !allocations.isThreadAllocatedMemoryEnabled())allocations.setThreadAllocatedMemoryEnabled(true);
        int n=3000;double[] cpu=new double[n];long id=Thread.currentThread().threadId(),bytes=0;
        for(int sample=0;sample<n;sample++) {
            long before=allocations==null?0:allocations.getThreadAllocatedBytes(id);long started=System.nanoTime();
            double checksum=scan(values);long elapsed=System.nanoTime()-started;
            long allocated=allocations==null?0:allocations.getThreadAllocatedBytes(id)-before;sink=checksum;
            if(!Double.isFinite(checksum))throw new AssertionError("Non-finite traversal");cpu[sample]=elapsed/1e6;bytes+=allocated;
            samples.add(count+","+scenario+","+path+","+repeat+","+sample+","+values.size()+","+cpu[sample]+","+allocated);
        }
        String summary=count+","+scenario+","+path+","+repeat+","+n+","+values.size()+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+(allocations==null?-1:bytes/(double)n);
        summaries.add(summary);System.out.println(summary);
    }
    public static void main(String[] args) throws Exception {
        var summaries=new ArrayList<String>();var samples=new ArrayList<String>();
        summaries.add("total_count,scenario,path,repeat,samples,visited,cpu_p50_ms,cpu_p95_ms,allocated_bytes_per_traversal");
        samples.add("total_count,scenario,path,repeat,sample,visited,cpu_ms,allocated_bytes");
        for(int count:new int[]{10000,65536,131072})for(String scenario:List.of("all_create","one_owned","half_create","one_percent_create","all_gpu")) {
            var original=new ArrayList<Box>(count);for(int i=0;i<count;i++)original.add(new Box(i));
            var partition=new PackageOwnershipList<>(original);int expected=0;
            for(int i=0;i<count;i++){boolean create=scenario.equals("all_create") || scenario.equals("one_owned") && i!=0
                        || scenario.equals("half_create") && i%2==0 || scenario.equals("one_percent_create") && i%100==0;
                if(create)expected++;else if(!partition.acquire(original.get(i),OWNER))throw new AssertionError("Duplicate ownership");}
            if(partition.size()!=count || partition.createView().size()!=expected || partition.ownedCount()!=count-expected)throw new AssertionError("Ownership count");
            // Production restores the actual fields to ArrayList when the final owner retires.
            var submitted=partition.ownedCount()==0?partition.toCreateList():partition.createView();
            for(int repeat=1;repeat<=3;repeat++) {
                measure(count,scenario,"original_full_list",repeat,original,summaries,samples);
                measure(count,scenario,partition.ownedCount()==0?"fallback_array_list":"partition_create_view",repeat,submitted,summaries,samples);
            }
        }
        Files.write(Path.of("build/package-chain-containers.csv"),summaries);
        Files.write(Path.of("build/package-chain-containers-samples.csv"),samples);
    }
}
