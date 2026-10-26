package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableStore;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Native Rapier/Simulated closure with two plots and offline companions across process death. */
final class RiftSableCrashFixture {
    static void run(GameTestHelper helper,Path manifest,String phase)throws Exception {
        if("recover".equals(System.getProperty("cmi.test.riftFaultMode"))){recover(helper,NbtIo.readCompressed(manifest,NbtAccounter.unlimitedHeap()));return;}
        if(Files.exists(manifest))throw new IllegalStateException("Choose a fresh isolated Sable crash directory");
        var source=helper.getLevel();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);var first=RiftTransportGameTests.player(source);var second=RiftTransportGameTests.player(source);
        Vec3 p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3)));double altitude=source.getMaxBuildHeight()+110;
        var main=RiftSableFixture.make(source,new Vec3(p.x,altitude,p.z));var child=RiftSableFixture.make(source,new Vec3(p.x+5,altitude,p.z));var a=main.getPlot().getCenterBlock().atY(64);var b=child.getPlot().getCenterBlock().atY(64);
        Block spring=BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("simulated","spring"));
        for(BlockPos pos:List.of(a,b)){source.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);source.setBlock(pos.offset(1,0,0),Blocks.OAK_PLANKS.defaultBlockState(),3);source.setBlock(pos.offset(2,0,0),spring.defaultBlockState(),3);}
        ((ChestBlockEntity)source.getBlockEntity(a)).setItem(0,new ItemStack(Items.DIAMOND,23));((ChestBlockEntity)source.getBlockEntity(b)).setItem(0,new ItemStack(Items.EMERALD,31));
        RiftSableFixture.link(source,a.offset(2,0,0),b.offset(2,0,0),child.getUniqueId(),true);RiftSableFixture.link(source,b.offset(2,0,0),a.offset(2,0,0),main.getUniqueId(),false);
        for(var sub:List.of(main,child)){var center=sub.getPlot().getCenterBlock().atY(64);sub.getPlot().onBlockChange(center,source.getBlockState(center));sub.getPlot().onBlockChange(center.offset(2,0,0),source.getBlockState(center.offset(2,0,0)));sub.getPlot().updateBoundingBox();sub.buildMassTracker();sub.updateMergedMassData(1);SubLevelContainer.getContainer(source).physicsSystem().getPipeline().onStatsChanged(sub);}
        var stand=EntityType.ARMOR_STAND.create(source);stand.setNoGravity(true);stand.moveTo(a.getX()+.5,65,a.getZ()+.5,38,0);source.addFreshEntity(stand);first.startRiding(stand,true);second.startRiding(first,true);
        for(ServerPlayer player:List.of(first,second)){player.teleportTo(p.x,altitude+2,p.z);((EntityMovementExtension)player).sable$setTrackingSubLevel(main);((EntityMovementExtension)player).sable$setLastTrackingSubLevelID(main.getUniqueId());}for(Entity e:List.of(stand,first,second))RiftMountPersistence.mark(e);
        if(phase.equals("PARTIAL")){java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> delay=event->{if(event.getLevel()==target&&event.getEntity().getUUID().equals(stand.getUUID())){long end=System.nanoTime()+1_200_000;while(System.nanoTime()<end)Thread.onSpinWait();}};net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(delay);}
        var ids=Set.of(main.getUniqueId(),child.getUniqueId());var nativeStore=new RiftSableStore.Job(List.of(source,target),ids,source,List.of(SubLevelSerializer.toData(main,new ArrayList<>(ids)).fullTag(),SubLevelSerializer.toData(child,new ArrayList<>(ids)).fullTag()),RiftTransferCoordinator.current().journal);
        var baseline=new RiftNativeCheckpoint(RiftTransferCoordinator.current().journal,Set.of(stand.getUUID()));baseline.include(source,List.of(stand.chunkPosition()));baseline.player(first);baseline.player(second);
        var saved=new CompoundTag();saved.putString("Phase",phase);saved.putUUID("Main",main.getUniqueId());saved.putUUID("Child",child.getUniqueId());saved.putUUID("Stand",stand.getUUID());saved.putUUID("First",first.getUUID());saved.putUUID("Second",second.getUUID());boolean[] initialized={false};var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=758;
        helper.onEachTick(()->{
            if(first.level()==source&&!StormRiftTransfers.frozen(first.getUUID())){RiftSableFixture.hold(source,main,new Vec3(p.x,altitude,p.z));RiftSableFixture.hold(source,child,new Vec3(p.x+5,altitude,p.z));for(ServerPlayer player:List.of(first,second)){((EntityMovementExtension)player).sable$setTrackingSubLevel(main);player.setPos(p.x,altitude+2,p.z);}}
            if(!initialized[0]){if(!nativeStore.ready()||!baseline.step(new RiftWorkBudget(System.nanoTime()+1_000_000,2)))return;nativeStore.finish();try{NbtIo.writeCompressed(saved,manifest);}catch(Exception error){throw new AssertionError(error);}initialized[0]=true;}
            if(first.level()==source&&!StormRiftTransfers.retained(first.getUUID()))StormRiftTransfers.consider(source,storm,p.x,p.z,target);
            for(var op:RiftCrashGameTests.operations())if(op.players.contains(first.getUUID())){boolean stop=switch(phase){case "PREPARED"->op.machine.stage()==RiftTransaction.Stage.COMMITTING&&op.commitCursor==0;case "PARTIAL"->op.machine.stage()==RiftTransaction.Stage.COMMITTING&&op.commitCursor>0&&op.commitCursor<op.group.members().size();case "COMMITTED"->op.machine.stage()==RiftTransaction.Stage.PUBLISHING;case "CHECKPOINTED"->op.published;default->throw new IllegalArgumentException("Sable fault phase "+phase);};if(stop){System.out.println("CMI_RIFT_EXPECTED_PROCESS_DEATH "+phase);System.out.flush();Runtime.getRuntime().halt(74);}}
        });
    }
    static void recover(GameTestHelper helper,CompoundTag saved){
        var source=helper.getLevel();var server=source.getServer();var target=server.getLevel(AllvrDimensions.ALLAY_LEVEL);boolean moved=Set.of("COMMITTED","CHECKPOINTED").contains(saved.getString("Phase"));var canonical=moved?target:source;ServerPlayer[] players=new ServerPlayer[2];
        helper.onEachTick(()->{if(players[0]==null&&StormRiftTransfers.report().contains("rift-transfer=active")){players[0]=RiftTransportGameTests.player(source,saved.getUUID("First"));players[1]=RiftTransportGameTests.player(source,saved.getUUID("Second"));}});
        helper.succeedWhen(()->{helper.assertTrue(players[0]!=null&&players[1]!=null,"Recovery pending: "+StormRiftTransfers.report());helper.assertTrue(players[0].level()==canonical&&players[1].level()==canonical,"Offline structure companions recovered in the wrong world");var container=SubLevelContainer.getContainer(canonical);var main=(ServerSubLevel)container.getSubLevel(saved.getUUID("Main"));var child=(ServerSubLevel)container.getSubLevel(saved.getUUID("Child"));helper.assertTrue(main!=null&&child!=null,"Recovered closure missing");var a=main.getPlot().getCenterBlock().atY(64);var b=child.getPlot().getCenterBlock().atY(64);helper.assertTrue(canonical.getBlockEntity(a) instanceof ChestBlockEntity&&canonical.getBlockEntity(b) instanceof ChestBlockEntity,"Recovered plot layout differs");helper.assertTrue(((ChestBlockEntity)canonical.getBlockEntity(a)).getItem(0).getCount()==23&&((ChestBlockEntity)canonical.getBlockEntity(b)).getItem(0).getCount()==31,"Recovered structure inventory changed");var joint=canonical.getBlockEntity(a.offset(2,0,0)).saveWithFullMetadata(canonical.registryAccess());helper.assertTrue(joint.getUUID("GoalSubLevel").equals(child.getUniqueId())&&BlockPos.of(joint.getLong("Goal")).equals(b.offset(2,0,0)),"Recovered joint/plot remapping differs");Entity stand=RiftKnownEntities.find(canonical,saved.getUUID("Stand"),List.of(new ChunkPos(a)));helper.assertTrue(stand!=null&&players[0].getVehicle()==stand&&players[1].getVehicle()==players[0],"Recovered nested structure seats missing");helper.assertTrue(!StormRiftTransfers.frozen(stand.getUUID()),"Structure recovery lock leaked");for(ServerLevel world:server.getAllLevels())if(world!=canonical)helper.assertTrue(SubLevelContainer.getContainer(world).getSubLevel(main.getUniqueId())==null&&SubLevelContainer.getContainer(world).getSubLevel(child.getUniqueId())==null,"Recovered duplicate structures remain");});
    }
}
