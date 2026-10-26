package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftGeometry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import net.minecraft.util.Mth;
import org.joml.Vector3d;
import java.util.Set;
import java.util.UUID;
/** Query loaded moving plots explicitly; vanilla noCollision does not include their terrain. */
public final class RiftSableSpace {
    public static boolean intersects(ServerLevel level,AABB box,Set<UUID> ignored) {
        for(var sub:SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if(ignored.contains(sub.getUniqueId()))continue;var bounds=sub.boundingBox();
            if(!new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()).intersects(box))continue;
            AABB local=RiftGeometry.transform(box,p->{Vector3d q=sub.logicalPose().transformPositionInverse(new Vector3d(p.x,p.y,p.z));return new Vec3(q.x,q.y,q.z);});
            for(BlockPos pos:BlockPos.betweenClosed(Mth.floor(local.minX),Mth.floor(local.minY),Mth.floor(local.minZ),Mth.floor(local.maxX),Mth.floor(local.maxY),Mth.floor(local.maxZ))) {
                if(!sub.getPlot().contains(new Vec3(pos.getX(),pos.getY(),pos.getZ())))continue;
                for(AABB shape:level.getBlockState(pos).getCollisionShape(level,pos).toAabbs()) {
                    AABB world=RiftGeometry.transform(shape.move(pos),p->{Vector3d q=sub.logicalPose().transformPosition(new Vector3d(p.x,p.y,p.z));return new Vec3(q.x,q.y,q.z);});if(world.intersects(box))return true;
                }
            }
            for(var contraption:sub.getPlot().getContraptions())if(contraption instanceof net.minecraft.world.entity.Entity entity) {
                AABB world=RiftGeometry.transform(entity.getBoundingBox(),p->{Vector3d q=sub.logicalPose().transformPosition(new Vector3d(p.x,p.y,p.z));return new Vec3(q.x,q.y,q.z);});if(world.intersects(box))return true;
            }
        }
        return false;
    }
    private RiftSableSpace(){}
}
