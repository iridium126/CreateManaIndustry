package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.api.sublevel.ticket.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.tracking_points.*;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Native tickets and login tracking points travel with the structure, retaining their codec and UUID. */
final class RiftSableMetadata {
    static CompoundTag capture(ServerSubLevel sub) {
        CompoundTag tag=new CompoundTag();ListTag tickets=new ListTag(),points=new ListTag();
        var container=SubLevelContainer.getContainer(sub.getLevel());var info=container.getAllTickets().get(sub.getUniqueId());
        if(info!=null)for(var ticket:info.tickets())tickets.add(encode(ticket));tag.put("ForceTickets",tickets);
        for(var entry:SubLevelTrackingPointSavedData.getOrLoad(sub.getLevel()).getAllTrackingPoints()) {
            TrackingPoint point=entry.getValue();if(!sub.getUniqueId().equals(point.subLevelID()))continue;
            var item=new CompoundTag();item.putUUID("Player",entry.getKey());item.put("Point",SableNBTUtils.writeVector3d(point.point()));
            if(point.globalPlaceholderPosition()!=null)item.put("Placeholder",SableNBTUtils.writeVector3d(point.globalPlaceholderPosition()));points.add(item);
        }
        tag.put("TrackingPoints",points);return tag;
    }
    private static <T> CompoundTag encode(SubLevelLoadingTicket<T> ticket){var tag=new CompoundTag();tag.putString("Type",ticket.type().name().toString());tag.put("Key",ticket.type().codec().encodeStart(NbtOps.INSTANCE,ticket.key()).getOrThrow());return tag;}
    @SuppressWarnings("unchecked") private static <T> void add(ServerSubLevelContainer container,ServerSubLevel sub,CompoundTag tag){var type=(SubLevelLoadingTicketType<T>)SubLevelLoadingTicketType.byName(ResourceLocation.parse(tag.getString("Type")));if(type==null)throw new IllegalStateException("Unknown native structure ticket type");container.addForceLoadTicket(sub,type,type.codec().parse(NbtOps.INSTANCE,tag.get("Key")).getOrThrow());}
    private static <T> void remove(ServerSubLevelContainer container,ServerSubLevel sub,SubLevelLoadingTicket<T> ticket){container.removeForceLoadTicket(sub,ticket.type(),ticket.key());}
    static void apply(ServerLevel source,ServerLevel target,ServerSubLevel canonical,CompoundTag tag,RiftPlotCoordinates coordinates,Vec3 delta,boolean moved) {
        for(ServerLevel world:List.of(source,target)) {
            var container=SubLevelContainer.getContainer(world);var info=container.getAllTickets().get(canonical.getUniqueId());
            var object=container.getSubLevel(canonical.getUniqueId());if(object==null)for(var existing:container.collectForceLoadTickets().keySet())if(existing.getUniqueId().equals(canonical.getUniqueId())){object=existing;break;}if(object==null)object=canonical;
            if(info!=null)for(var ticket:List.copyOf(info.tickets()))remove(container,(ServerSubLevel)object,ticket);
            var points=SubLevelTrackingPointSavedData.getOrLoad(world);for(var entry:points.getAllTrackingPoints())if(canonical.getUniqueId().equals(entry.getValue().subLevelID()))points.removeTrackingPoint(entry.getKey());
        }
        var destination=SubLevelContainer.getContainer(canonical.getLevel());
        for(Tag entry:tag.getList("ForceTickets",Tag.TAG_COMPOUND))add(destination,canonical,(CompoundTag)entry);
        var points=SubLevelTrackingPointSavedData.getOrLoad(canonical.getLevel());
        for(Tag entry:tag.getList("TrackingPoints",Tag.TAG_COMPOUND)) {
            var item=(CompoundTag)entry;var raw=SableNBTUtils.readVector3d(item.getCompound("Point"));Vec3 point=new Vec3(raw.x,raw.y,raw.z);if(moved)point=coordinates.map(point);
            org.joml.Vector3d placeholder=item.contains("Placeholder")?SableNBTUtils.readVector3d(item.getCompound("Placeholder")):null;if(placeholder!=null&&moved)placeholder.add(delta.x,delta.y,delta.z);
            points.setTrackingPoint(item.getUUID("Player"),new TrackingPoint(true,canonical.getUniqueId(),canonical.getLastSerializationPointer(),new org.joml.Vector3d(point.x,point.y,point.z),placeholder));
        }
        for(ServerLevel world:List.of(source,target))SubLevelTicketsSavedData.getOrLoad(world).setDirty();
    }
    private RiftSableMetadata(){}
}
