package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageOwnershipListTest {
    private static final class Owner implements PackageOwnershipList.Owner<Object> {
        int checkpoints,releases,removals;
        @Override public void materialize(Object value){checkpoints++;}
        @Override public void released(Object value,boolean removed){releases++;if(removed)removals++;}
    }
    @Test void fullMembershipAndOrderStayIntactWhileCreateVisitsOnlyItsPackages() {
        Object a=new Object(),b=new Object(),c=new Object(),d=new Object();var list=new PackageOwnershipList<>(List.of(a,b,c,d));var owner=new Owner();
        assertTrue(list.acquire(b,owner));assertTrue(list.acquire(d,owner));assertEquals(List.of(a,b,c,d),list);
        assertEquals(List.of(a,c),list.createView());assertEquals(2,list.ownedCount());assertFalse(list.acquire(b,owner));
        list.materializeAll();assertEquals(2,owner.checkpoints);assertEquals(0,owner.releases);
        assertThrows(IllegalStateException.class,list::toCreateList);
        assertTrue(list.restore(b));assertFalse(list.restore(b));assertEquals(List.of(a,b,c),list.createView());assertEquals(1,owner.releases);
        assertTrue(list.restore(d));assertEquals(List.of(a,b,c,d),list.createView());assertEquals(2,owner.releases);assertEquals(0,owner.removals);
        assertInstanceOf(ArrayList.class,list.toCreateList());assertEquals(List.of(a,b,c,d),list.toCreateList());
    }
    @Test void ownershipUsesObjectIdentityAndFailedDuplicatesLeaveBothViewsUntouched() {
        Object a=new String("same"),b=new String("same");var list=new PackageOwnershipList<>(List.of(a,b));var owner=new Owner();
        assertTrue(list.acquire(a,owner));assertTrue(list.contains(b));assertFalse(list.contains(new String("same")));
        assertTrue(list.createView().remove(b));assertEquals(1,list.size());assertThrows(IllegalArgumentException.class,()->list.add(a));
        assertEquals(List.of(a),list);assertTrue(list.isOwned(a));assertEquals(0,owner.releases);
        assertThrows(NullPointerException.class,()->list.add(null));assertEquals(1,list.size());
    }
    @Test void createIteratorsRemoveAndInsertActualObjectsAndFullRemovalRetiresOnce() {
        Object a=new Object(),b=new Object(),c=new Object(),d=new Object();var list=new PackageOwnershipList<>(List.of(a,b,c));var owner=new Owner();list.acquire(b,owner);
        var cpu=list.createView().listIterator();assertSame(a,cpu.next());cpu.remove();assertSame(c,cpu.next());cpu.add(d);
        assertEquals(List.of(b,c,d),list);assertEquals(List.of(c,d),list.createView());
        var all=list.listIterator();assertSame(b,all.next());all.remove();assertEquals(1,owner.removals);assertFalse(list.restore(b));
        assertEquals(List.of(c,d),list);assertSame(c,all.next());assertSame(d,all.next());assertFalse(all.hasNext());
    }
    @Test void replacementsRetireOldIdentityAndCursorDirectionIsPreserved() {
        Object a=new Object(),b=new Object(),c=new Object(),d=new Object();var list=new PackageOwnershipList<>(List.of(a,b,c));var owner=new Owner();list.acquire(b,owner);
        var it=list.listIterator(2);assertSame(b,it.previous());it.set(d);assertEquals(1,owner.removals);
        assertEquals(List.of(a,d,c),list);assertEquals(list,list.createView());assertSame(d,it.next());assertSame(c,it.next());
        assertSame(c,it.previous());it.remove();assertFalse(it.hasNext());assertSame(d,it.previous());
        assertThrows(IllegalArgumentException.class,()->it.set(a));assertEquals(List.of(a,d),list);
    }
    @Test void repeatedMiddleInsertionsRelabelWithoutLosingOwnersOrCreateOrder() {
        Object a=new Object(),b=new Object();var list=new PackageOwnershipList<>(List.of(a,b));var reference=new ArrayList<>(List.of(a,b));var owner=new Owner();list.acquire(b,owner);
        for(int i=0;i<100;i++){Object inserted=new Object();list.add(1,inserted);reference.add(1,inserted);}
        assertEquals(reference,list);reference.remove(b);assertEquals(reference,list.createView());
        list.restore(b);reference.add(b);assertEquals(reference,list.createView());assertEquals(1,owner.releases);
    }
    @Test void fullAndCreateCursorInvalidationMatchTheViewThatChanged() {
        Object a=new Object(),b=new Object();var list=new PackageOwnershipList<>(List.of(a,b));var owner=new Owner();
        var all=list.iterator();var cpu=list.createView().iterator();list.acquire(b,owner);
        assertSame(a,all.next());assertSame(b,all.next());assertThrows(ConcurrentModificationException.class,cpu::next);
        var fresh=list.createView().iterator();list.restore(b);assertThrows(ConcurrentModificationException.class,fresh::next);
        list.add(new Object());assertThrows(ConcurrentModificationException.class,all::next);
    }
    @Test void bulkTeardownClearsAllOwnersDespiteCallbackFailures() {
        Object a=new Object(),b=new Object();var list=new PackageOwnershipList<>(List.of(a,b));int[] called={0};
        var owner=new PackageOwnershipList.Owner<Object>() {
            @Override public void materialize(Object value){}
            @Override public void released(Object value,boolean removed){called[0]++;assertFalse(list.isOwned(value));throw new IllegalStateException("injected callback failure");}
        };
        list.acquire(a,owner);list.acquire(b,owner);var error=assertThrows(IllegalStateException.class,list::restoreAll);
        assertEquals(1,error.getSuppressed().length);assertEquals(2,called[0]);assertEquals(list,list.createView());assertEquals(0,list.ownedCount());
        list.acquire(a,owner);list.acquire(b,owner);assertThrows(IllegalStateException.class,list::clear);
        assertEquals(4,called[0]);assertTrue(list.isEmpty());assertTrue(list.createView().isEmpty());
    }
    @Test void bulkCallbacksCannotMaterializeOrReleaseAReplacementOwner() {
        for(boolean checkpoint:new boolean[]{true,false}) {
            Object a=new Object(),b=new Object();var list=new PackageOwnershipList<>(List.of(a,b));var replacement=new Owner();var original=new Owner();
            var first=new PackageOwnershipList.Owner<Object>() {
                private void replaceOther(){assertTrue(list.restore(b));assertTrue(list.acquire(b,replacement));}
                @Override public void materialize(Object value){if(checkpoint)replaceOther();}
                @Override public void released(Object value,boolean removed){if(!checkpoint)replaceOther();}
            };
            list.acquire(a,first);list.acquire(b,original);
            if(checkpoint)list.materializeAll();else list.restoreAll();
            assertTrue(list.isOwned(b));assertEquals(1,original.releases);
            assertEquals(0,original.checkpoints);assertEquals(0,replacement.checkpoints);assertEquals(0,replacement.releases);
            assertEquals(checkpoint?2:1,list.ownedCount());assertEquals(List.of(a,b),list);
        }
    }
    @Test void randomizedTransfersAndMutationPreserveReferenceListAndIdentityConservation() {
        var list=new PackageOwnershipList<Object>(List.of());var reference=new ArrayList<Object>();var owned=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        var owner=new PackageOwnershipList.Owner<Object>() {
            @Override public void materialize(Object value){assertTrue(owned.contains(value));}
            @Override public void released(Object value,boolean removed){assertTrue(owned.remove(value));assertEquals(!removed,list.contains(value));}
        };
        var random=new Random(7221);
        for(int step=0;step<10000;step++) {
            int op=random.nextInt(7);
            if(reference.isEmpty() || op==0){int at=random.nextInt(reference.size()+1);Object value=new Object();list.add(at,value);reference.add(at,value);}
            else {
                int at=random.nextInt(reference.size());Object value=reference.get(at);
                switch(op) {
                    case 1->{reference.remove(at);assertSame(value,list.remove(at));}
                    case 2->{if(owned.add(value))assertTrue(list.acquire(value,owner));else assertFalse(list.acquire(value,owner));}
                    case 3->{boolean expected=owned.contains(value);assertEquals(expected,list.restore(value));}
                    case 4->{Object next=new Object();reference.set(at,next);assertSame(value,list.set(at,next));}
                    case 5->{var it=list.listIterator(at);assertSame(value,it.next());reference.remove(at);it.remove();}
                    case 6->{if(!owned.contains(value)){reference.remove(value);assertTrue(list.createView().remove(value));}}
                }
            }
            assertEquals(reference,list);assertEquals(reference.stream().filter(v->!owned.contains(v)).toList(),list.createView());assertEquals(owned.size(),list.ownedCount());
        }
        list.materializeAll();list.restoreAll();assertTrue(owned.isEmpty());assertEquals(reference,list.createView());
    }
    @Test void capacityPopulationDoesNotEnterCreateIterationOrCheckpointCallbacks() {
        var values=new ArrayList<Object>(131072);for(int i=0;i<131072;i++)values.add(new Object());
        var list=new PackageOwnershipList<>(values);var owner=new Owner();Object remaining=values.get(65000);
        for(Object value:values)if(value!=remaining)assertTrue(list.acquire(value,owner));
        int visited=0;for(Object value:list.createView()){assertSame(remaining,value);visited++;}
        assertEquals(1,visited);assertEquals(131072,list.size());assertEquals(131071,list.ownedCount());assertEquals(0,owner.checkpoints);
        list.createView().clear();assertEquals(131071,list.size());assertEquals(0,owner.removals);assertTrue(list.createView().isEmpty());
    }
    @Test void mutableContainerCannotBeReadOrChangedOnAWorkerThread() throws InterruptedException {
        var list=new PackageOwnershipList<>(List.of(new Object()));var thrown=new AtomicReference<Throwable>();
        var worker=new Thread(()->{try{list.size();}catch(Throwable error){thrown.set(error);}});worker.start();worker.join();assertInstanceOf(IllegalStateException.class,thrown.get());
    }
    @Test void contiguousCreateCursorHandlesRemovalHolesBothDirectionsAndInsertion() {
        var values=new ArrayList<Object>();for(int i=0;i<1024;i++)values.add(new Object());var list=new PackageOwnershipList<>(values);var owner=new Owner();
        var expected=new ArrayList<Object>();for(int i=0;i<values.size();i++)if(i%2==1)list.acquire(values.get(i),owner);else expected.add(values.get(i));
        var it=list.createView().listIterator();int visited=0;
        while(it.hasNext()){Object value=it.next();if(visited++%3==0){expected.remove(value);it.remove();}}
        assertEquals(expected,list.createView());assertEquals(expected.size(),it.nextIndex());
        for(int i=expected.size()-1;i>=0;i--){assertSame(expected.get(i),it.previous());assertEquals(i,it.nextIndex());}
        Object added=new Object();it.add(added);expected.addFirst(added);assertEquals(1,it.nextIndex());
        assertEquals(expected,list.createView());assertSame(added,it.previous());it.remove();expected.removeFirst();
        assertEquals(expected,list.createView());assertSame(expected.getFirst(),it.next());assertEquals(0,owner.removals);
    }
    @Test void valueReplacementIsVisibleToOtherLiveContiguousCursorsAndMembershipChangesInvalidateThem() {
        var values=new ArrayList<Object>();for(int i=0;i<512;i++)values.add(new Object());var list=new PackageOwnershipList<>(values);var owner=new Owner();
        var first=list.createView().listIterator();var second=list.createView().listIterator();Object replacement=new Object();
        first.next();first.set(replacement);assertSame(replacement,second.next());
        Object next=new Object();list.set(1,next);assertSame(next,second.next());
        first.remove();assertThrows(ConcurrentModificationException.class,second::next);
        var third=list.createView().listIterator();list.acquire(values.get(2),owner);assertThrows(ConcurrentModificationException.class,third::next);
        assertEquals(510,list.createView().size());assertEquals(511,list.size());
    }
}
