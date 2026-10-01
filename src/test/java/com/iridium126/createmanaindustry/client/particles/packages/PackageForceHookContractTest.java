package com.iridium126.createmanaindustry.client.particles.packages;

import java.io.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Resolved ABI checks; actual in-game Mixin application remains a separate test. */
class PackageForceHookContractTest {
    private ClassNode type(String name)throws IOException{try(var in=getClass().getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in);var n=new ClassNode();new ClassReader(in).accept(n,ClassReader.SKIP_DEBUG);return n;}}
    private static long calls(ClassNode n,String method,String owner,String name,String descriptor){return n.methods.stream().filter(m->m.name.equals(method)).flatMap(m->{var calls=new java.util.ArrayList<MethodInsnNode>();for(var i:m.instructions)if(i instanceof MethodInsnNode call)calls.add(call);return calls.stream();}).filter(c->c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(descriptor)).count();}
    @Test void fanHooksMatchCreateAndKeepNativeProcessingCallbacks()throws Exception{
        var fan=type("com/simibubi/create/content/kinetics/fan/AirCurrent");
        assertEquals(1,calls(fan,"tick","com/simibubi/create/content/kinetics/fan/AirCurrent","tickAffectedEntities","(Lnet/minecraft/world/level/Level;)V"));
        assertEquals(1,calls(fan,"tickAffectedEntities","net/minecraft/world/entity/Entity","setDeltaMovement","(Lnet/minecraft/world/phys/Vec3;)V"));
        assertEquals(1,calls(fan,"tickAffectedEntities","com/simibubi/create/content/kinetics/fan/processing/FanProcessingType","affectEntity","(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;)V"));
        assertTrue(fan.fields.stream().anyMatch(f->f.name.equals("caughtEntities")&&f.desc.equals("Ljava/util/List;")));
    }
    @Test void standardPairCallsCanReplaceOnlyPackageVelocity()throws Exception{
        var entity=type("net/minecraft/world/entity/Entity");entity.methods.removeIf(m->!m.desc.equals("(Lnet/minecraft/world/entity/Entity;)V"));
        assertEquals(2,calls(entity,"push","net/minecraft/world/entity/Entity","push","(DDD)V"));
        var box=type("com/simibubi/create/content/logistics/box/PackageEntity");assertTrue(box.methods.stream().anyMatch(m->m.name.equals("push")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;)V")));
    }
}
