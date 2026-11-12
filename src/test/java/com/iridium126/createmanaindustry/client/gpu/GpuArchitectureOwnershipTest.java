package com.iridium126.createmanaindustry.client.gpu;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuArchitectureOwnershipTest {
    private static final String ROOT="com/iridium126/createmanaindustry/client/";
    private static ClassNode read(String name)throws Exception {
        try(var source=GpuArchitectureOwnershipTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(source,name);var type=new ClassNode();new ClassReader(source).accept(type,ClassReader.SKIP_DEBUG);return type;
        }
    }
    @Test void businessObjectsCannotHoldOrAcceptTheEntireEngine()throws Exception {
        try(var files=Files.walk(Path.of("src/main/java",ROOT))) {
            for(var path:files.filter(p->p.toString().endsWith(".java")).toList()) {
                var name=Path.of("src/main/java").relativize(path).toString().replace('\\','/').replace(".java","");
                if(name.contains("/particles/engine/")||name.contains("/render/scene/"))continue;
                var type=read(name);
                for(var field:type.fields)assertFalse(field.desc.equals("L"+ROOT+"particles/engine/ParticleRuntime;")
                        ||field.desc.equals("L"+ROOT+"particles/engine/CMIParticleEngine;"),name+" holds "+field.name);
                for(var method:type.methods)for(var argument:Type.getArgumentTypes(method.desc))
                    assertFalse(argument.getDescriptor().equals("L"+ROOT+"particles/engine/ParticleRuntime;"),name+" accepts an engine: "+method.name);
            }
        }
    }
    @Test void preservedEmissionApiKeepsItsStableEntryPoints()throws Exception {
        var engine=read(ROOT+"particles/engine/CMIParticleEngine");
        for(String entry:List.of("spawn","stream","removeStream","clear","setAnimation","trySpawnHexParticle","trySpawnHexSpray"))
            assertTrue(engine.methods.stream().anyMatch(m->m.name.equals(entry)),entry);
    }
    @Test void narrowResourcePortsReturnOnlyOperationsOrImmutableValues()throws Exception {
        for(String name:List.of("allaystorm/StormParticlePort","particles/interaction/ModelParticlePort",
                "packages/render/PackageParticlePort","particles/engine/ParticleAppendTarget")) {
            var port=read(ROOT+name);assertTrue(port.fields.isEmpty(),name);
            for(var method:port.methods) {
                var result=Type.getReturnType(method.desc);
                assertFalse(result.getSort()==Type.ARRAY,name+" exposes mutable array");
                assertFalse(result.getDescriptor().contains("ParticleRuntime")||result.getDescriptor().contains("ParticleBuffers"),name+" exposes resource collection");
            }
        }
    }
    @Test void necessaryBusinessAndHexUiCannotReadTheDecorativeEmissionRatio()throws Exception {
        // Enforces independence even when the generic decorative ratio is zero.
        for(String domain:List.of("allaystorm","packages","hex/pattern","particles/interaction")) {
            try(var files=Files.walk(Path.of("src/main/java",ROOT,domain))) {
                for(var path:files.filter(p->p.toString().endsWith(".java")).toList()) {
                    var name=Path.of("src/main/java").relativize(path).toString().replace('\\','/').replace(".java","");
                    for(var method:read(name).methods)for(var instruction:method.instructions) {
                        if(instruction instanceof MethodInsnNode call)
                            assertFalse(call.name.equals("emissionScale"),name+" throttles necessary work / independent UI");
                        if(instruction instanceof FieldInsnNode field)
                            assertFalse(field.owner.endsWith("/ParticleRuntime")&&field.name.equals("scale"),name+" reads decorative ratio");
                    }
                }
            }
        }
    }
}
