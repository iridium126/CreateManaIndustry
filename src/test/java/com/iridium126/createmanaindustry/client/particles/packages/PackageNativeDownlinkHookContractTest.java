package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.util.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Resolved native ABI and publication ordering, independent of a transformed game startup. */
class PackageNativeDownlinkHookContractTest {
    @Test void recipientAndUnpairHooksMatchEveryResolvedTrackerCallSite() throws Exception {
        var tracked=PackageNativePacketHookContractTest.type("net/minecraft/server/level/ChunkMap$TrackedEntity");
        assertTrue(tracked.fields.stream().anyMatch(f->f.name.equals("entity")&&f.desc.equals("Lnet/minecraft/world/entity/Entity;")&&(f.access&Opcodes.ACC_FINAL)!=0));
        for(String method:List.of("broadcast","removePlayer","updatePlayer","broadcastRemoved")) {
            var m=tracked.methods.stream().filter(v->v.name.equals(method)).findFirst().orElseThrow();int calls=0;
            for(var instruction:m.instructions)if(instruction instanceof MethodInsnNode c) {
                if(method.equals("broadcast")&&c.owner.equals("net/minecraft/server/network/ServerPlayerConnection")
                        &&c.name.equals("send")&&c.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V"))calls++;
                else if(!method.equals("broadcast")&&c.owner.equals("net/minecraft/server/level/ServerEntity")
                        &&c.name.equals("removePairing")&&c.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;)V"))calls++;
            }
            assertEquals(1,calls,method);
        }
        try(var in=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(in);var config=JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            assertTrue(config.getAsJsonArray("mixins").asList().stream().anyMatch(v->v.getAsString().equals("packages.PackageNativeDownlinkMixin")));
            assertFalse(config.getAsJsonArray("client").asList().stream().anyMatch(v->v.getAsString().equals("packages.PackageNativeDownlinkMixin")));
        }
    }
    @Test void serverAdvancesSharedPositionBaseAfterItsPositionBroadcastAndHookNeverWritesIt() throws Exception {
        var server=PackageNativePacketHookContractTest.type("net/minecraft/server/level/ServerEntity");
        var send=server.methods.stream().filter(v->v.name.equals("sendChanges")&&v.desc.equals("()V")).findFirst().orElseThrow();
        int at=0,position=-1,broadcast=-1,base=-1;
        for(var instruction:send.instructions) {
            if(instruction instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.startsWith("net/minecraft/network/protocol/game/ClientboundMoveEntityPacket$Pos"))position=at;
            if(instruction instanceof MethodInsnNode c) {
                if(c.owner.equals("java/util/function/Consumer")&&c.name.equals("accept")&&at>position&&position>=0&&base<0)broadcast=at;
                if(c.owner.equals("net/minecraft/network/protocol/game/VecDeltaCodec")&&c.name.equals("setBase")&&position>=0){base=at;break;}
            }
            at++;
        }
        assertTrue(position>=0&&broadcast>position&&base>broadcast);
        var mixin=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/mixin/packages/PackageNativeDownlinkMixin");
        for(var m:mixin.methods)for(var instruction:m.instructions)if(instruction instanceof MethodInsnNode c)
            assertFalse(c.owner.equals("net/minecraft/network/protocol/game/VecDeltaCodec"),"Must not rebase shared server state");
        assertTrue(mixin.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).anyMatch(i->i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("net/minecraft/network/protocol/game/ClientboundTeleportEntityPacket")));
    }
    @Test void visibleReadyIsSentOnlyAfterTheClientPublishesItsMatchingGpuClaim() throws Exception {
        var transport=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient$1");
        var activated=transport.methods.stream().filter(m->m.name.equals("activated")).findFirst().orElseThrow();
        boolean claimed=false,sent=false;
        for(var instruction:activated.instructions)if(instruction instanceof MethodInsnNode c) {
            if(c.name.equals("claimAfterAdmission"))claimed=true;
            if(c.name.equals("control")){assertTrue(claimed);sent=true;}
        }
        assertTrue(sent);
        var shared=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageAuthorityManager");
        for(var m:shared.methods)for(var instruction:m.instructions)if(instruction instanceof MethodInsnNode c)
            assertFalse(c.owner.startsWith("net/minecraft/client/"),"Dedicated server must not link client symbols");
    }
    @Test void nativeRecoveryPrecedesGpuFallbackAndRenderClaimsSurviveUntilRecoveryCompletes() throws Exception {
        var transport=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient$1");
        var restore=transport.methods.stream().filter(m->m.name.equals("restore")).findFirst().orElseThrow();
        boolean nativeFirst=false,cached=false;
        for(var instruction:restore.instructions)if(instruction instanceof MethodInsnNode c) {
            if(c.name.equals("restoreNativeRecovery"))nativeFirst=true;
            if(c.name.equals("retainedCheckpoint")){assertTrue(nativeFirst);cached=true;}
        }
        assertTrue(cached);
        var client=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient");
        var close=client.methods.stream().filter(m->m.name.equals("closeAll")).findFirst().orElseThrow();boolean restored=false;
        for(var instruction:close.instructions)if(instruction instanceof MethodInsnNode c) {
            if(c.owner.endsWith("/PackageFreeAcquisitionGpu")&&c.name.equals("close"))restored=true;
            if(c.owner.endsWith("/PackageRenderOwnership")&&c.name.equals("clear"))assertTrue(restored);
        }
        assertTrue(restored);
    }
    @Test void controlFlushFollowsAdmissionPollingAndPrecedesDeltaSubmission() throws Exception {
        var client=PackageNativePacketHookContractTest.type("com/iridium126/createmanaindustry/client/particles/packages/PackageAuthorityClient");
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
