package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** The only lightweight-to-entity path. Ordinary authority failure never calls this class. */
final class PackageLightRestoration {
    private static final Map<PackageEntity,PackageLightStore.Entry> JOINING=new IdentityHashMap<>();
    private PackageLightRestoration() {}

    static boolean joining(ServerLevel level,PackageEntity entity,PackageLightStore store) {
        var entry=JOINING.get(entity);
        return !ServerConfig.packageGpuAuthority&&entry!=null&&entity.level()==level
                &&store.byIdentity(entry.identity)==entry&&entity.getUUID().equals(entry.uuid);
    }
    /** Keep arbitrary entity NBT, but replace stale captured fields with confirmed record state. */
    static CompoundTag entityData(PackageLightStore.Entry entry) {
        var tag=entry.data.copy();var p=entry.state().pose();
        tag.putUUID("UUID",entry.uuid);tag.put("Pos",doubles(p.x(),p.y(),p.z()));
        tag.put("Motion",doubles(p.vx()/20.,p.vy()/20.,p.vz()/20.));
        var rotation=new ListTag();rotation.add(FloatTag.valueOf(p.yaw()));
        rotation.add(FloatTag.valueOf(tag.getList("Rotation",Tag.TAG_FLOAT).size()>1?tag.getList("Rotation",Tag.TAG_FLOAT).getFloat(1):0));
        tag.put("Rotation",rotation);tag.putBoolean("OnGround",(entry.state().flags()&PackageAuthorityRegion.GROUNDED)!=0);
        tag.putFloat("Health",entry.health);tag.putShort("Fire",(short)Math.clamp(entry.fireTicks,0,Short.MAX_VALUE));
        tag.putInt("PortalCooldown",entry.portalCooldown);
        tag.putInt("CMIPackageInsertionDelay",entry.insertionDelay);
        if(entry.tossedBy!=null)tag.putUUID("CMIPackageTossedBy",entry.tossedBy);else tag.remove("CMIPackageTossedBy");
        return tag;
    }
    private static ListTag doubles(double...values){var list=new ListTag();for(double value:values)list.add(DoubleTag.valueOf(value));return list;}

    static PackageEntity restore(ServerLevel level,PackageLightStore.Entry entry) {
        if(ServerConfig.packageGpuAuthority||!level.hasChunkAt(net.minecraft.core.BlockPos.containing(entry.position())))return null;
        var existing=level.getEntity(entry.uuid);
        if(existing!=null)return null; // Do not overwrite another integration's entity on a UUID conflict.
        var box=entry.box(level);if(!com.simibubi.create.content.logistics.box.PackageItem.isPackage(box))return null;
        var p=entry.state().pose();var entity=new PackageEntity(level,p.x(),p.y(),p.z());
        entity.load(entityData(entry));entity.setBox(box.copy());
        // Entity.load clamps excessive NBT motion. The confirmed GPU velocity must survive exactly.
        entity.setPos(p.x(),p.y(),p.z());entity.setDeltaMovement(p.vx()/20.,p.vy()/20.,p.vz()/20.);
        entity.setYRot(p.yaw());entity.setYHeadRot(p.yaw());entity.setYBodyRot(p.yaw());entity.yRotO=p.yaw();
        entity.setOnGround((entry.state().flags()&PackageAuthorityRegion.GROUNDED)!=0);
        entity.setHealth(entry.health);entity.setRemainingFireTicks(entry.fireTicks);entity.setPortalCooldown(entry.portalCooldown);
        entity.insertionDelay=entry.insertionDelay;((PackageInitialEntityAccess)entity).cmi$tossedBy(entry.tossedBy);
        var persistent=entity.getPersistentData();persistent.putLong("CMIGpuPackageId",entry.identity.id());persistent.putLong("CMIGpuPackageGeneration",entry.identity.generation());
        JOINING.put(entity,entry);
        try{return level.addFreshEntity(entity)&&!entity.isRemoved()&&level.getEntity(entry.uuid)==entity?entity:null;}
        finally{JOINING.remove(entity);}
    }
}
