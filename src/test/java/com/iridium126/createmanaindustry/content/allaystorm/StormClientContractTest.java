package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import static org.junit.jupiter.api.Assertions.*;

class StormClientContractTest {
    @Test void sharedPathStateIsMemberKeyedAndBounded() throws Exception {
        assertEquals(StarfallGpuBuffers.DOME_FLOW_STATE+StarfallGpuBuffers.DOME_FLOW_WORDS,StarfallGpuBuffers.DOME_PATH_BASE);
        assertEquals(128,StarfallGpuBuffers.DOME_PATH_TASK_COUNT);
        assertEquals(12,StarfallGpuBuffers.DOME_PATH_RECORD_WORDS);
        assertTrue(StarfallGpuBuffers.DOME_PATH_BYTES<=32*1024*1024);
        assertEquals(131072,StarfallGpuBuffers.DOME_PATH_RECORDS);
        assertEquals(16,StarfallGpuBuffers.NAV_EXTRA_WORDS);
        try(var source=getClass().getResourceAsStream("/assets/createmanaindustry/shaders/particles/chunks/dome_path_state.glsl")){
            assertNotNull(source);String text=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertMacroOffset(text,"CMI_DOME_PATH_BASE",StarfallGpuBuffers.DOME_PATH_BASE);
        }
    }
    @Test void helixWindowLayoutMatchesShaderAndDoesNotRequireDomeStorage() throws Exception {
        assertEquals(4*4096*4,StarfallGpuBuffers.HELIX_BLOCK_WORDS);
        assertEquals(StarfallGpuBuffers.GATHER_BLOCKED+StarfallGpuBuffers.ROSTER,StarfallGpuBuffers.HELIX_BLOCK_WINDOW);
        assertEquals(StarfallGpuBuffers.HELIX_BLOCK_WINDOW+StarfallGpuBuffers.HELIX_BLOCK_WORDS,StarfallGpuBuffers.DOME_JOIN);
        assertTrue(StarfallGpuBuffers.DOME_JOIN<StarfallGpuBuffers.STATE_WORDS);
        try(var source=getClass().getResourceAsStream("/assets/createmanaindustry/shaders/particles/chunks/starfall.glsl")){
            assertNotNull(source);String text=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertMacroOffset(text,"CMI_HELIX_BLOCK_WINDOW",StarfallGpuBuffers.HELIX_BLOCK_WINDOW);
            assertMacroOffset(text,"CMI_DOME_JOIN",StarfallGpuBuffers.DOME_JOIN);
            assertMacroOffset(text,"CMI_DOME_FLOW_STATE",StarfallGpuBuffers.DOME_FLOW_STATE);
        }
    }
    private static void assertMacroOffset(String text,String name,int expected){
        var declaration=java.util.regex.Pattern.compile("(?m)^#define\\s+"+java.util.regex.Pattern.quote(name)+"\\s+\\((\\d+)u\\)").matcher(text);
        assertTrue(declaration.find(),name);assertEquals(expected,Integer.parseInt(declaration.group(1)),name);
    }
    @Test void motionAndCompletionUseTheCommonStateOrigin() throws Exception {
        for(String name:new String[]{"dome_motion_budget.glsl","dome_completion.glsl"})
            try(var source=getClass().getResourceAsStream("/assets/createmanaindustry/shaders/particles/chunks/"+name)){
                assertNotNull(source);String text=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(text.contains("CMI_STATE(STATE,"),name);assertFalse(text.contains("STATE.words["),name);
            }
    }
    @Test void candidatePrecedesSwingAndStormBranchOnlyPredictsSwing() throws Exception {
        // ServerPlayer.swing resets its attack ticker. Inspect the executed early branch without loading GL/client singletons.
        var calls=new ArrayList<String>();var swings=new ArrayList<String>();
        try(var in=getClass().getClassLoader().getResourceAsStream("com/iridium126/createmanaindustry/client/particles/interaction/ModelInteractionRuntime.class")){
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    if(!name.equals("syntheticAttack"))return null;
                    return new MethodVisitor(Opcodes.ASM9){boolean returned;
                        @Override public void visitInsn(int opcode){if(opcode==Opcodes.IRETURN)returned=true;}
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){if(!returned){calls.add(method);if(method.equals("swing"))swings.add(desc);}}
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.indexOf("reportHit")>=0);assertTrue(calls.indexOf("reportHit")<calls.indexOf("swing"));
        assertEquals(java.util.List.of("(Lnet/minecraft/world/InteractionHand;Z)V"),swings,"Storm prediction must bypass the packet-sending LocalPlayer overload");
        for(String forbidden:new String[]{"playLocalSound","ensureCombatEmitter","setSprinting","setDeltaMovement","causeFoodExhaustion","fireCriticalHit"})assertFalse(calls.contains(forbidden),forbidden);
    }
 @Test void spatialPlayersAreNotTruncatedAndReuseStorage(){var grid=new com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid();float[] xyz=new float[300];for(int i=0;i<100;i++){xyz[i*3]=i%2==0?-i*7:i*7;xyz[i*3+1]=i;xyz[i*3+2]=i*2;}grid.build(xyz,100);var data=grid.data();int table=(int)data[400],found=0;var seen=new BitSet();for(int h=0;h<table;h++){int head=(int)data[(101+h)*4+3];while(head>0){assertFalse(seen.get(head-1));seen.set(head-1);found++;head=(int)data[(head-1)*4+3];}}assertEquals(100,found);grid.build(xyz,100);assertSame(data,grid.data());}
 @Test void slotLayoutHasNoOverlap(){assertEquals(0,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(0));assertEquals(1152,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(3));assertEquals(1536,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(4));assertEquals(1662,com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.completionOffset(67));}
}
