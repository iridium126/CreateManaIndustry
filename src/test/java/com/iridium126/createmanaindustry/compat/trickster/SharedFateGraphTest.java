package com.iridium126.createmanaindustry.compat.trickster;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedFateGraphTest {
    @Test void rejectsSelfDuplicatesAndAllDirectedCycles() {
        var graph=new SharedFateGraph();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        graph.add(UUID.randomUUID(),a,b,true);graph.add(UUID.randomUUID(),b,c,true);
        assertEquals(SharedFateGraph.Rejection.SELF,graph.check(a,a,true));
        assertEquals(SharedFateGraph.Rejection.DUPLICATE,graph.check(a,b,true));
        assertEquals(SharedFateGraph.Rejection.CYCLE,graph.check(b,a,true));
        assertEquals(SharedFateGraph.Rejection.CYCLE,graph.check(c,a,true));
        assertTrue(graph.outgoing(c).isEmpty());assertEquals(List.of(c),graph.outgoing(b).stream().map(SharedFateGraph.Edge::target).toList());
    }
    @Test void removingAnEdgePermitsRebindingAndBreaksReachability() {
        var graph=new SharedFateGraph();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        var ab=graph.add(UUID.randomUUID(),a,b,true);graph.add(UUID.randomUUID(),b,c,true);
        graph.remove(ab);
        assertEquals(SharedFateGraph.Rejection.NONE,graph.check(c,a,true));
        assertEquals(SharedFateGraph.Rejection.NONE,graph.check(a,b,true));
    }
    @Test void mergedPathsCanVisitEveryEntityOnceWithoutReverseEdges() {
        var graph=new SharedFateGraph();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
        graph.add(UUID.randomUUID(),a,b,true);graph.add(UUID.randomUUID(),a,c,true);
        graph.add(UUID.randomUUID(),b,d,false);graph.add(UUID.randomUUID(),c,d,false);
        var visited=new HashSet<UUID>();var queue=new ArrayDeque<UUID>();queue.add(a);
        while(!queue.isEmpty()){UUID node=queue.removeFirst();if(!visited.add(node))continue;graph.outgoing(node).forEach(e->queue.add(e.target()));}
        assertEquals(Set.of(a,b,c,d),visited);assertTrue(graph.outgoing(d).isEmpty());
    }
    @Test void chainLegacyEncodingRemainsThreeFieldsWithIndependentTargets() {
        var chain=new CastChain();String old=chain.encode();UUID entity=UUID.randomUUID();chain.addFateTarget(entity);
        assertEquals(old,chain.encode());assertTrue(CastChain.decode(old).fateTargets().isEmpty());
        chain.restoreFateTargets(List.of(entity.toString()));assertEquals(List.of(entity),chain.fateTargets());
        assertEquals(GreatPattern.SHARED_FATE,GreatPattern.fromBytes(GreatPattern.SHARED_FATE.bytes()));
        assertNotEquals(GreatPattern.INVERSION,GreatPattern.SHARED_FATE);
    }
}
