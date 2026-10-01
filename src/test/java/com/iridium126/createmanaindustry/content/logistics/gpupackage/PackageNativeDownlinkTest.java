package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNativeDownlink.Action.*;
import static com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNativeDownlink.Kind.*;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PackageNativeDownlinkTest {
    @Test void releaseTeleportAloneCannotRepairTheNextSharedNativeDelta() {
        var routes=new PackageNativeDownlink<Object>();Object owner=new Object(),observer=new Object();
        var server=new VecDeltaCodec();var client=new VecDeltaCodec();var nativeObserver=new VecDeltaCodec();
        server.setBase(new Vec3(1,3,5));client.setBase(server.getBase());nativeObserver.setBase(server.getBase());
        Vec3 moved=new Vec3(10,3,5);
        assertEquals(DROP,routes.route(owner,RELATIVE_POSITION,true));
        assertEquals(SEND,routes.route(observer,RELATIVE_POSITION,false));
        nativeObserver.setBase(nativeObserver.decode(server.encodeX(moved),server.encodeY(moved),server.encodeZ(moved)));
        server.setBase(moved); // Exactly the order in ServerEntity.sendChanges.
        client.setBase(new Vec3(10.5,3,5)); // Immediate handback between native updates.
        Vec3 next=new Vec3(11,3,5);
        assertEquals(11.5,client.decode(server.encodeX(next),0,0).x); // Unprotected recovery is wrong.
        assertEquals(SEND,routes.route(owner,ROTATION,false));
        assertEquals(SEND,routes.route(owner,VELOCITY,false));
        assertEquals(1,routes.pendingRebases());
        assertEquals(REBASE,routes.route(owner,RELATIVE_POSITION,false));
        client.setBase(next);routes.rebased(owner);
        nativeObserver.setBase(nativeObserver.decode(server.encodeX(next),0,0));server.setBase(next);
        Vec3 later=new Vec3(11.25,3,5);
        assertEquals(SEND,routes.route(owner,RELATIVE_POSITION,false));
        assertEquals(nativeObserver.decode(server.encodeX(later),0,0),client.decode(server.encodeX(later),0,0));
        assertEquals(0,routes.pendingRebases());
    }
    @Test void failureAndMetadataCannotDiscardRequiredRebase() {
        var routes=new PackageNativeDownlink<Object>();Object owner=new Object();
        for(var kind:PackageNativeDownlink.Kind.values())
            assertEquals(kind==OTHER?SEND:DROP,routes.route(owner,kind,true));
        assertEquals(SEND,routes.route(owner,OTHER,false));
        assertEquals(REBASE,routes.route(owner,RELATIVE_POSITION,false));
        // A failed enqueue calls no rebased callback; retry must still be absolute.
        assertEquals(REBASE,routes.route(owner,RELATIVE_POSITION,false));
        assertEquals(SEND,routes.route(owner,ABSOLUTE_POSITION,false));
        assertEquals(1,routes.pendingRebases());routes.rebased(owner);
        assertEquals(SEND,routes.route(owner,RELATIVE_POSITION,false));
    }
    @Test void unpairAndReconnectUseConnectionIdentity() {
        var routes=new PackageNativeDownlink<String>();
        String first=new String("same player UUID"),reconnected=new String("same player UUID");
        assertEquals(first,reconnected);
        assertEquals(DROP,routes.route(first,VELOCITY,true));
        assertEquals(SEND,routes.route(reconnected,RELATIVE_POSITION,false));
        routes.unpaired(first);assertEquals(0,routes.pendingRebases());
        assertEquals(SEND,routes.route(first,RELATIVE_POSITION,false));
        assertEquals(DROP,routes.route(reconnected,RELATIVE_POSITION,true));
        routes.unpaired(reconnected);assertEquals(0,routes.pendingRebases());
    }
    @Test void migrationRetainsEachOldOwnersRebaseUntilPositionAndLeavesObserverStreamIntact() {
        var routes=new PackageNativeDownlink<Object>();Object first=new Object(),second=new Object(),observer=new Object();
        assertEquals(DROP,routes.route(first,RELATIVE_POSITION,true));
        assertEquals(DROP,routes.route(second,RELATIVE_POSITION,true));
        assertEquals(2,routes.pendingRebases());
        assertEquals(SEND,routes.route(observer,RELATIVE_POSITION,false));
        assertEquals(REBASE,routes.route(first,RELATIVE_POSITION,false));routes.rebased(first);
        assertEquals(DROP,routes.route(second,ABSOLUTE_POSITION,true));
        assertEquals(1,routes.pendingRebases());
        assertEquals(SEND,routes.route(second,ABSOLUTE_POSITION,false));routes.rebased(second);
        assertEquals(0,routes.pendingRebases());
    }
    @Test void thousandsOfSharedCodecUpdatesWithRotationsAndOwnershipChangesDoNotDrift() {
        Object[] peers={new Object(),new Object(),new Object()};
        VecDeltaCodec[] clients={new VecDeltaCodec(),new VecDeltaCodec(),new VecDeltaCodec()};
        var server=new VecDeltaCodec();var reference=new VecDeltaCodec();var routes=new PackageNativeDownlink<Object>();
        Vec3 initial=new Vec3(20_000_000.25,2_147_483_600.5,-20_000_000.125);
        server.setBase(initial);reference.setBase(initial);for(var c:clients)c.setBase(initial);
        for(int step=0;step<2048;step++) {
            Vec3 current=new Vec3(initial.x+step*.01345,initial.y+Math.sin(step*.13)*.25,initial.z-step*.0079);
            int authority=step%300<100?0:step%300<200?1:-1;
            long dx=server.encodeX(current),dy=server.encodeY(current),dz=server.encodeZ(current);
            reference.setBase(reference.decode(dx,dy,dz));
            for(int p=0;p<peers.length;p++) {
                assertEquals(p==authority?DROP:SEND,routes.route(peers[p],ROTATION,p==authority));
                assertEquals(p==authority?DROP:SEND,routes.route(peers[p],VELOCITY,p==authority));
                assertEquals(SEND,routes.route(peers[p],OTHER,p==authority));
                var action=routes.route(peers[p],RELATIVE_POSITION,p==authority);
                if(action==REBASE){clients[p].setBase(current);routes.rebased(peers[p]);}
                else if(action==SEND)clients[p].setBase(clients[p].decode(dx,dy,dz));
                if(p!=authority)assertEquals(current.x,clients[p].getBase().x,1.0/4096);
                if(p==2)assertEquals(reference.getBase(),clients[p].getBase()); // Always-native observer, exact.
            }
            server.setBase(current);
        }
        for(var peer:peers)routes.unpaired(peer);assertEquals(0,routes.pendingRebases());
    }
}
