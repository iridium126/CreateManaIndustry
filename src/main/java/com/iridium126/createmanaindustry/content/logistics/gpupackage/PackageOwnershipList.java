package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;

/** Internal identity list with an independently indexed Create-only view. Preserves full-list
 * order, objects and count for persistence/capacity/interactions. Tick iteration visits no owned
 * objects, rather than filtering the whole population. Mutations run on the owning world thread. */
public final class PackageOwnershipList<E> extends AbstractSequentialList<E> {
    public interface Owner<E> {
        /** Materialize a validated checkpoint on demand, never once per normal tick. */
        void materialize(E value);
        /** Called once AFTER ownership is cleared. removed=true cannot restore the item to this list. */
        void released(E value,boolean removed);
    }
    private static final long GAP=1L<<32;
    private static final class Node<E> {
        E value;Node<E> previous,next,createPrevious,createNext;long key;Owner<E> owner;
        Node(E value){this.value=value;}
    }
    private record Ownership<E>(Node<E> node,Owner<E> owner) {}
    private final IdentityHashMap<E,Node<E>> index=new IdentityHashMap<>();
    private final NavigableMap<Long,Node<E>> create=new TreeMap<>(),owned=new TreeMap<>();
    private final Thread thread=Thread.currentThread();
    private Node<E> first,last,createFirst,createLast;
    private int createModCount;
    private final CreateMirror mirror=new CreateMirror();
    private int mirrorVersion=-1;
    private final List<E> createView=new AbstractList<>() {
        @Override public int size(){checkThread();return create.size();}
        @Override public Iterator<E> iterator(){checkThread();return currentMirror().iterator();}
        @Override public ListIterator<E> listIterator(int start){checkThread();return currentMirror().listIterator(start);}
        @Override public E get(int at){checkThread();return currentMirror().get(at);}
        @Override public E set(int at,E value){checkThread();return currentMirror().set(at,value);}
        @Override public void add(int at,E value){checkThread();currentMirror().add(at,value);}
        @Override public E remove(int at){checkThread();return currentMirror().remove(at);}
        @Override public boolean remove(Object value){checkThread();Node<E> n=index.get(value);if(n==null || n.owner!=null)return false;unlink(n);return true;}
        @Override public boolean contains(Object value){checkThread();Node<E> n=index.get(value);return n!=null && n.owner==null;}
        @Override public void clear(){checkThread();while(createFirst!=null)unlink(createFirst);}
    };
    public PackageOwnershipList(Collection<? extends E> initial){addAll(initial);}
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Package container off owning world thread");}
    @Override public int size(){checkThread();return index.size();}
    @Override public boolean contains(Object value){checkThread();return index.containsKey(value);}
    @Override public boolean remove(Object value){checkThread();Node<E> n=index.get(value);if(n==null)return false;unlink(n);return true;}
    @Override public ListIterator<E> listIterator(int start){checkThread();return new Cursor(start,false);}
    public List<E> createView(){checkThread();return createView;}
    public int createMembershipVersion(){checkThread();return createModCount;}
    public int ownedCount(){checkThread();return owned.size();}
    /** One-time epoch/final-owner teardown. Preserve native ArrayList traversal after fallback. */
    public List<E> toCreateList() {
        checkThread();if(!owned.isEmpty())throw new IllegalStateException("Cannot unwrap GPU-owned packages");
        var result=new ArrayList<E>(index.size());for(Node<E> node=first;node!=null;node=node.next)result.add(node.value);return result;
    }
    public boolean isOwned(E value){checkThread();Node<E> node=index.get(value);return node!=null && node.owner!=null;}
    public boolean acquire(E value,Owner<E> owner) {
        checkThread();Objects.requireNonNull(owner);Node<E> node=index.get(value);
        if(node==null || node.owner!=null)return false;
        unlinkCreate(node);owned.put(node.key,node);node.owner=owner;createChanged();return true;
    }
    public boolean restore(E value) {
        checkThread();Node<E> node=index.get(value);if(node==null || node.owner==null)return false;
        Owner<E> owner=node.owner;node.owner=null;owned.remove(node.key);linkCreate(node);createChanged();
        owner.released(value,false);return true;
    }
    public void materialize(E value){checkThread();Node<E> node=index.get(value);if(node!=null && node.owner!=null)node.owner.materialize(value);}
    private List<Ownership<E>> ownershipSnapshot() {
        var snapshot=new ArrayList<Ownership<E>>(owned.size());
        for(Node<E> node:owned.values())snapshot.add(new Ownership<>(node,node.owner));
        return snapshot;
    }
    private boolean current(Ownership<E> entry) {
        return index.get(entry.node.value)==entry.node && entry.node.owner==entry.owner;
    }
    public void materializeAll() {
        checkThread();RuntimeException failure=null;
        // Callbacks may retire or reacquire another item. Never invoke its replacement owner.
        for(var entry:ownershipSnapshot())if(current(entry)) {
            try{entry.owner.materialize(entry.node.value);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null)throw failure;
    }
    public void restoreAll() {
        checkThread();
        // Epoch teardown only. Retain the exact initial owners if callbacks mutate other entries.
        var snapshot=ownershipSnapshot();
        RuntimeException failure=null;
        for(var entry:snapshot)if(current(entry)) {
            try{restore(entry.node.value);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null)throw failure;
    }
    @Override public void clear() {
        checkThread();if(index.isEmpty())return;
        Node<E> previousFirst=first;first=last=createFirst=createLast=null;index.clear();create.clear();owned.clear();modCount++;createChanged();
        RuntimeException failure=null;
        for(Node<E> n=previousFirst;n!=null;) {
            Node<E> next=n.next;Owner<E> owner=n.owner;n.owner=null;n.previous=n.next=n.createPrevious=n.createNext=null;
            if(owner!=null)try{owner.released(n.value,true);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            n=next;
        }
        if(failure!=null)throw failure;
    }
    private void unlink(Node<E> node) {
        index.remove(node.value);if(node.owner==null)unlinkCreate(node);else owned.remove(node.key);
        if(node.previous==null)first=node.next;else node.previous.next=node.next;
        if(node.next==null)last=node.previous;else node.next.previous=node.previous;
        Owner<E> owner=node.owner;node.owner=null;node.previous=node.next=null;modCount++;createChanged();
        if(owner!=null)owner.released(node.value,true);
    }
    private void unlinkCreate(Node<E> node) {
        create.remove(node.key);
        if(node.createPrevious==null)createFirst=node.createNext;else node.createPrevious.createNext=node.createNext;
        if(node.createNext==null)createLast=node.createPrevious;else node.createNext.createPrevious=node.createPrevious;
        node.createPrevious=node.createNext=null;
    }
    private void linkCreate(Node<E> node) {
        var before=create.lowerEntry(node.key);var after=create.higherEntry(node.key);
        node.createPrevious=before==null?null:before.getValue();node.createNext=after==null?null:after.getValue();
        if(node.createPrevious==null)createFirst=node;else node.createPrevious.createNext=node;
        if(node.createNext==null)createLast=node;else node.createNext.createPrevious=node;
        create.put(node.key,node);
    }
    private void relabel() {
        create.clear();owned.clear();long key=GAP;
        for(Node<E> node=first;node!=null;node=node.next) {
            node.key=key;key=Math.addExact(key,GAP);(node.owner==null?create:owned).put(node.key,node);
        }
        createChanged();
    }
    private void insert(Node<E> before,E value) {
        Objects.requireNonNull(value);if(index.containsKey(value))throw new IllegalArgumentException("Duplicate package object in container");
        Node<E> previous=before==null?last:before.previous;
        if(previous!=null && before!=null && before.key-previous.key<=1
                || previous==null && before!=null && before.key<Long.MIN_VALUE+GAP
                || before==null && previous!=null && previous.key>Long.MAX_VALUE-GAP)relabel();
        long key=previous==null?(before==null?GAP:before.key-GAP):before==null?previous.key+GAP:previous.key+(before.key-previous.key)/2;
        Node<E> node=new Node<>(value);node.key=key;node.previous=previous;node.next=before;
        if(previous==null)first=node;else previous.next=node;
        if(before==null)last=node;else before.previous=node;
        index.put(value,node);linkCreate(node);modCount++;createChanged();
    }
    private void createChanged(){createModCount++;mirror.invalidate();mirrorVersion=-1;}
    private CreateMirror currentMirror(){if(mirrorVersion!=createModCount)mirror.rebuild();return mirror;}
    /** The native ArrayList iterator keeps the hot consumer loop identical to Create. Rebuild
     * only the Create subset after membership changes, never the GPU-owned population. */
    private final class CreateMirror extends ArrayList<E> {
        void invalidate(){modCount++;}
        void rebuild() {
            super.clear();ensureCapacity(create.size());
            for(Node<E> node=createFirst;node!=null;node=node.createNext)super.add(node.value);
            mirrorVersion=createModCount;
        }
        void replaceValue(E old,E value) {
            if(mirrorVersion==createModCount)for(int i=0;i<super.size();i++)if(super.get(i)==old){super.set(i,value);return;}
        }
        @Override public E remove(int at) {
            checkThread();E value=super.get(at);unlink(index.get(value));E removed=super.remove(at);mirrorVersion=createModCount;return removed;
        }
        @Override public void add(int at,E value) {
            checkThread();if(at<0 || at>super.size())throw new IndexOutOfBoundsException(at);
            Node<E> before=at==super.size()?null:index.get(super.get(at));insert(before,value);super.add(at,value);mirrorVersion=createModCount;
        }
        @Override public E set(int at,E value) {
            checkThread();Objects.requireNonNull(value);E old=super.get(at);if(old==value)return old;
            if(index.containsKey(value))throw new IllegalArgumentException("Duplicate package object in container");
            Node<E> node=index.remove(old);node.value=value;index.put(value,node);super.set(at,value);return old;
        }
    }
    private final class Cursor implements ListIterator<E> {
        private final boolean legacy;
        private Node<E> next,returned;
        private int position,expected;
        Cursor(int start,boolean legacy) {
            this.legacy=legacy;int count=legacy?create.size():index.size();
            if(start<0 || start>count)throw new IndexOutOfBoundsException(start);
            position=start;expected=legacy?createModCount:modCount;
            if(start<count) {
                if(legacy){next=createFirst;for(int i=0;i<start;i++)next=next.createNext;}
                else{next=first;for(int i=0;i<start;i++)next=next.next;}
            }
        }
        private void valid(){checkThread();if(expected!=(legacy?createModCount:modCount))throw new ConcurrentModificationException();}
        private void refresh(){expected=legacy?createModCount:modCount;}
        private Node<E> following(Node<E> node){return legacy?node.createNext:node.next;}
        private Node<E> preceding(Node<E> node){return legacy?node==null?createLast:node.createPrevious:node==null?last:node.previous;}
        @Override public boolean hasNext(){valid();return next!=null;}
        @Override public boolean hasPrevious(){valid();return position>0;}
        @Override public E next(){valid();if(next==null)throw new NoSuchElementException();returned=next;next=following(next);position++;return returned.value;}
        @Override public E previous(){valid();Node<E> node=preceding(next);if(node==null)throw new NoSuchElementException();next=returned=node;position--;return node.value;}
        @Override public int nextIndex(){valid();return position;}
        @Override public int previousIndex(){valid();return position-1;}
        @Override public void remove(){valid();if(returned==null)throw new IllegalStateException();if(next==returned)next=following(returned);else position--;
            Node<E> node=returned;returned=null;try{unlink(node);}finally{refresh();}}
        @Override public void add(E value){valid();insert(next,value);position++;returned=null;refresh();}
        @Override public void set(E value) {
            valid();if(returned==null)throw new IllegalStateException();Objects.requireNonNull(value);
            if(value==returned.value)return;if(index.containsKey(value))throw new IllegalArgumentException("Duplicate package object in container");
            E old=returned.value;Owner<E> owner=returned.owner;index.remove(old);returned.value=value;returned.owner=null;index.put(value,returned);
            if(owner!=null){owned.remove(returned.key);linkCreate(returned);createChanged();}
            else mirror.replaceValue(old,value);
            refresh();if(owner!=null)owner.released(old,true);
        }
    }
}
