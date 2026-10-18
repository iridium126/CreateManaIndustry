package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class StormClientCombatContractTest {
    @Test void candidatePrecedesSwingAndStormBranchOnlyPredictsSwing() throws Exception {
        // ServerPlayer.swing resets its attack ticker. Inspect the executed early branch without loading GL/client singletons.
        var calls=new ArrayList<String>();
        try(var in=getClass().getClassLoader().getResourceAsStream("com/iridium126/createmanaindustry/client/particles/interaction/ModelInteractionRuntime.class")){
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    if(!name.equals("syntheticAttack"))return null;
                    return new MethodVisitor(Opcodes.ASM9){boolean returned;
                        @Override public void visitInsn(int opcode){if(opcode==Opcodes.IRETURN)returned=true;}
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){if(!returned)calls.add(method);}
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.indexOf("reportHit")>=0);assertTrue(calls.indexOf("reportHit")<calls.indexOf("swing"));
        for(String forbidden:new String[]{"playLocalSound","ensureCombatEmitter","setSprinting","setDeltaMovement","causeFoodExhaustion","fireCriticalHit"})assertFalse(calls.contains(forbidden),forbidden);
    }
}
