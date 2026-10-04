package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.*;
import com.simibubi.create.content.kinetics.belt.transport.*;
import com.simibubi.create.content.logistics.box.*;
import com.simibubi.create.content.logistics.chute.ChuteBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PackageOutputGameTests {
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void voidLifecycleDoesNotNeedAnEnvironmentContact(GameTestHelper helper){
        var level=helper.getLevel();var item=box("CMI void fixture");var p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(2,4,2)));
        var entity=PackageEntity.fromItemStack(level,p,item.copy());level.addFreshEntity(entity);
        var entries=PackageAuthorityManager.queryLight(level,new AABB(p,p).inflate(2));
        helper.assertTrue(entries.size()==1,"Package capture missing");var entry=entries.getFirst();
        var pose=entry.state().pose();PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(pose.x(),level.getMinBuildHeight()-65,pose.z(),0,-10,0,0),0));
        var below=PackageEntity.fromItemStack(level,new Vec3(p.x+2,level.getMinBuildHeight()-65,p.z),box("CMI initial void fixture"));level.addFreshEntity(below);
        var initialIdentity=PackageAuthorityManager.identity(below,level);
        helper.assertTrue(PackageAuthorityManager.light(level,initialIdentity)!=null,"Initial void package was not captured");
        helper.runAfterDelay(2,()->{
            helper.assertTrue(PackageLightStore.get(level).byIdentity(entry.identity)==null,"Void record survived accepted pose");
            helper.assertTrue(PackageLightStore.get(level).byIdentity(initialIdentity)==null,"Initial void record survived without GPU authority");
            helper.assertTrue(!PackageAuthorityManager.consumeLight(level,entry),"Void inventory consumed twice");helper.succeed();
        });
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void compactEnvironmentReplaysEveryServerDamageTick(GameTestHelper helper){
        var level=helper.getLevel();var p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(2,4,2)));
        var item=box("CMI compact fire fixture");level.addFreshEntity(PackageEntity.fromItemStack(level,p,item.copy()));
        var entry=emitted(helper,BlockPos.containing(p),item);entry.fireTicks=120;
        var region=PackageRegion.at(entry.state().pose());var c=entry.bounds().getCenter();
        var sample=new PackageEnvironmentEvent.Sample(40,0,0,0,0,(float)(c.x-region.originX()),(float)(c.y-region.originY()),(float)(c.z-region.originZ()),80,4.7f,40);
        helper.assertTrue(!PackageLightGameplay.environmentStep(level,entry,region,sample),"Compact fire unexpectedly consumed inventory");
        helper.assertTrue(entry.fireTicks==80&&Math.abs(entry.health-4.7f)<1e-5&&entry.environmentStep==40,"Compact duration lost server damage ticks");
        helper.assertTrue(ItemStack.matches(item,entry.box(level)),"Compact fire changed surviving inventory");
        PackageAuthorityManager.consumeLight(level,entry);helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=200)
    public static void realTwoHundredTickRateAllowsDelayedBeltPackageAdmission(GameTestHelper helper){
        var manager=helper.getLevel().getServer().tickRateManager();float previous=manager.tickrate();manager.setTickRate(200);
        var source=helper.absolutePos(new BlockPos(2,4,2));
        helper.getLevel().setBlockAndUpdate(source,AllBlocks.BELT.getDefaultState().setValue(BeltBlock.SLOPE,BeltSlope.HORIZONTAL)
                .setValue(BeltBlock.HORIZONTAL_FACING,Direction.EAST).setValue(BeltBlock.PART,BeltPart.END));
        var belt=(BeltBlockEntity)helper.getLevel().getBlockEntity(source);belt.beltLength=1;belt.setSpeed(32);
        var item=box("CMI 200 tick delayed belt fixture");var stack=new TransportedItemStack(item.copy());stack.beltPosition=1;new BeltInventory(belt).eject(stack);
        var entry=emitted(helper,source,item);var owner=new java.util.UUID(7,9);long start=helper.getLevel().getGameTime();
        var core=new PackageAuthorityRegion(PackageRegion.at(entry.state().pose()),owner,70,1,start,()->manager.tickrate());
        var target=new PackageAuthorityRegion.Target(){
            public PackageLease.Identity identity(){return entry.identity;}
            public PackageAuthorityRegion.Snapshot snapshot(){return entry.state();}
            public boolean eligible(){return true;}
            public void apply(PackageAuthorityRegion.Snapshot state){throw new AssertionError("Admission must not mutate native output inventory/pose");}
        };
        var offer=core.offer(target,start);var baseline=core.prepared(owner,70,offer.index(),entry.identity,offer.leaseEpoch(),offer.revision(),start);
        helper.runAfterDelay(80,()->{
            try{
                long tick=helper.getLevel().getGameTime();
                helper.assertTrue(manager.tickrate()==200,"GameTest did not use the actual 200 tick/s server rate");
                helper.assertTrue(tick-start>=80,"Delayed admission did not span 80 world ticks");core.tick(tick);
                helper.assertTrue(core.finalReady(owner,70,baseline.index(),entry.identity,baseline.leaseEpoch(),baseline.revision(),tick),"200 tick/s expired a valid 0.4-second final upload");
                helper.assertTrue(ItemStack.matches(item,entry.box(helper.getLevel())),"Tick-rate admission changed inventory");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(PackageEntity.class,new AABB(source).inflate(3)).isEmpty(),"Tick-rate recovery restored native CPU motion");
                PackageAuthorityManager.consumeLight(helper.getLevel(),entry);helper.succeed();
            }finally{manager.setTickRate(previous);}
        });
    }
    @GameTest(template="package_output_test",timeoutTicks=60)
    public static void continuousBeltOutputKeepsStaticCollisionAndDistinctInventories(GameTestHelper helper){
        var source=helper.absolutePos(new BlockPos(2,4,2));
        var state=AllBlocks.BELT.getDefaultState().setValue(BeltBlock.SLOPE,BeltSlope.HORIZONTAL)
                .setValue(BeltBlock.HORIZONTAL_FACING,Direction.EAST).setValue(BeltBlock.PART,BeltPart.END);
        helper.getLevel().setBlockAndUpdate(source,state);
        var belt=(BeltBlockEntity)helper.getLevel().getBlockEntity(source);belt.beltLength=1;
        var worldSource=new com.iridium126.createmanaindustry.client.particles.packages.PackageWorldCollisionSource(helper.getLevel());
        var section=new com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionCache.Section(source.getX()>>4,source.getY()>>4,source.getZ()>>4);
        int cell=(source.getX()&15)|((source.getZ()&15)<<4)|((source.getY()&15)<<8);
        var before=worldSource.capture(section,cell);
        helper.assertTrue(!com.iridium126.createmanaindustry.client.particles.packages.PackageWorldCollisionSource.blockEntityAffectsCollision(state),"Belt inventory packets still revoke static collision");
        helper.assertTrue(com.iridium126.createmanaindustry.client.particles.packages.PackageWorldCollisionSource.blockEntityAffectsCollision(AllBlocks.CHUTE.getDefaultState()),"Other block entities lost conservative collision invalidation");
        var entries=new java.util.ArrayList<PackageLightStore.Entry>();var identities=new java.util.HashSet<PackageLease.Identity>();
        for(int i=0;i<32;i++){
            belt.setSpeed(32+i);var item=box("CMI continuous belt fixture "+i);
            var stack=new TransportedItemStack(item.copy());stack.beltPosition=1;new BeltInventory(belt).eject(stack);
            var entry=emitted(helper,source,item);entries.add(entry);
            helper.assertTrue(identities.add(entry.identity),"Continuous output reused a live inventory identity");
            helper.assertTrue(before.equals(worldSource.capture(section,cell)),"Belt item/speed updates changed GPU collision input");
            helper.assertTrue(!entry.bounds().intersects(new AABB(source)),"Continuous output still overlaps the source");
            helper.assertTrue(entry.state().pose().vx()!=0||entry.state().pose().vz()!=0,"Continuous output lost its impulse");
        }
        for(var entry:entries)PackageAuthorityManager.consumeLight(helper.getLevel(),entry);
        helper.succeed();
    }
    private static ItemStack box(String address) {
        var box=PackageItem.containing(List.of(new ItemStack(Items.DIAMOND,17),new ItemStack(Items.IRON_INGOT,23)));
        PackageItem.addAddress(box,address);return box;
    }
    private static PackageLightStore.Entry emitted(GameTestHelper helper,BlockPos source,ItemStack expected) {
        var entries=PackageAuthorityManager.queryLight(helper.getLevel(),new AABB(source).inflate(3)).stream()
                .filter(e->ItemStack.matches(e.box(helper.getLevel()),expected)).toList();
        helper.assertTrue(entries.size()==1,"Machine output must create exactly one durable inventory record, found "+entries.size());
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(PackageEntity.class,new AABB(source).inflate(3)).isEmpty(),"Machine output entered native package motion");
        return entries.getFirst();
    }
    private static void verifyExitEvent(GameTestHelper helper,BlockPos source,PackageLightStore.Entry entry) {
        var region=PackageRegion.at(entry.state().pose());
        helper.assertTrue(region.x()!=0||region.y()!=0||region.z()!=0,"Exit validation must exercise a nonzero authority origin");
        int ox=Math.floorDiv(source.getX(),16)*16,oy=Math.floorDiv(source.getY(),16)*16,oz=Math.floorDiv(source.getZ(),16)*16;
        var raw=java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        raw.putLong(0,entry.identity.id()).putLong(8,entry.identity.generation()).putLong(16,1).putInt(24,1).putInt(40,1).putLong(56,1);
        var centre=entry.bounds().getCenter();
        raw.putInt(64,source.getX()-ox).putInt(68,source.getY()-oy).putInt(72,source.getZ()-oz).putInt(76,8).putLong(80,1).putFloat(92,5);
        raw.putFloat(96,(float)(centre.x-ox)).putFloat(100,(float)(centre.y-oy)).putFloat(104,(float)(centre.z-oz));
        var event=PackageEnvironmentEvent.decode(PackageEnvironmentEvent.regionPayload(raw,ox,oy,oz,region));var sample=event.samples().getFirst();
        helper.assertTrue(sample.block(region).equals(source),"Exit event added the authority origin twice");
        helper.assertTrue(PackageLightGameplay.environmentValid(helper.getLevel(),entry,region,sample),"Actual Create source rejected its outgoing contact event");
        var inventory=entry.box(helper.getLevel()).copy();
        helper.assertTrue(!PackageLightGameplay.environmentStep(helper.getLevel(),entry,region,sample),"Source recaptured its outgoing package");
        helper.assertTrue(ItemStack.matches(inventory,entry.box(helper.getLevel())),"Exit event modified package inventory");
        // Contact coordinates describe the actual sweep hit, not a later corrected end pose.
        var fast=new PackageEnvironmentEvent.Sample(2,8,sample.x(),sample.y(),sample.z(),sample.px()+4,sample.py(),sample.pz(),0,5);
        helper.assertTrue(!PackageLightGameplay.environmentValid(helper.getLevel(),entry,region,fast),"A remote end pose was accepted as an actual machine contact");
    }
    @GameTest(template="package_output_test",timeoutTicks=60)
    public static void ordinaryVerticalChuteCapturesSeparatedInventoryAndDownwardImpulse(GameTestHelper helper) {
        var source=helper.absolutePos(new BlockPos(2,4,2));
        helper.getLevel().setBlockAndUpdate(source,AllBlocks.CHUTE.getDefaultState());
        var chute=(ChuteBlockEntity)helper.getLevel().getBlockEntity(source);
        var item=box("CMI chute output fixture");chute.setItem(item.copy(),.01f);chute.tick();
        var entry=emitted(helper,source,item);
        helper.assertTrue(entry.bounds().maxY<source.getY(),"Full package collider remains inside chute outlet");
        helper.assertTrue(entry.state().pose().vy()==-7.5f,"Chute output lost Create's downward impulse");
        helper.assertTrue(chute.getItem().isEmpty(),"Chute retained inventory after emitting the record");
        verifyExitEvent(helper,source,entry);
        PackageAuthorityManager.consumeLight(helper.getLevel(),entry);helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=60)
    public static void horizontalBeltCapturesNativeImpulseBeforeLightweightAdmission(GameTestHelper helper) {
        var source=helper.absolutePos(new BlockPos(2,4,2));
        helper.getLevel().setBlockAndUpdate(source,AllBlocks.BELT.getDefaultState().setValue(BeltBlock.SLOPE,BeltSlope.HORIZONTAL)
                .setValue(BeltBlock.HORIZONTAL_FACING,Direction.EAST).setValue(BeltBlock.PART,BeltPart.END));
        var belt=(BeltBlockEntity)helper.getLevel().getBlockEntity(source);belt.beltLength=1;belt.setSpeed(32);
        var item=box("CMI belt output fixture");var stack=new TransportedItemStack(item.copy());stack.beltPosition=1;
        var nativeMotion=Vec3.atLowerCornerOf(belt.getBeltChainDirection()).scale(Math.max(Math.abs(belt.getBeltMovementSpeed()),.125f)).add(0,.125,0);
        new BeltInventory(belt).eject(stack);
        var entry=emitted(helper,source,item);var pose=entry.state().pose();
        helper.assertTrue(Math.abs(pose.vx()-nativeMotion.x*30)<1e-5&&Math.abs(pose.vy()-nativeMotion.y*30)<1e-5
                &&Math.abs(pose.vz()-nativeMotion.z*30)<1e-5,"Belt conversion lost native launch velocity");
        helper.assertTrue(Math.abs(pose.vx())+Math.abs(pose.vz())>0,"Belt output has no horizontal impulse");
        helper.assertTrue(!entry.bounds().intersects(new AABB(source)),"Belt output collider still overlaps its source interaction volume");
        verifyExitEvent(helper,source,entry);
        PackageAuthorityManager.consumeLight(helper.getLevel(),entry);helper.succeed();
    }
}
