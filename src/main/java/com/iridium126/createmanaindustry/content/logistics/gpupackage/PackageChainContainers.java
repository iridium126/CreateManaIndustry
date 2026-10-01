package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import java.util.*;
import net.minecraft.core.BlockPos;

/** World-thread bridge installed only when a conveyor first prepares an actual GPU acquisition.
 * Original getters expose every item; tick/visual passes receive separately indexed Create views. */
public final class PackageChainContainers {
    public record RenderSnapshot(List<ChainConveyorPackage> loop,Map<BlockPos,List<ChainConveyorPackage>> travel) {}
    private record RenderList(PackageOwnershipList<ChainConveyorPackage> source,int version,List<ChainConveyorPackage> values) {}
    private final PackageOwnershipList<ChainConveyorPackage> loop;
    private final Map<BlockPos,List<ChainConveyorPackage>> allTravel;
    private final Map<BlockPos,List<ChainConveyorPackage>> createTravel=new LinkedHashMap<>();
    private final IdentityHashMap<ChainConveyorPackage,PackageOwnershipList<ChainConveyorPackage>> owned=new IdentityHashMap<>();
    private final Thread thread=Thread.currentThread();
    private RenderList renderedLoop;
    private final Map<BlockPos,RenderList> renderedTravel=new LinkedHashMap<>();
    private RenderSnapshot rendered;
    public PackageChainContainers(List<ChainConveyorPackage> loop,Map<BlockPos,List<ChainConveyorPackage>> travel) {
        this.loop=partition(loop);allTravel=Objects.requireNonNull(travel);
        for(var connection:new ArrayList<>(travel.keySet()))syncTravel(connection);
    }
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Chain containers off world thread");}
    private static PackageOwnershipList<ChainConveyorPackage> partition(List<ChainConveyorPackage> values) {
        if(values instanceof PackageOwnershipList<ChainConveyorPackage> list)return list;
        return new PackageOwnershipList<>(values);
    }
    public List<ChainConveyorPackage> allLoop(){checkThread();return loop;}
    public List<ChainConveyorPackage> createLoop(){checkThread();return loop.createView();}
    public Map<BlockPos,List<ChainConveyorPackage>> createTravel(){checkThread();return createTravel;}
    /** Membership changes invalidate only the affected list. Ordinary frames return the same
     * snapshot without copying or visiting GPU-owned objects. Call once after a transition batch. */
    public RenderSnapshot renderSnapshot() {
        checkThread();boolean changed=rendered==null;
        var next=renderList(loop,renderedLoop);changed|=next!=renderedLoop;renderedLoop=next;
        if(renderedTravel.keySet().removeIf(key->!allTravel.containsKey(key)))changed=true;
        for(var entry:allTravel.entrySet()) {
            var list=(PackageOwnershipList<ChainConveyorPackage>)entry.getValue();var previous=renderedTravel.get(entry.getKey());
            var replacement=renderList(list,previous);if(replacement!=previous){changed=true;renderedTravel.put(entry.getKey(),replacement);}
        }
        if(changed) {
            var travel=new LinkedHashMap<BlockPos,List<ChainConveyorPackage>>();
            for(var entry:renderedTravel.entrySet())if(!entry.getValue().values.isEmpty())travel.put(entry.getKey(),entry.getValue().values);
            rendered=new RenderSnapshot(renderedLoop.values,Collections.unmodifiableMap(travel));
        }
        return rendered;
    }
    private static RenderList renderList(PackageOwnershipList<ChainConveyorPackage> source,RenderList old) {
        int version=source.createMembershipVersion();
        if(old!=null && old.source==source && old.version==version)return old;
        return new RenderList(source,version,List.copyOf(source.createView()));
    }
    /** Create appends through its original addTravellingPackage method; sync only that changed key. */
    public void syncTravel(BlockPos connection) {
        checkThread();var current=allTravel.get(connection);
        if(current==null){createTravel.remove(connection);return;}
        var list=partition(current);if(list!=current)allTravel.put(connection,list);
        createTravel.put(connection,list.createView());
    }
    public boolean acquire(ChainConveyorPackage box,BlockPos connection,PackageOwnershipList.Owner<ChainConveyorPackage> owner) {
        checkThread();Objects.requireNonNull(owner);if(owned.containsKey(box))return false;
        var target=connection==null?loop:allTravel.get(connection);
        if(!(target instanceof PackageOwnershipList<ChainConveyorPackage> list) || !list.contains(box))return false;
        var bridge=new PackageOwnershipList.Owner<ChainConveyorPackage>() {
            @Override public void materialize(ChainConveyorPackage value){owner.materialize(value);}
            @Override public void released(ChainConveyorPackage value,boolean removed){owned.remove(value,list);owner.released(value,removed);}
        };
        if(!list.acquire(box,bridge))return false;owned.put(box,list);return true;
    }
    public boolean owns(ChainConveyorPackage box){checkThread();return owned.containsKey(box);}
    public boolean hasOwned(){checkThread();return !owned.isEmpty();}
    /** Called once when the last lease retires, never in every simulation pass. */
    public List<ChainConveyorPackage> unwrap() {
        checkThread();if(hasOwned())throw new IllegalStateException("Active chain owners cannot be unwrapped");
        var normalLoop=loop.toCreateList();
        allTravel.replaceAll((connection,list)->list instanceof PackageOwnershipList<ChainConveyorPackage> p?p.toCreateList():list);
        createTravel.clear();return normalLoop;
    }
    public boolean restore(ChainConveyorPackage box){checkThread();var list=owned.get(box);return list!=null && list.restore(box);}
    public void materialize(ChainConveyorPackage box){checkThread();var list=owned.get(box);if(list!=null)list.materialize(box);}
    public void materializeAll() {
        checkThread();loop.materializeAll();for(var list:allTravel.values())if(list instanceof PackageOwnershipList<ChainConveyorPackage> p)p.materializeAll();
    }
    /** Always clear every owner even if one integration callback fails. */
    public void restoreAll() {
        checkThread();RuntimeException failure=null;
        try{loop.restoreAll();}catch(RuntimeException error){failure=error;}
        for(var list:new ArrayList<>(allTravel.values()))if(list instanceof PackageOwnershipList<ChainConveyorPackage> p)
            try{p.restoreAll();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    public void beforeTravelRemoval(BlockPos connection) {
        checkThread();var list=allTravel.get(connection);
        if(list instanceof PackageOwnershipList<ChainConveyorPackage> p){p.materializeAll();p.restoreAll();}
    }
}
