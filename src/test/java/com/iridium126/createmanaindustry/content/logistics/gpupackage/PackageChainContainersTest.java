package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainContainersTest {
    @Test void immutableRenderMembershipCanBeTraversedOnFlywheelWorkerAndReusedUntilAChange() throws Exception {
        var a=box(1);var b=box(2);var c=box(3);var connection=new BlockPos(8,0,0);
        Map<BlockPos,List<ChainConveyorPackage>> travel=new HashMap<>();travel.put(connection,new ArrayList<>(List.of(c)));
        var containers=new PackageChainContainers(new ArrayList<>(List.of(a,b)),travel);var owner=new Owner();
        var initial=containers.renderSnapshot();assertSame(initial,containers.renderSnapshot());
        containers.acquire(b,null,owner);var hidden=containers.renderSnapshot();
        assertEquals(List.of(a,b),initial.loop());assertEquals(List.of(a),hidden.loop());
        assertSame(initial.travel().get(connection),hidden.travel().get(connection));
        assertThrows(UnsupportedOperationException.class,()->hidden.loop().add(b));
        assertThrows(UnsupportedOperationException.class,()->hidden.travel().clear());
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals(4,executor.submit(()->{
                int sum=0;for(var item:hidden.loop())sum+=item.netId;
                for(var items:hidden.travel().values())for(var item:items)sum+=item.netId;return sum;
            }).get());
        }
        containers.acquire(c,connection,owner);var allHidden=containers.renderSnapshot();assertTrue(allHidden.travel().isEmpty());
        assertSame(hidden.loop(),allHidden.loop());assertSame(allHidden,containers.renderSnapshot());
        containers.restore(b);var restored=containers.renderSnapshot();assertEquals(List.of(a,b),restored.loop());
        assertEquals(List.of(a),hidden.loop());assertTrue(allHidden.travel().isEmpty());
    }
    private static ChainConveyorPackage box(int id){return new ChainConveyorPackage(id*.1f,null,id);}
    private static final class Owner implements PackageOwnershipList.Owner<ChainConveyorPackage> {
        final List<String> calls=new ArrayList<>();
        @Override public void materialize(ChainConveyorPackage box){calls.add("checkpoint "+box.netId);box.chainPosition=10;}
        @Override public void released(ChainConveyorPackage box,boolean removed){calls.add("release "+box.netId+" "+removed);}
    }
    @Test void actualCreateObjectsAndCountsRemainAvailableOutsideSimulationViews() {
        var a=box(1);var b=box(2);var c=box(3);var connection=new BlockPos(8,0,0);
        Map<BlockPos,List<ChainConveyorPackage>> travel=new HashMap<>();travel.put(connection,new ArrayList<>(List.of(c)));
        var containers=new PackageChainContainers(new ArrayList<>(List.of(a,b)),travel);var owner=new Owner();
        assertTrue(containers.acquire(b,null,owner));assertTrue(containers.acquire(c,connection,owner));
        assertTrue(containers.hasOwned());assertThrows(IllegalStateException.class,containers::unwrap);
        assertEquals(List.of(a,b),containers.allLoop());assertSame(c,travel.get(connection).getFirst());
        assertEquals(List.of(a),containers.createLoop());assertTrue(containers.createTravel().get(connection).isEmpty());
        assertFalse(containers.acquire(b,null,owner));assertFalse(containers.acquire(a,connection,owner));
        containers.materialize(c);assertEquals(10,c.chainPosition);assertEquals(List.of("checkpoint 3"),owner.calls);
        assertTrue(travel.get(connection).remove(c));assertFalse(containers.owns(c));assertEquals("release 3 true",owner.calls.getLast());
        assertTrue(containers.restore(b));assertEquals(List.of(a,b),containers.createLoop());assertEquals("release 2 false",owner.calls.getLast());
        assertFalse(containers.hasOwned());var normalLoop=containers.unwrap();assertEquals(List.of(a,b),normalLoop);
        assertInstanceOf(ArrayList.class,normalLoop);assertInstanceOf(ArrayList.class,travel.get(connection));
    }
    @Test void createAddsAndRemovesOnlyTheChangedTrackWithoutTouchingOtherOwnedItems() {
        var a=box(1);var b=box(2);var first=new BlockPos(8,0,0);var second=new BlockPos(-8,0,0);
        Map<BlockPos,List<ChainConveyorPackage>> travel=new HashMap<>();travel.put(first,new ArrayList<>(List.of(a)));
        var containers=new PackageChainContainers(new ArrayList<>(),travel);var owner=new Owner();containers.acquire(a,first,owner);
        travel.put(second,new ArrayList<>(List.of(b)));containers.syncTravel(second);assertEquals(List.of(b),containers.createTravel().get(second));
        assertTrue(containers.owns(a));assertTrue(owner.calls.isEmpty());containers.acquire(b,second,owner);
        containers.beforeTravelRemoval(second);assertEquals(List.of("checkpoint 2","release 2 false"),owner.calls);
        travel.remove(second);containers.syncTravel(second);assertFalse(containers.createTravel().containsKey(second));assertTrue(containers.owns(a));
        containers.materializeAll();containers.restoreAll();assertFalse(containers.owns(a));assertEquals(List.of(a),containers.createTravel().get(first));
    }
    @Test void teardownAcrossLoopAndTravelDoesNotStopAtAFailedOwnerCallback() {
        var a=box(1);var b=box(2);var connection=new BlockPos(8,0,0);
        Map<BlockPos,List<ChainConveyorPackage>> travel=new HashMap<>();travel.put(connection,new ArrayList<>(List.of(b)));
        var containers=new PackageChainContainers(new ArrayList<>(List.of(a)),travel);int[] released={0};
        var owner=new PackageOwnershipList.Owner<ChainConveyorPackage>() {
            @Override public void materialize(ChainConveyorPackage box){}
            @Override public void released(ChainConveyorPackage box,boolean removed){released[0]++;assertFalse(containers.owns(box));throw new IllegalStateException("injected release failure");}
        };
        containers.acquire(a,null,owner);containers.acquire(b,connection,owner);var failure=assertThrows(IllegalStateException.class,containers::restoreAll);
        assertEquals(2,released[0]);assertEquals(1,failure.getSuppressed().length);assertEquals(List.of(a),containers.createLoop());assertEquals(List.of(b),containers.createTravel().get(connection));
    }
}
