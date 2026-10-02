package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.util.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Resolved native ABI and publication ordering, independent of a transformed game startup. */
class PackageAuthorityLifecycleContractTest {
    @Test void serverChainRoutingSkipsVisualPoseLoopsAndResolvesPortsLazily() throws Exception {
        var nativeType=PackageCollisionHookContractTest.type("com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity");
        var tick=nativeType.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow();int calls=0;
        for(var instruction:tick.instructions)if(instruction instanceof MethodInsnNode c&&c.name.equals("updateBoxWorldPositions"))calls++;
        assertEquals(2,calls,"Every native pose loop must be intercepted");
        var mixin=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/mixin/packages/ChainOwnershipContainersMixin");
        var skip=mixin.methods.stream().filter(m->m.name.equals("cmi$skipServerVisualPositions")).findFirst().orElseThrow();
        for(var instruction:skip.instructions)if(instruction instanceof FieldInsnNode f)assertFalse(f.owner.endsWith("/ServerConfig"),"Disabled GPU still needs minimal chain logic");
        for(String name:List.of("cmi$positionForPort","cmi$checkpointForDrop")){
            var method=mixin.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();boolean logical=false;
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode c&&c.name.equals("cmi$logicalPosition"))logical=true;
            assertTrue(logical,name);
        }
    }
    @Test void nativeFreeMotionHookIsRemoved() throws Exception {
        try(var in=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(in);var config=JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            assertFalse(config.getAsJsonArray("mixins").asList().stream().anyMatch(v->v.getAsString().equals("packages.PackageNativeDownlinkMixin")));
        }
        var manager=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager");
        assertTrue(manager.methods.stream().noneMatch(m->m.name.equals("detach")||m.name.equals("maintainCheckpoint")||m.name.equals("nativeMotionOwned")));
    }
    @Test void creationValidatesOriginalItemBeforeCapture() throws Exception {
        var manager=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager");
        var join=manager.methods.stream().filter(m->m.name.equals("onJoin")).findFirst().orElseThrow();
        boolean validated=false,captured=false;
        for(var instruction:join.instructions)if(instruction instanceof MethodInsnNode c){
            if(c.name.equals("cmi$validInitialEntity"))validated=true;
            if(c.owner.endsWith("/PackageLightStore")&&c.name.equals("capture")){assertTrue(validated);captured=true;}
        }
        assertTrue(captured);
    }
    @Test void visibleReadyIsSentOnlyAfterTheClientPublishesItsMatchingGpuClaim() throws Exception {
        var transport=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient$1");
        var activated=transport.methods.stream().filter(m->m.name.equals("activated")).findFirst().orElseThrow();
        boolean claimed=false,sent=false;
        for(var instruction:activated.instructions)if(instruction instanceof MethodInsnNode c) {
            if(c.name.equals("claimLightAfterAdmission"))claimed=true;
            if(c.name.equals("control")){assertTrue(claimed);sent=true;}
        }
        assertTrue(sent);
        var shared=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager");
        for(var m:shared.methods)for(var instruction:m.instructions)if(instruction instanceof MethodInsnNode c)
            assertFalse(c.owner.startsWith("net/minecraft/client/"),"Dedicated server must not link client symbols");
    }
    @Test void serverPauseNeverSendsNativePoseRecovery() throws Exception {
        var manager=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager");
        var control=manager.methods.stream().filter(m->m.name.equals("control")).findFirst().orElseThrow();
        assertTrue(control.instructions.iterator().hasNext());
        assertTrue(manager.fields.stream().noneMatch(f->f.name.equals("entities")));

        var target=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager$EntityTarget");
        var released=target.methods.stream().filter(m->m.name.equals("released")
                &&m.desc.equals("(Lcom/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityRegion$Baseline;)V"))
                .findFirst().orElseThrow();
        int teleport=-1,motion=-1,nativeSends=0,at=0;
        for(var instruction:released.instructions) {
            if(instruction instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW) {
                if(t.desc.equals("net/minecraft/network/protocol/game/ClientboundTeleportEntityPacket")&&teleport<0)teleport=at;
                if(t.desc.equals("net/minecraft/network/protocol/game/ClientboundSetEntityMotionPacket")&&motion<0)motion=at;
            }
            if(instruction instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/server/network/ServerPlayerConnection")
                    &&c.name.equals("send")&&c.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V"))nativeSends++;
            at++;
        }
        assertEquals(-1,teleport);assertEquals(-1,motion);assertEquals(0,nativeSends);
    }
    @Test void freeTransportHasNoNativeRestoration() throws Exception {
        var transport=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient$1");
        assertTrue(transport.methods.stream().noneMatch(m->m.name.equals("restore")));
    }
    @Test void controlFlushFollowsAdmissionPollingAndPrecedesDeltaSubmission() throws Exception {
        var client=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient");
        var pump=client.methods.stream().filter(m->m.name.equals("pump")).findFirst().orElseThrow();
        int at=0,admission=-1,flush=-1,blocked=-1,delta=-1;
        for(var instruction:pump.instructions) {
            if(instruction instanceof MethodInsnNode c) {
                if(c.owner.endsWith("/PackageFreeAcquisitionGpu")&&c.name.equals("pump"))admission=at;
                if(c.owner.endsWith("/PackageControlQueue")&&c.name.equals("flush"))flush=at;
                if(c.owner.endsWith("/PackageDeltaChannel")&&c.name.equals("pump"))delta=at;
            }
            if(instruction instanceof FieldInsnNode f&&f.owner.endsWith("/PackageControlQueue$Result")&&f.name.equals("BLOCKED"))blocked=at;
            at++;
        }
        assertTrue(admission>=0&&flush>admission&&blocked>flush&&delta>blocked);
    }
}
