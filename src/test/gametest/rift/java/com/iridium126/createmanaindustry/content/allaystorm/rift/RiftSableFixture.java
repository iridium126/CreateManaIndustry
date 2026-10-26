package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

/** Exercises the installed Rapier pipeline and Simulated spring ABI, not mock structure objects. */
final class RiftSableFixture {
    static void run(GameTestHelper helper) {
        var source=helper.getLevel();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);var player=RiftTransportGameTests.player(source);
        Vec3 point=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3)));double altitude=source.getMaxBuildHeight()+110;
        var main=make(source,new Vec3(point.x,altitude,point.z));var child=make(source,new Vec3(point.x+5,altitude,point.z));var a=main.getPlot().getCenterBlock().atY(64);var b=child.getPlot().getCenterBlock().atY(64);
        source.setBlock(a,Blocks.CHEST.defaultBlockState(),3);source.setBlock(b,Blocks.CHEST.defaultBlockState(),3);
        source.setBlock(a.offset(1,0,0),Blocks.OAK_PLANKS.defaultBlockState(),3);source.setBlock(b.offset(1,0,0),Blocks.OAK_PLANKS.defaultBlockState(),3);
        ((ChestBlockEntity)source.getBlockEntity(a)).setItem(0,new ItemStack(Items.DIAMOND,23));((ChestBlockEntity)source.getBlockEntity(b)).setItem(0,new ItemStack(Items.EMERALD,31));
        Block spring=BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("simulated","spring"));helper.assertTrue(spring!=Blocks.AIR,"Installed spring registry missing");source.setBlock(a.offset(2,0,0),spring.defaultBlockState(),3);source.setBlock(b.offset(2,0,0),spring.defaultBlockState(),3);
        link(source,a.offset(2,0,0),b.offset(2,0,0),child.getUniqueId(),true);link(source,b.offset(2,0,0),a.offset(2,0,0),main.getUniqueId(),false);
        for(var sub:java.util.List.of(main,child)){BlockPos center=sub.getPlot().getCenterBlock().atY(64);sub.getPlot().onBlockChange(center,source.getBlockState(center));sub.getPlot().onBlockChange(center.offset(2,0,0),source.getBlockState(center.offset(2,0,0)));sub.getPlot().updateBoundingBox();sub.buildMassTracker();sub.updateMergedMassData(1);SubLevelContainer.getContainer(source).physicsSystem().getPipeline().onStatsChanged(sub);}
        var stand=EntityType.ARMOR_STAND.create(source);stand.setNoGravity(true);stand.moveTo(a.getX()+.5,65,a.getZ()+.5,38,0);source.addFreshEntity(stand);
        player.startRiding(stand,true);
        player.teleportTo(point.x,altitude+2,point.z);((EntityMovementExtension)player).sable$setTrackingSubLevel(main);((EntityMovementExtension)player).sable$setLastTrackingSubLevelID(main.getUniqueId());
        var fixtureGroup=StormTransferGroup.collect(player,VehicleTransferAdapters.select(player));helper.assertTrue(fixtureGroup.adapter().structures().size()==2,"Fixture spring closure missing: "+source.getBlockEntity(a.offset(2,0,0)).saveWithFullMetadata(source.registryAccess()));
        var mainId=main.getUniqueId();var childId=child.getUniqueId();var standId=stand.getUUID();var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=718;
        SubLevelContainer.getContainer(source).addForceLoadTicket(main,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,net.minecraft.util.Unit.INSTANCE);
        helper.onEachTick(()->{
            if(player.level()==source&&!StormRiftTransfers.frozen(player.getUUID())){hold(source,main,new Vec3(point.x,altitude,point.z));hold(source,child,new Vec3(point.x+5,altitude,point.z));((EntityMovementExtension)player).sable$setTrackingSubLevel(main);player.setPos(point.x,altitude+2,point.z);}
            if(player.level()==source&&!StormRiftTransfers.retained(player.getUUID()))StormRiftTransfers.consider(source,storm,point.x,point.z,target);
            if(player.level()==target)RiftTransportGameTests.acknowledge(player);
        });
        helper.succeedWhen(()->{
            helper.assertTrue(player.level()==target,"Sable group not transported; "+StormRiftTransfers.report());var container=SubLevelContainer.getContainer(target);var moved=(ServerSubLevel)container.getSubLevel(mainId);var other=(ServerSubLevel)container.getSubLevel(childId);helper.assertTrue(moved!=null&&other!=null,"Connected structure missing");
            var na=moved.getPlot().getCenterBlock().atY(64);var nb=other.getPlot().getCenterBlock().atY(64);
            helper.assertTrue(container.getAllTickets().containsKey(mainId)&&!SubLevelContainer.getContainer(source).getAllTickets().containsKey(mainId),"Native force-load ticket was not migrated");
            helper.assertTrue(target.getBlockEntity(na) instanceof ChestBlockEntity,"Target plot/chest coordinates not rebased");helper.assertTrue(((ChestBlockEntity)target.getBlockEntity(na)).getItem(0).getCount()==23&&((ChestBlockEntity)target.getBlockEntity(nb)).getItem(0).getCount()==31,"Structure inventory changed");
            CompoundTag springTag=target.getBlockEntity(na.offset(2,0,0)).saveWithFullMetadata(target.registryAccess());helper.assertTrue(springTag.getUUID("GoalSubLevel").equals(childId)&&BlockPos.of(springTag.getLong("Goal")).equals(nb.offset(2,0,0)),"Joint reference not rebased to the child's own plot");
            var entity=target.getEntity(standId);helper.assertTrue(entity!=null&&dev.ryanhcode.sable.Sable.HELPER.getContaining(entity)==moved,"Plot-contained entity lost or left in the old slot");helper.assertTrue(SubLevelContainer.getContainer(source).getSubLevel(mainId)==null&&SubLevelContainer.getContainer(source).getSubLevel(childId)==null,"Source structure duplicated");helper.assertTrue(!StormRiftTransfers.frozen(player.getUUID()),"Sable native storage/ready barrier pending");
        });
    }
    static ServerSubLevel make(ServerLevel world,Vec3 point){var container=SubLevelContainer.getContainer(world);var pose=new Pose3d();pose.position().set(point.x,point.y,point.z);var sub=(ServerSubLevel)container.allocateNewSubLevel(pose);BlockPos center=sub.getPlot().getCenterBlock().atY(64);sub.getPlot().newEmptyChunk(new ChunkPos(center));sub.logicalPose().rotationPoint().set(center.getX()+.5,64.5,center.getZ()+.5);sub.logicalPose().orientation().rotationY(.2);return sub;}
    static void hold(ServerLevel world,ServerSubLevel sub,Vec3 point){if(sub.isRemoved())return;var pipeline=SubLevelContainer.getContainer(world).physicsSystem().getPipeline();pipeline.resetVelocity(sub);pipeline.teleport(sub,new Vector3d(point.x,point.y,point.z),sub.logicalPose().orientation());}
    static void link(ServerLevel world,BlockPos own,BlockPos other,java.util.UUID partner,boolean controller){var be=world.getBlockEntity(own);CompoundTag tag=be.saveWithFullMetadata(world.registryAccess());tag.putLong("Goal",other.asLong());tag.putUUID("GoalSubLevel",partner);tag.putBoolean("Controller",controller);tag.putDouble("DesiredLength",5);be.loadWithComponents(tag,world.registryAccess());}
}
