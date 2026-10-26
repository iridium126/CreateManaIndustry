package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.mixin.allaystorm.create.RiftContraptionAccessor;
import com.simibubi.create.content.contraptions.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Create's own NBT retains mounted storage, actors, stalled state and oriented contraption rotation. */
public class CreateVehicleTransferAdapter implements VehicleTransferAdapter {
    protected final Map<UUID,Map<UUID,Integer>> seats=new LinkedHashMap<>();
    private final Map<UUID,List<AABB>> localBoxes=new HashMap<>();
    private final Map<UUID,Iterator<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo>> geometryCursors=new HashMap<>();
    public String id(){return "create";}
    @Override public Collection<Entity> collect(Entity member) {
        var found=new LinkedHashMap<UUID,Entity>();var pending=new ArrayDeque<Entity>();pending.add(member.getRootVehicle());
        while(!pending.isEmpty()) {
            Entity entity=pending.removeFirst();if(found.putIfAbsent(entity.getUUID(),entity)!=null)continue;
            pending.addAll(entity.getPassengers());
            if(entity instanceof AbstractContraptionEntity contraption&&contraption.getContraption()!=null) {
                if(!(contraption.getContraption() instanceof RiftContraptionAccessor access))throw new IllegalStateException("Create transport ABI mixin unavailable");
                for(UUID id:access.cmi$children().keySet()) {
                    Entity child=findChild(entity,id);
                    if(child==null)throw new IllegalStateException("Attached Create subcontraption is unavailable: "+id);
                    pending.add(child);
                }
            }
        }
        return found.values();
    }
    @Override public void preflight(StormTransferGroup group,ServerLevel target) {
        VanillaVehicleTransferAdapter.INSTANCE.preflight(group,target);
        for(Entity entity:group.members())if(entity instanceof AbstractContraptionEntity contraption) {
            if(contraption.getContraption()==null)throw new IllegalStateException("Uninitialized contraption");
            if(contraption instanceof ControlledContraptionEntity&&!allowsWorldController(contraption))throw new IllegalStateException("Create contraption is attached to a non-migrating world controller");
            if(entity.getClass().getName().contains("CarriageContraption"))throw new IllegalStateException("Railway graph migration is outside this contraption contract");
        }
    }
    protected boolean allowsWorldController(AbstractContraptionEntity entity){return false;}
    protected Entity findChild(Entity parent,UUID id){return ((ServerLevel)parent.level()).getEntity(id);}
    @Override public void beginSnapshot(StormTransferGroup group){seats.clear();localBoxes.clear();geometryCursors.clear();}
    @Override public boolean snapshotReady(StormTransferGroup group,long deadline) {
        for(Entity entity:group.members())if(entity instanceof AbstractContraptionEntity contraption) {
            var structure=contraption.getContraption();if(structure==null)throw new IllegalStateException("Missing contraption");
            seats.computeIfAbsent(entity.getUUID(),ignored->Map.copyOf(structure.getSeatMapping()));
            var cursor=geometryCursors.computeIfAbsent(entity.getUUID(),ignored->structure.getBlocks().values().iterator());
            var boxes=localBoxes.computeIfAbsent(entity.getUUID(),ignored->new ArrayList<>());
            while(cursor.hasNext()) {
                if(System.nanoTime()>=deadline)return false;
                var block=cursor.next();
                for(AABB shape:block.state().getCollisionShape(structure.getContraptionWorld(),block.pos()).toAabbs())boxes.add(shape.move(block.pos()));
            }
        }
        return true;
    }
    @Override public List<AABB> geometry(StormTransferGroup group) {
        var result=new ArrayList<AABB>();
        for(Entity entity:group.members()) {
            if(entity instanceof AbstractContraptionEntity contraption&&localBoxes.containsKey(entity.getUUID())) {
                for(AABB box:localBoxes.get(entity.getUUID()))result.add(RiftGeometry.transform(box,point->contraption.toGlobalVector(point,1)));
            } else result.add(entity.getBoundingBox());
        }
        return result;
    }
    @Override public CompoundTag snapshot(StormTransferGroup group) {
        CompoundTag result=new CompoundTag();ListTag all=new ListTag();
        for(var entry:seats.entrySet()) {
            CompoundTag item=new CompoundTag();item.putUUID("Entity",entry.getKey());ListTag mapping=new ListTag();
            for(var seat:entry.getValue().entrySet()){CompoundTag s=new CompoundTag();s.putUUID("Passenger",seat.getKey());s.putInt("Seat",seat.getValue());mapping.add(s);}
            item.put("Seats",mapping);all.add(item);
        }
        result.put("CreateSeats",all);return result;
    }
    @Override public void beforeEntityTransfer(Entity entity) {
        if(entity instanceof AbstractContraptionEntity contraption&&seats.containsKey(entity.getUUID()))contraption.getContraption().setSeatMapping(new HashMap<>(seats.get(entity.getUUID())));
    }
    @Override public CompoundTag targetSnapshot(Entity entity,CompoundTag original,net.minecraft.world.phys.Vec3 translation) {
        var copy=original.copy();
        if(entity instanceof AbstractContraptionEntity&&copy.getCompound("Contraption").contains("Anchor")) {
            var tag=copy.getCompound("Contraption");var anchor=net.minecraft.nbt.NbtUtils.readBlockPos(tag,"Anchor").orElseThrow();
            var pos=RiftEntitySnapshot.position(original);var shift=net.minecraft.core.BlockPos.containing(pos.add(translation)).subtract(net.minecraft.core.BlockPos.containing(pos));tag.put("Anchor",net.minecraft.nbt.NbtUtils.writeBlockPos(anchor.offset(shift)));
        }
        return copy;
    }
    @Override public void mounted(StormTransferGroup group,Map<UUID,Entity> entities) {
        for(var item:seats.entrySet()) {
            Entity entity=entities.get(item.getKey());if(!(entity instanceof AbstractContraptionEntity contraption))throw new IllegalStateException("Contraption missing after transfer");
            contraption.getContraption().setSeatMapping(new HashMap<>(item.getValue()));
            for(var seat:item.getValue().entrySet()) {
                Entity rider=entities.get(seat.getKey());if(rider==null&&group==null)continue;if(rider==null||rider.getVehicle()!=entity)throw new IllegalStateException("Contraption passenger missing after transfer");
                contraption.addSittingPassenger(rider,seat.getValue());
            }
            if(!contraption.getContraption().getSeatMapping().equals(item.getValue()))throw new IllegalStateException("Contraption seat mapping changed during transfer");
        }
    }
    public void restoreSeats(CompoundTag snapshot) {
        seats.clear();for(Tag tag:snapshot.getList("CreateSeats",Tag.TAG_COMPOUND)) {
            var item=(CompoundTag)tag;var mapping=new LinkedHashMap<UUID,Integer>();
            for(Tag s:item.getList("Seats",Tag.TAG_COMPOUND)){var seat=(CompoundTag)s;mapping.put(seat.getUUID("Passenger"),seat.getInt("Seat"));}
            seats.put(item.getUUID("Entity"),mapping);
        }
    }
}
