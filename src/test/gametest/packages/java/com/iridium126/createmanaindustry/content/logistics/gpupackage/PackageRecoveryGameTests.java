package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.logistics.box.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.bus.api.EventPriority;

/** One isolated serial scenario: server config is shared across dimensions and tests. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PackageRecoveryGameTests {
    private static void pulse(ServerLevel level){level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);PackageAuthorityManager.onTick(new LevelTickEvent.Pre(()->true,level));}
    private static ItemStack box(String address){var box=PackageItem.containing(List.of(new ItemStack(Items.DIAMOND,17),new ItemStack(Items.IRON_INGOT,23)));PackageItem.addAddress(box,address);return box;}
    private static PackageLightStore.Entry capture(ServerLevel level,Vec3 p,String address){
        var nativeBox=PackageEntity.fromItemStack(level,p,box(address));nativeBox.getPersistentData().putString("CMIFixtureCustom","preserve");level.addFreshEntity(nativeBox);
        var entry=PackageLightStore.get(level).byUuid(nativeBox.getUUID());if(entry==null)throw new AssertionError("Light capture missing");return entry;
    }
    @GameTest(template="package_output_test",timeoutTicks=200)
    public static void disabledRecoveryIsLosslessBudgetedAndReversible(GameTestHelper helper){
        var level=helper.getLevel();boolean previous=ServerConfig.packageGpuAuthority;
        var spawned=new HashSet<UUID>();var store=PackageLightStore.get(level);
        try{
            ServerConfig.packageGpuAuthority=true;pulse(level);
            var p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(2,6,2)));
            var entry=capture(level,p,"CMI restore contents");spawned.add(entry.uuid);
            entry.health=2.75f;entry.fireTicks=87;entry.portalCooldown=123;entry.insertionDelay=9;entry.tossedBy=UUID.randomUUID();
            var remainder=box("CMI authoritative remainder");entry.replaceBox(level,remainder);
            var pose=new PackageLease.Pose(p.x+1.5,p.y+2,p.z-.25,1024,-32,8,-90);
            PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(pose,PackageAuthorityRegion.GROUNDED));
            helper.assertTrue(PackageLightRestoration.restore(level,entry)==null,"Enabled GPU system restored an entity");
            pulse(level);helper.assertTrue(store.byIdentity(entry.identity)==entry&&level.getEntity(entry.uuid)==null,"Missing GPU peer must pause the record");
            var many=new ArrayList<PackageLightStore.Entry>();many.add(entry);
            for(int i=0;i<65;i++){var next=capture(level,p.add(i*.01,0,0),"CMI budget "+i);many.add(next);spawned.add(next.uuid);}
            ServerConfig.packageGpuAuthority=false;pulse(level);
            long remaining=many.stream().filter(e->store.byIdentity(e.identity)!=null).count();
            helper.assertTrue(remaining>=2,"More than 64 records migrated in one level tick");
            for(int i=0;i<10&&many.stream().anyMatch(e->store.byIdentity(e.identity)!=null);i++)pulse(level);
            helper.assertTrue(many.stream().allMatch(e->store.byIdentity(e.identity)==null),"Loaded lightweight records were not fully restored");
            var restored=(PackageEntity)level.getEntity(entry.uuid);
            helper.assertTrue(restored!=null,"Restored entity missing");
            helper.assertTrue(restored.position().equals(new Vec3(pose.x(),pose.y(),pose.z())),"Stale captured position restored");
            helper.assertTrue(restored.getDeltaMovement().equals(new Vec3(51.2,-1.6,.4)),"Confirmed velocity was clamped or used wrong units");
            helper.assertTrue(restored.getYRot()==-90&&restored.onGround(),"Yaw or grounded flag lost");
            helper.assertTrue(restored.getHealth()==2.75f&&restored.getRemainingFireTicks()==87&&restored.getPortalCooldown()==123,"Environment state lost");
            helper.assertTrue(restored.insertionDelay==9&&entry.tossedBy.equals(((PackageInitialEntityAccess)restored).cmi$tossedBy()),"Insertion/thrower state lost");
            helper.assertTrue(ItemStack.matches(remainder,restored.box),"Restored original box instead of actual remainder");
            var saved=new CompoundTag();restored.saveWithoutId(saved);
            var reloaded=new PackageEntity(level,0,0,0);reloaded.load(saved);
            helper.assertTrue(reloaded.insertionDelay==9&&entry.tossedBy.equals(((PackageInitialEntityAccess)reloaded).cmi$tossedBy()),"Native save/reload lost extended state");
            helper.assertTrue("preserve".equals(reloaded.getPersistentData().getString("CMIFixtureCustom")),"Integration entity NBT lost");
            pulse(level);helper.assertTrue(level.getEntity(entry.uuid)==restored,"Repeated disabled tick duplicated entity");
            var ordinary=PackageEntity.fromItemStack(level,p.add(0,0,2),box("CMI disabled native"));
            helper.assertTrue(level.addFreshEntity(ordinary)&&store.byUuid(ordinary.getUUID())==null,"Disabled configuration recaptured an ordinary native package");spawned.add(ordinary.getUUID());

            ServerConfig.packageGpuAuthority=true;
            for(int i=0;i<10;i++)pulse(level);
            var recaptured=store.byUuid(entry.uuid);
            helper.assertTrue(recaptured!=null&&recaptured.identity.generation()>entry.identity.generation()&&level.getEntity(entry.uuid)==null,"Re-enable did not establish a new lightweight lifecycle");
            helper.assertTrue(ItemStack.matches(remainder,recaptured.box(level))&&recaptured.tossedBy.equals(entry.tossedBy),"Re-enable changed restored inventory/thrower");
            helper.assertTrue(store.byUuid(ordinary.getUUID())!=null,"Existing native package was not migrated on enable");
            var canceled=capture(level,p.add(0,0,4),"CMI canceled admission");spawned.add(canceled.uuid);
            Consumer<EntityJoinLevelEvent> reject=event->{if(!ServerConfig.packageGpuAuthority&&event.getEntity().getUUID().equals(canceled.uuid))event.setCanceled(true);};
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,reject);
            ServerConfig.packageGpuAuthority=false;
            try{for(int i=0;i<6;i++)pulse(level);helper.assertTrue(store.byIdentity(canceled.identity)==canceled&&level.getEntity(canceled.uuid)==null,"Failed admission consumed the backing record");}
            finally{NeoForge.EVENT_BUS.unregister(reject);}
            pulse(level);helper.assertTrue(store.byIdentity(canceled.identity)==null&&level.getEntity(canceled.uuid)!=null,"Canceled admission was not retryable");

            ServerConfig.packageGpuAuthority=true;pulse(level);
            var conflicting=capture(level,p.add(0,0,5),"CMI UUID conflict");spawned.add(conflicting.uuid);
            var cow=EntityType.COW.create(level);cow.setUUID(conflicting.uuid);cow.setPos(p);level.addFreshEntity(cow);
            ServerConfig.packageGpuAuthority=false;pulse(level);
            helper.assertTrue(store.byIdentity(conflicting.identity)==conflicting&&level.getEntity(conflicting.uuid)==cow,"UUID collision overwrote entity or consumed package");
            cow.discard();pulse(level);helper.assertTrue(store.byIdentity(conflicting.identity)==null&&level.getEntity(conflicting.uuid) instanceof PackageEntity,"UUID collision did not recover");

            var coldData=new CompoundTag();coldData.put("Box",box("CMI startup restore").save(level.registryAccess()));
            var cold=new PackageLightStore.Entry(new PackageLease.Identity(PackageIdentityData.get(level).identity(),1),UUID.randomUUID(),entry.model,entry.width,entry.height,-1,coldData,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(p.x+8,p.y,p.z,1,0,0,0),0));
            var coldStore=new PackageLightStore();coldStore.put(cold);
            var loaded=PackageLightStore.load(coldStore.save(new CompoundTag(),level.registryAccess()),level.registryAccess()).byIdentity(cold.identity);
            store.put(loaded);spawned.add(loaded.uuid);
            // Recreate the world adapter with persisted records and authority already disabled.
            PackageAuthorityManager.onUnload(new LevelEvent.Unload(level));pulse(level);
            helper.assertTrue(store.byIdentity(loaded.identity)==null&&level.getEntity(loaded.uuid) instanceof PackageEntity,"Disabled startup did not recover a persisted record");

            var unloadedPose=new PackageLease.Pose(1_000_008,p.y,1_000_008,0,0,0,0);
            helper.assertTrue(!level.hasChunkAt(BlockPos.containing(unloadedPose.x(),unloadedPose.y(),unloadedPose.z())),"Far fixture chunk is already loaded");
            var data=new CompoundTag();data.put("Box",box("CMI unloaded record").save(level.registryAccess()));
            var unloaded=new PackageLightStore.Entry(new PackageLease.Identity(PackageIdentityData.get(level).identity(),1),UUID.randomUUID(),entry.model,entry.width,entry.height,-1,data,new PackageAuthorityRegion.Snapshot(unloadedPose,0));
            store.put(unloaded);spawned.add(unloaded.uuid);pulse(level);
            helper.assertTrue(store.byIdentity(unloaded.identity)==unloaded&&level.getEntity(unloaded.uuid)==null&&!level.hasChunkAt(BlockPos.containing(unloaded.position())),"Recovery force-loaded an untouched chunk");
            level.getChunk((int)unloadedPose.x()>>4,(int)unloadedPose.z()>>4);
            helper.runAfterDelay(6,()->{
                try{helper.assertTrue(store.byIdentity(unloaded.identity)==null&&level.getEntity(unloaded.uuid) instanceof PackageEntity,"Natural chunk load did not restore the deferred record");helper.succeed();}
                finally{cleanup(level,spawned);ServerConfig.packageGpuAuthority=previous;}
            });
        }catch(Throwable failure){cleanup(level,spawned);ServerConfig.packageGpuAuthority=previous;throw failure;}
    }
    private static void cleanup(ServerLevel level,Set<UUID> ids){for(var uuid:ids){var entity=level.getEntity(uuid);if(entity!=null)entity.discard();var entry=PackageLightStore.get(level).byUuid(uuid);if(entry!=null)PackageAuthorityManager.consumeLight(level,entry);}}
}
