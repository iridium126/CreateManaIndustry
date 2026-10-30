package com.iridium126.createmanaindustry.client.render.shaderpack;

import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackStatusReport.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShaderPackStatusReportTest {
    @Test void summaryOrdersModulesAndKeepsFallbackReasons(){
        var report=new ShaderPackStatusReport(List.of(
                new Section("Probe",State.OFF,"auto",""),
                new Section("MODEL",State.FALLBACK,"self-drawn","driver rejected source",field("optimization","source unsupported")),
                new Section("Pack",State.ACTIVE,"sample","")));
        var summary=report.lines(false);
        assertEquals(4,summary.size());assertTrue(summary.get(1).contains("Pack:"));assertTrue(summary.get(2).contains("driver rejected source"));
        assertFalse(String.join("\n",summary).contains("optimization"));
        assertEquals(1,report.lines(true).stream().filter(line->line.contains("optimization")).count());
    }
    @Test void everyStateIsDistinctAndUnmeasuredIsNotZero(){
        for(State state:State.values())assertTrue(new ShaderPackStatusReport(List.of(new Section("MODEL",state,"p","reason"))).lines(false).get(1).contains(state.label));
        assertEquals("unmeasured",milliseconds(false,0));assertEquals("0.000 ms",milliseconds(true,0));assertEquals("1.234 ms",milliseconds(true,1.2344));
    }
    @Test void snapshotDoesNotRetainMutableInputsAndDetailsNeverDuplicateSummary(){
        var fields=new ArrayList<Field>();fields.add(field("session",12));var sections=new ArrayList<Section>();
        sections.add(new Section("Pack",State.PENDING,"reload","awaiting current session",fields));
        var report=new ShaderPackStatusReport(sections);fields.clear();sections.clear();
        assertEquals(3,report.lines(true).size());assertEquals(1,report.lines(true).stream().filter(s->s.contains("awaiting current session")).count());
    }
    @Test void missingDependencyDoesNotRemoveIndependentSkyStatus(){
        var report=new ShaderPackStatusReport(List.of(new Section("World",State.UNAVAILABLE,"none","Veil unavailable"),
                new Section("Sky/Cloud",State.ACTIVE,"native","")));
        assertEquals(3,report.lines(false).size());assertTrue(report.lines(false).getLast().contains("Sky/Cloud"));
    }
    @Test void snapshotNeverCompilesReadsSourcesOrStartsMeasurements()throws Exception{
        for(String type:List.of("ShaderPackStatusSnapshot","ShaderPackStatusReport")){
            var code=ShaderPackIntegrationAbiTest.read("com/iridium126/createmanaindustry/client/render/shaderpack/"+type);
            for(var method:code.methods)for(var instruction:method.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                assertFalse(Set.of("ensureCompiled","readPack","readString","begin","poll","render").contains(call.name),type+"."+method.name+" invokes "+call.name);
        }
    }
}
