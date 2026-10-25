package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleDiagnosticsReportTest {
    private static Object field(ParticleDiagnostics diagnostics,String name)throws Exception{
        var field=ParticleDiagnostics.class.getDeclaredField(name);field.setAccessible(true);return field.get(diagnostics);
    }
    private static void field(ParticleDiagnostics diagnostics,String name,Object value)throws Exception{
        var field=ParticleDiagnostics.class.getDeclaredField(name);field.setAccessible(true);field.set(diagnostics,value);
    }
    @Test void anUnstartedProfileOnlyShowsOneHint(){
        var diagnostics=new ParticleDiagnostics();var frame=new GpuBudgetLedger().snapshot();
        assertEquals("[CMI profile] off; /cmi particle profile on",diagnostics.summary(frame));
        assertEquals(diagnostics.summary(frame),diagnostics.report(frame));
    }
    @Test @SuppressWarnings("unchecked") void reportsHaveBoundedSummariesAndOrganizedDetails()throws Exception{
        var diagnostics=new ParticleDiagnostics();field(diagnostics,"samples",3);
        var cpu=(double[])field(diagnostics,"cpu");cpu[0]=1;cpu[1]=3;cpu[2]=2;
        var allocations=(double[])field(diagnostics,"allocations");allocations[0]=1024;allocations[1]=2048;allocations[2]=3072;
        var gpu=(Map<String,Double>)field(diagnostics,"gpu");for(int i=0;i<7;i++)gpu.put("stage"+i,i/10.0);
        var frame=new GpuBudgetLedger.Snapshot(10,1_000_000,4.5,4096,1024,0,0,0,0);
        String summary=diagnostics.summary(frame),details=diagnostics.report(frame);
        assertTrue(summary.contains("stopped; last capture"));assertTrue(summary.contains("CPU submit p50/p95=2.000/3.000 ms"));
        assertTrue(summary.contains("alloc=2.0 KiB/frame"));assertTrue(summary.contains("upload=4.0 KiB"));assertTrue(summary.contains("(+2 stages)"));
        assertFalse(summary.contains("Queue"));assertFalse(summary.contains("unmeasured"));
        assertTrue(details.contains("stage0=0.000"));assertTrue(details.contains("\n  stage2="));assertFalse(details.contains("{stage"));
        assertEquals(1,details.split("CPU submit",-1).length-1,"Do not repeat the frame ledger as a second CPU report");
    }
}
