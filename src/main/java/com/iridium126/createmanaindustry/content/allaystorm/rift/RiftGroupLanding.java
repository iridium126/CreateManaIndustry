package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import java.util.*;

/** Budgeted candidate/collision walk over actual collision cuboids, not just a vehicle's reference point. */
public final class RiftGroupLanding implements AutoCloseable {
    private final ServerLevel target;
    private final StormTransferGroup group;
    private final RiftChunkTickets tickets;
    private final double x,z;
    private final List<AABB> relative;
    private final AABB bounds;
    private int offset,y,part;
    private Vec3 candidate,result;
    private Iterator<BlockPos> hazards;
    private boolean exhausted;
    private int verificationPart;
    public RiftGroupLanding(ServerLevel target,UUID transaction,StormTransferGroup group,double x,double z) {
        this(target,group,x,z,new RiftChunkTickets(target,transaction));
    }
    private RiftGroupLanding(ServerLevel target,StormTransferGroup group,double x,double z,RiftChunkTickets tickets) {
        this.target=target;this.group=group;this.x=x;this.z=z;
        relative=group.geometry().stream().map(b->b.move(group.reference().scale(-1))).toList();
        if(relative.isEmpty())throw new IllegalStateException("Transport group has no collision geometry");
        bounds=relative.stream().reduce(AABB::minmax).orElseThrow();
        exhausted=bounds.getYsize()>512;
        y=Math.min(383,target.getMaxBuildHeight()-1);
        this.tickets=tickets;tickets.include(bounds.move(x,0,z).inflate(33,0,33));
    }
    public RiftGroupLanding refreshed(StormTransferGroup frozen){return new RiftGroupLanding(target,frozen,x,z,tickets);}
    public boolean step(RiftWorkBudget budget,Collection<AABB> reservations) {
        if(exhausted||result!=null)return true;
        if(!tickets.step(budget))return false;
        while(budget.available()&&offset<RiftLandingOffsets.OFFSETS.size()) {
            if(candidate==null) {
                var d=RiftLandingOffsets.OFFSETS.get(offset);
                if(group.adapter().flying()) {
                    candidate=new Vec3(x+d.x(),y+1-bounds.maxY-0.01,z+d.z());
                } else {
                    BlockPos p=BlockPos.containing(x+d.x(),y,z+d.z());var state=target.getBlockState(p);var shape=state.getCollisionShape(target,p);
                    boolean water=group.members().stream().filter(e->e.getVehicle()==null).anyMatch(e->e instanceof Boat)&&state.getFluidState().is(FluidTags.WATER);
                    if(!group.members().getFirst().getType().isBlockDangerous(state)&&(!shape.isEmpty()||water)) {
                        double surface=y+(water?state.getFluidState().getHeight(target,p):shape.max(Direction.Axis.Y));candidate=new Vec3(x+d.x(),surface-bounds.minY,z+d.z());
                    }
                    if(candidate==null){next();continue;}
                }
                part=0;hazards=null;
            }
            boolean rejected=false;
            while(part<relative.size()&&budget.available()) {
                AABB box=relative.get(part).move(candidate).deflate(1e-6);
                if(hazards==null) {
                    if(!RiftRules.nativeBand(box.minY,box.maxY)||box.minY<target.getMinBuildHeight()||box.maxY>target.getMaxBuildHeight()||!target.getWorldBorder().isWithinBounds(box)||!target.noCollision(group.members().getFirst(),box)||!target.getEntities((net.minecraft.world.entity.Entity)null,box,e->!e.isRemoved()).isEmpty()||VehicleTransferAdapters.occupied(target,box,java.util.Set.of())||reservations.stream().anyMatch(box::intersects)){rejected=true;break;}
                    hazards=BlockPos.betweenClosed(Mth.floor(box.minX),Mth.floor(box.minY),Mth.floor(box.minZ),Mth.floor(box.maxX),Mth.floor(box.maxY),Mth.floor(box.maxZ)).iterator();
                }
                while(hazards.hasNext()&&budget.available()) {
                    var state=target.getBlockState(hazards.next());
                    if(group.members().stream().anyMatch(e->e.getType().isBlockDangerous(state))||state.getFluidState().is(FluidTags.LAVA)){rejected=true;break;}
                }
                if(rejected)break;
                if(!hazards.hasNext()){part++;hazards=null;}
            }
            if(rejected){candidate=null;next();continue;}
            if(part==relative.size()){result=candidate;return true;}
        }
        exhausted=offset>=RiftLandingOffsets.OFFSETS.size();return exhausted;
    }
    private void next(){int floor=Math.max(-128,target.getMinBuildHeight());if(--y<(group.adapter().flying()?floor+bounds.getYsize()-1:floor)){offset++;y=Math.min(383,target.getMaxBuildHeight()-1);}}
    public Vec3 result(){return result;}
    public boolean resident(){return tickets.resident();}
    public List<AABB> reservation(){return result==null?List.of():relative.stream().map(b->b.move(result)).toList();}
    public boolean verify(long deadline,java.util.Set<java.util.UUID> members,java.util.Set<java.util.UUID> structures) {
        if(result==null)throw new IllegalStateException("No selected group landing");
        while(verificationPart<relative.size()&&System.nanoTime()<deadline) {
            AABB box=relative.get(verificationPart++).move(result).deflate(1e-6);
            if(!target.noBlockCollision(null,box)||!target.getEntities((net.minecraft.world.entity.Entity)null,box,e->!e.isRemoved()&&!members.contains(e.getUUID())).isEmpty()||VehicleTransferAdapters.occupied(target,box,structures))throw new IllegalStateException("Target occupancy changed before group publication");
        }
        return verificationPart>=relative.size();
    }
    public boolean exhausted(){return exhausted;}
    public void close(){tickets.close();}
}
