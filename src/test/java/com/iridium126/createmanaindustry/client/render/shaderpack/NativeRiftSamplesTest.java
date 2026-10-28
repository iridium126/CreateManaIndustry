package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("shaderpack-samples")
class NativeRiftSamplesTest {
    @Test void allEightNativeSkyCandidatesAreAtomicAndPreserveColoredLightEdits() throws Exception {
        Path directory=Path.of(System.getProperty("cmi.shaderpack.samples","run/shaderpacks"));
        List<Path> samples;try(var files=Files.list(directory)){samples=files.filter(p->p.toString().endsWith(".zip")).sorted().toList();}
        var failures=new ArrayList<String>();var report=new StringBuilder("# Native rift source matrix\n\nSource candidates only; not a real-client acceptance result.\n\n| Sample | Native sky | Result | Edited paths |\n|---|---|---|---|\n");
        for(Path sample:samples) {
            var inventory=ShaderPackInventory.readPack(sample);var colored=ShaderPatchPlan.prepare(inventory);
            var combined=new TreeMap<>(inventory.sources());combined.putAll(colored.edits());
            var plan=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(combined));
            report.append('|').append(sample.getFileName()).append('|').append(plan.family()).append('|').append(plan.reason()).append('|').append(plan.edits().keySet()).append("|\n");
            if(!plan.enabled())failures.add(sample.getFileName()+": "+plan.reason());
            else {
                var skySources=new TreeMap<>(combined);skySources.putAll(plan.edits());
                var density=StormCloudDensityPatchPlan.prepare(ShaderPackInventory.of(skySources),plan.family());
                assertTrue(density.enabled(),sample+": "+density.reason());
                for(var entry:density.edits().entrySet()){
                    assertFalse(entry.getValue().contains("cmiStormCloudPosition"));
                    var broken=new TreeMap<>(skySources);broken.remove(entry.getKey());
                    var rejectedDensity=StormCloudDensityPatchPlan.prepare(ShaderPackInventory.of(broken),plan.family());
                    assertFalse(rejectedDensity.enabled(),sample+" partial density candidate");assertTrue(rejectedDensity.edits().isEmpty());
                    assertTrue(NativeRiftPatchPlan.prepare(ShaderPackInventory.of(combined)).enabled(),"density rejection must not disable sky");
                }
                var formatted=new TreeMap<String,String>();combined.forEach((p,s)->formatted.put(p,"/* loader/version annotation is not an identity */\n"+s.replace("\r\n","\n").replace("\n","\r\n").replace("\t","    ")));
                var formatPlan=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(formatted));assertTrue(formatPlan.enabled(),sample+": "+formatPlan.reason());assertEquals(plan.family(),formatPlan.family());
                for(String path:plan.edits().keySet()) {var ambiguous=new TreeMap<>(combined);ambiguous.put(path,combined.get(path)+"\n"+combined.get(path));var rejected=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(ambiguous));assertFalse(rejected.enabled(),sample+" accepted duplicated anchors in "+path);assertTrue(rejected.edits().isEmpty());}
                for(var entry:plan.edits().entrySet())assertNotEquals(combined.get(entry.getKey()),entry.getValue());
                for(String cache:List.of("program/d0_sky_map.fsh","program/d4a_generate_sky_sh.csh","dimensions/deferred.fsh"))
                    assertFalse(plan.edits().containsKey(cache),"localized rift must not enter an environment cache: "+cache);
                var changed=new TreeMap<>(combined);String main=plan.edits().keySet().stream().filter(p->p.contains("deferred")||p.contains("composite")).findFirst().orElseThrow();
                changed.remove(main);
                var rejected=NativeRiftPatchPlan.prepare(ShaderPackInventory.of(changed));
                assertTrue(!rejected.enabled()||rejected.edits().keySet().stream().noneMatch(p->p.equals(main)));
            }
        }
        Path output=Path.of("build/reports/shaderpack/rift-source-matrix.md");Files.createDirectories(output.getParent());Files.writeString(output,report.toString());
        assertTrue(samples.size()>=8);assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
}
