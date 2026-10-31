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
    @Test void submissionCountsRetainTheirOwnFrameAndGeneration()throws Exception{
        var d=new ParticleDiagnostics();field(d,"samples",1);field(d,"enabled",true);field(d,"firstFrame",20L);
        d.modelCounts(19,8,999,999,999,999,999,999);
        assertTrue(d.report(new GpuBudgetLedger().snapshot()).contains("MODEL submitted counts: unmeasured"));
        d.modelCounts(21,9,100,3,100,4,5,6);
        String report=d.report(new GpuBudgetLedger().snapshot());
        assertTrue(report.contains("body/carrier/ghost/feather/LOD1/LOD2=100/3/100/4/5/6; frame=21; generation=9"));
        assertFalse(d.summary(new GpuBudgetLedger().snapshot()).contains("submitted counts"));
    }
    @Test void changedContextAndReplacementWorldStartFreshWindows()throws Exception{
        var d=new ParticleDiagnostics();Object world=new Object();
        d.captureContext("1920x1080; pack=photon; options=a",10,world);field(d,"samples",4);
        d.captureContext("1920x1080; pack=photon; options=a",11,world);assertEquals(4,field(d,"samples"));
        d.captureContext("2560x1506; pack=photon; options=a",12,world);assertEquals(0,field(d,"samples"));
        field(d,"samples",4);d.captureContext("2560x1506; pack=photon; options=b",13,world);assertEquals(0,field(d,"samples"));
        field(d,"samples",4);d.captureContext("2560x1506; pack=photon; options=b",14,new Object());assertEquals(0,field(d,"samples"));
        assertEquals(14L,field(d,"firstFrame"));assertEquals(-1L,field(d,"countFrame"));
    }
    @Test void simulationAndVisibleCountsRemainDistinct()throws Exception{
        var d=new ParticleDiagnostics();field(d,"samples",1);field(d,"enabled",true);field(d,"firstFrame",100L);
        d.simulationCounts(99,50,1000,900);assertTrue(d.report(new GpuBudgetLedger().snapshot()).contains("Simulation input counts: unmeasured"));
        d.simulationCounts(101,51,1000,900);d.modelCounts(102,52,19,0,19,2,0,0);
        String report=d.report(new GpuBudgetLedger().snapshot());assertTrue(report.contains("live=1000; MODEL=900; non-model=100; frame=101; input generation=50"));
        assertTrue(report.contains("body/carrier/ghost/feather/LOD1/LOD2=19/0/19/2/0/0"));
    }
}
