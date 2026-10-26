package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local samples runner; third-party archives are never embedded in the mod or normal unit tests. */
@Tag("shaderpack-samples")
class ShaderPackSamplesTest {
    @Test void allLocalSamplesAndRenamedCopiesHaveEquivalentContracts(@TempDir Path temp)throws Exception{
        Path directory=Path.of(System.getProperty("cmi.shaderpack.samples","run/shaderpacks"));
        List<Path> files;try(var stream=Files.list(directory)){files=stream.filter(p->p.toString().endsWith(".zip")).sorted().toList();}
        assertTrue(files.size()>=8,"Eight local sample archives are required, found "+files.size());
        var report=new StringBuilder("# Local shaderpack source matrix\n\nStatic source/preflight only; this does not certify client visuals or compiled pipelines.\n\n| Sample | SHA256 (source) | Contract | Colored-light preflight | World route | Buffers |\n|---|---|---|---|---|---|\n");
        int index=0;
        for(Path file:files){
            var inv=ShaderPackInventory.readPack(file);assertFalse(inv.sources().isEmpty());
            var plan=ShaderPatchPlan.prepare(inv);var caps=PackCapabilities.detect(inv,plan);
            assertNotEquals(PackCapabilities.WorldPath.BASELINE,caps.worldPath(),file+": all eight accepted local samples require a native auto candidate");
            Path renamed=temp.resolve("unrelated-"+(index++)+".zip");Files.copy(file,renamed);
            var other=ShaderPackInventory.readPack(renamed);assertEquals(inv.digest(),other.digest());
            assertEquals(plan.family(),ShaderPatchPlan.prepare(other).family());
            assertEquals(plan.edits(),ShaderPatchPlan.prepare(other).edits());
            assertEquals(caps,PackCapabilities.detect(other,ShaderPatchPlan.prepare(other)));
            Path folder=temp.resolve("unpacked-"+index);Files.createDirectories(folder);
            for(var entry:inv.sources().entrySet()){Path dest=folder.resolve(entry.getKey());Files.createDirectories(dest.getParent());Files.writeString(dest,entry.getValue());}
            var unpacked=ShaderPackInventory.readPack(folder);assertEquals(inv.digest(),unpacked.digest());
            assertEquals(caps,PackCapabilities.detect(unpacked,ShaderPatchPlan.prepare(unpacked)));
            var sky=NativeRiftPatchPlan.prepare(inv);assertTrue(sky.enabled(),file+": "+sky.reason());
            assertEquals(sky.edits(),NativeRiftPatchPlan.prepare(other).edits());assertEquals(sky.edits(),NativeRiftPatchPlan.prepare(unpacked).edits());
            report.append('|').append(file.getFileName()).append('|').append(inv.digest()).append('|').append(plan.family()).append('|').append(plan.reason()).append('|').append(caps.worldPath()).append('|').append(inv.colorBuffers()).append("|\n");
            if(inv.sources().containsKey("include/lighting/lpv/light_colors.glsl"))assertTrue(plan.enabled(),file+": "+plan.reason());
            for(var edit:plan.edits().entrySet()){assertTrue(inv.sources().containsKey(edit.getKey()));assertNotEquals(inv.source(edit.getKey()),edit.getValue());}
            if(plan.rawLayers()){
                var occupied=new HashMap<>(inv.sources());occupied.put("composite.fsh",inv.source("composite.fsh")+"\nuniform sampler2D colortex9;\n");
                var shifted=ShaderPatchPlan.prepare(ShaderPackInventory.of(occupied));assertTrue(shifted.enabled(),shifted.reason());assertTrue(shifted.rawLayers());
                assertNotEquals(9,shifted.mistBuffer());assertNotEquals(9,shifted.glowBuffer());assertTrue(shifted.edits().get("composite.fsh").contains("uniform sampler2D colortex9;"));
            }
        }
        Path output=Path.of("build/reports/shaderpack/source-matrix.md");Files.createDirectories(output.getParent());Files.writeString(output,report.toString());
    }
}
