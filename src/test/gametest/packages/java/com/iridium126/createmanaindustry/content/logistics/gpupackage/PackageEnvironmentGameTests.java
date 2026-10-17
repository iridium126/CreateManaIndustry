package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.logistics.box.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PackageEnvironmentGameTests {
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void everyRegisteredMinecraftAndCreateBlockHasAuditedDeclarations(GameTestHelper helper)throws Exception{
        var known=new java.util.HashSet<String>();
        try(var stream=PackageEnvironmentGameTests.class.getResourceAsStream("/package-environment-native-inventory.json")){
            if(stream==null)throw new IllegalStateException("Missing native audit fixture");
            var root=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for(var row:root.getAsJsonArray("sources"))known.add(row.getAsJsonObject().get("source").getAsString().replace('/','.').replace(".java",""));
        }
        var methods=java.util.Set.of("entityInside","stepOn","fallOn","updateEntityAfterFallOn","getCollisionShape","getSpeedFactor","getJumpFactor",
                "isLadder","makesOpenTrapdoorAboveClimbable","isScaffolding","isBurning","getFriction","collisionExtendsVertically",
                "addLandingEffects","addRunningEffects","getSoundType","canEntityDestroy","onCaughtFire","isEmpty");
        var missing=new java.util.TreeSet<String>();int blocks=0,states=0;
        for(var block:net.minecraft.core.registries.BuiltInRegistries.BLOCK){
            var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if(!id.getNamespace().equals("minecraft")&&!id.getNamespace().equals("create"))continue;
            blocks++;for(var state:block.getStateDefinition().getPossibleStates()){
                PackageBlockMaterial.encode(state);states++;
                if(state.isEmpty()&&!state.is(Blocks.AIR)&&!state.is(Blocks.CAVE_AIR)&&!state.is(Blocks.VOID_AIR))
                    missing.add(id+" -> custom isEmpty invalidates hasOnlyAir fast-path safety");
            }
            for(Class<?> type=block.getClass();type!=null;type=type.getSuperclass())for(var method:type.getDeclaredMethods())
                if(methods.contains(method.getName())&&(!method.getName().equals("isEmpty")||java.util.Arrays.equals(method.getParameterTypes(),new Class<?>[]{BlockState.class}))
                        &&!known.contains(type.getName()))missing.add(id+" -> "+type.getName()+"#"+method.getName());
        }
        helper.assertTrue(missing.isEmpty(),"Unaudited environment entry: "+missing);
        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("Package native registry audit: {} blocks, {} states, no unknown declarations",blocks,states);
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void emptySectionFastPathKeepsCollisionlessEnvironment(GameTestHelper helper){
        for(var state:List.of(Blocks.COBWEB.defaultBlockState(),Blocks.POWDER_SNOW.defaultBlockState(),Blocks.FIRE.defaultBlockState(),
                Blocks.SCAFFOLDING.defaultBlockState(),Blocks.WATER.defaultBlockState(),com.simibubi.create.AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState())){
            var section=new net.minecraft.world.level.chunk.LevelChunkSection(helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME));
            helper.assertTrue(section.hasOnlyAir(),"Fresh section is not empty");
            section.setBlockState(0,0,0,state);
            helper.assertTrue(!section.hasOnlyAir(),"Native empty count erased collisionless environment "+state);
            section.recalcBlockCounts();helper.assertTrue(!section.hasOnlyAir(),"Recount erased collisionless environment "+state);
            // Native recalc counts a fluid twice; conservatively non-empty until a
            // recount after removal is valid and must not be mistaken for lost data.
            section.setBlockState(0,0,0,Blocks.AIR.defaultBlockState());section.recalcBlockCounts();
            helper.assertTrue(section.hasOnlyAir(),"Recount retained a dense air section");
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void openTrapdoorsHonorNeoForgeLadderExtensions(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,3,1));
        var source=new com.iridium126.createmanaindustry.client.packages.world.PackageWorldCollisionSource(level);
        var reference=new Reference(level,Vec3.atBottomCenterOf(at).add(0,.01,0),PackageItem.containing(List.of()));
        var section=new com.iridium126.createmanaindustry.client.packages.world.PackageCollisionCache.Section(at.getX()>>4,at.getY()>>4,at.getZ()>>4);
        int index=(at.getX()&15)|((at.getZ()&15)<<4)|((at.getY()&15)<<8),checked=0;
        for(var block:net.minecraft.core.registries.BuiltInRegistries.BLOCK){
            var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if(!(block instanceof LadderBlock)||!java.util.Set.of("minecraft","create").contains(id.getNamespace()))continue;
            for(var facing:net.minecraft.core.Direction.Plane.HORIZONTAL)for(boolean open:new boolean[]{false,true})for(boolean matching:new boolean[]{false,true}){
                var trapdoor=Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN,open).setValue(TrapDoorBlock.FACING,facing);
                level.setBlock(at.below(),block.defaultBlockState().setValue(LadderBlock.FACING,matching?facing:facing.getOpposite()),3);
                level.setBlock(at,trapdoor,3);
                boolean nativeClimbing=level.getBlockState(at).isLadder(level,at,reference);
                var cell=source.capture(section,index);
                helper.assertTrue(cell!=null&&((cell.flags()>>>16&255)==PackageBlockMaterial.CLIMBABLE)==nativeClimbing,
                        "Captured trapdoor climbing differs for "+id+" facing="+facing+" open="+open+" matching="+matching);
                checked++;
            }
        }
        helper.assertTrue(checked>=64,"Missing Create ladder fixtures: "+checked);reference.discard();helper.succeed();
    }
    private static final class Reference extends PackageEntity {
        Reference(ServerLevel level,Vec3 position,ItemStack box){super(level,position.x,position.y,position.z);setBox(box);}
        void inside(BlockState state,BlockPos pos){state.entityInside(level(),pos,this);onInsideBlock(state);}
        void portalTick(){handlePortal();}
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void climbingCollisionUsesNativePostMoveStage(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,3,2));
        var option=net.neoforged.neoforge.common.NeoForgeConfig.SERVER.fullBoundingBoxLadders;boolean previous=option.get();
        try{
            for(boolean fullBounds:new boolean[]{false,true})for(boolean aboveFeet:new boolean[]{false,true})for(boolean climbing:new boolean[]{false,true}){
                // NeoForge deliberately keeps world-restart values cached after set().
                // This isolated fixture models a restart between the two configurations.
                option.set(fullBounds);option.clearCache();
                for(int dy=-1;dy<=2;dy++){level.setBlock(at.above(dy),Blocks.AIR.defaultBlockState(),2);level.setBlock(at.above(dy).east(),Blocks.STONE.defaultBlockState(),2);}
                if(climbing)level.setBlock(aboveFeet?at.above():at,Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,net.minecraft.core.Direction.WEST),2);
                var p=new Vec3(at.getX()+.61,at.getY()+.75,at.getZ()+.5);
                var reference=new Reference(level,p,PackageStyles.getDefaultBox());reference.setDeltaMovement(.3,-.2,0);
                boolean initialClimbing=reference.onClimbable();
                boolean hookClimbing=net.neoforged.neoforge.common.CommonHooks.isLivingOnLadder(level.getBlockState(reference.blockPosition()),level,reference.blockPosition(),reference).isPresent();
                var movement=reference.handleRelativeFrictionAndCalculateMovement(Vec3.ZERO,.6f);
                boolean expected=climbing&&(!aboveFeet||fullBounds);
                helper.assertTrue(reference.horizontalCollision,"Native climbing fixture missed the wall");
                helper.assertTrue(expected?Math.abs(movement.y-.2)<1e-7:movement.y<0,"Native climbing phase differs fullBounds="+fullBounds+" option="+option.get()+" aboveFeet="+aboveFeet+" climbing="+climbing+" initial="+initialClimbing+" hook="+hookClimbing+" upper="+level.getBlockState(at.above())+" final="+reference.onClimbable()+" movement="+movement+" bounds="+reference.getBoundingBox());
                reference.discard();
            }
        }finally{option.set(previous);option.clearCache();}
        helper.succeed();
    }
    private static PackageLightStore.Entry record(GameTestHelper helper,Vec3 p,ItemStack box){
        var level=helper.getLevel();var entity=PackageEntity.fromItemStack(level,p,box.copy());level.addFreshEntity(entity);
        var result=PackageAuthorityManager.queryLight(level,new AABB(p,p).inflate(2)).stream().filter(e->e.uuid.equals(entity.getUUID())).findFirst();
        helper.assertTrue(result.isPresent(),"Lightweight reference fixture was not captured");return result.orElseThrow();
    }
    private static PackageEnvironmentEvent.Sample batch(PackageLightStore.Entry entry,PackageRegion region,BlockPos at,int extra){
        var center=entry.bounds().getCenter();var first=BlockPos.containing(entry.bounds().minX+1e-7,entry.bounds().minY+1e-7,entry.bounds().minZ+1e-7);
        int bit=(at.getX()-first.getX())*9+(at.getY()-first.getY())*3+at.getZ()-first.getZ();
        var p=entry.state().pose();return new PackageEnvironmentEvent.Sample(1,32,Float.floatToRawIntBits(p.vx()),Float.floatToRawIntBits(p.vy()),Float.floatToRawIntBits(p.vz()),
                (float)(center.x-region.originX()),(float)(center.y-region.originY()),(float)(center.z-region.originZ()),(1<<bit)|extra,0);
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void damageEligibilityAndFireThresholdsMatchNative(GameTestHelper helper){
        var level=helper.getLevel();var p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(2,4,2)));
        var damage=level.damageSources();
        for(boolean invulnerable:new boolean[]{false,true})for(boolean resistant:new boolean[]{false,true})
            for(int delay:new int[]{10,30})for(int fire:new int[]{-20,100})for(float health:new float[]{.6499f,.6501f,5})
                for(var source:List.of(damage.fellOutOfWorld(),damage.fall(),damage.inWall(),damage.drown(),damage.inFire(),damage.onFire(),damage.lava(),damage.freeze())){
                    var box=PackageItem.containing(List.of());if(resistant)box.set(net.minecraft.core.component.DataComponents.FIRE_RESISTANT,net.minecraft.util.Unit.INSTANCE);
                    var reference=new Reference(level,p,box);reference.setInvulnerable(invulnerable);reference.insertionDelay=delay;reference.setRemainingFireTicks(fire);reference.setHealth(health);
                    var entry=record(helper,p,box);entry.data.putBoolean("Invulnerable",invulnerable);entry.insertionDelay=delay;entry.fireTicks=fire;entry.health=health;
                    reference.hurt(source,7);PackageLightNativeEnvironment.hurt(level,entry,source);
                    boolean alive=PackageAuthorityManager.light(level,entry.identity)==entry;
                    helper.assertTrue(alive==reference.isAlive(),"Damage eligibility lifetime differs: "+source+" invul="+invulnerable+" resistant="+resistant+" delay="+delay+" fire="+fire+" health="+health);
                    if(alive){helper.assertTrue(Math.abs(entry.health-reference.getHealth())<1e-6&&entry.fireTicks==reference.getRemainingFireTicks(),"Damage fire/health result differs: "+source);PackageAuthorityManager.consumeLight(level,entry);}
                    reference.discard();
                }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void sailBounceAndEjectorNudgeRespectPackageBranches(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);var box=PackageItem.containing(List.of());
        var reference=new Reference(level,p,box);reference.setDeltaMovement(0,-1,0);
        var sail=com.simibubi.create.AllBlocks.SAIL.get();sail.updateEntityAfterFallOn(level,reference);
        helper.assertTrue(Math.abs(reference.getDeltaMovement().y-.26f)<1e-7,"Reference sail coefficient changed");
        helper.assertTrue((PackageBlockMaterial.encode(sail.defaultBlockState())>>>16&255)==PackageBlockMaterial.SAIL,"Canvas sail lost material classification");
        helper.assertTrue((PackageBlockMaterial.encode(com.simibubi.create.AllBlocks.SAIL_FRAME.getDefaultState())>>>16&255)!=PackageBlockMaterial.SAIL,"Sail frame incorrectly inherited canvas bounce");
        reference.discard();
        level.setBlock(at,com.simibubi.create.AllBlocks.WEIGHTED_EJECTOR.getDefaultState(),2);
        var ejector=(com.simibubi.create.content.logistics.depot.EjectorBlockEntity)level.getBlockEntity(at);
        var entry=record(helper,p,box);reference=new Reference(level,p,box);
        boolean old=com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.packageGpuAuthority;
        try{com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.packageGpuAuthority=false;level.addFreshEntity(reference);}
        finally{com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.packageGpuAuthority=old;}
        ejector.activate();
        helper.assertTrue(Math.abs(entry.position().y-reference.getY())<1e-7&&Math.abs(entry.position().y-p.y-.125)<1e-7,"Ejector nudge differs for record/native package");
        reference.discard();PackageAuthorityManager.consumeLight(level,entry);helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void honeyAndBubbleCallbacksPreserveWorldRandomConsumption(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var box=PackageItem.containing(List.of());
        level.setBlock(at,Blocks.HONEY_BLOCK.defaultBlockState(),2);
        for(int seed=0;seed<25;seed++){
            var reference=new Reference(level,Vec3.atCenterOf(at),box);
            var p=new Vec3(at.getX()+.96875+reference.getBbWidth()*.5,at.getY()+.1,at.getZ()+.5);reference.setPos(p);
            reference.setDeltaMovement(.2,-.2,.1);var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());
            level.random.setSeed(seed);reference.inside(level.getBlockState(at),at);long expected=level.random.nextLong();
            var contact=batch(entry,region,at,1<<31);var v=reference.getDeltaMovement();
            var sample=new PackageEnvironmentEvent.Sample(1,32,Float.floatToRawIntBits((float)(v.x*20)),Float.floatToRawIntBits((float)(v.y*20)),Float.floatToRawIntBits((float)(v.z*20)),contact.px(),contact.py(),contact.pz(),contact.effects(),0);
            level.random.setSeed(seed);PackageLightGameplay.environmentStep(level,entry,region,sample);
            helper.assertTrue(level.random.nextLong()==expected,"Honey slide consumed a different world RNG sequence, seed "+seed);
            PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        }
        for(boolean top:new boolean[]{false,true}){
            level.setBlock(at,Blocks.BUBBLE_COLUMN.defaultBlockState(),2);level.setBlock(at.above(),top?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState(),2);
            var p=Vec3.atCenterOf(at);var reference=new Reference(level,p,box);var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());
            level.random.setSeed(743);reference.inside(level.getBlockState(at),at);long expected=level.random.nextLong();
            level.random.setSeed(743);PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
            helper.assertTrue(level.random.nextLong()==expected,"Bubble-column top branch consumed a different world RNG sequence");
            PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void portalContactDefersTransitionRefreshesCooldownAndPersists(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);var box=PackageItem.containing(List.of());
        var destination=at.offset(1,0,0);
        level.setBlock(at,Blocks.END_GATEWAY.defaultBlockState(),2);
        var gateway=(net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity)level.getBlockEntity(at);gateway.setExitPosition(destination,true);
        var reference=new Reference(level,p,box);reference.inside(level.getBlockState(at),at);
        helper.assertTrue(reference.position().equals(p)&&reference.portalProcess!=null&&gateway.isCoolingDown(),"Reference gateway no longer defers teleport to base tick");
        level.setBlock(at,Blocks.AIR.defaultBlockState(),2);level.setBlock(at,Blocks.END_GATEWAY.defaultBlockState(),2);
        gateway=(net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity)level.getBlockEntity(at);gateway.setExitPosition(destination,true);
        var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());
        PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
        helper.assertTrue(entry.position().equals(p)&&at.equals(entry.pendingPortal)&&gateway.isCoolingDown(),"Record gateway teleported in the contact callback");
        helper.assertTrue((PackageLightGameplay.environmentPermissions(level,entry)&16)!=0,"Pending portal did not request the next logical tick");
        var tag=PackageLightStore.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
        var restored=PackageLightStore.load(tag,level.registryAccess()).byIdentity(entry.identity);
        helper.assertTrue(at.equals(restored.pendingPortal)&&restored.pendingPortalType==3&&restored.pendingPortalAge==1,"Pending portal did not survive archive reload");
        reference.portalTick();var center=entry.bounds().getCenter();
        var next=new PackageEnvironmentEvent.Sample(2,32,0,0,0,(float)(center.x-region.originX()),(float)(center.y-region.originY()),(float)(center.z-region.originZ()),0,0,2);
        PackageLightGameplay.environmentStep(level,entry,region,next);
        helper.assertTrue(entry.position().distanceToSqr(reference.position())<1e-12&&entry.portalCooldown==reference.getPortalCooldown(),"Gateway next-tick pose/cooldown differs from native");
        PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        for(double y:new double[]{.4,.9}){
            level.setBlock(at,Blocks.END_PORTAL.defaultBlockState(),2);p=new Vec3(at.getX()+.5,at.getY()+y,at.getZ()+.5);
            reference=new Reference(level,p,box);entry=record(helper,p,box);region=PackageRegion.at(entry.state().pose());
            reference.inside(level.getBlockState(at),at);PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
            helper.assertTrue((entry.pendingPortal!=null)==(reference.portalProcess!=null),"End portal shape intersection differs at feet "+y);
            PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        }
        level.setBlock(at,Blocks.END_PORTAL.defaultBlockState(),2);p=Vec3.atCenterOf(at);reference=new Reference(level,p,box);reference.setPortalCooldown(12);
        entry=record(helper,p,box);entry.portalCooldown=12;region=PackageRegion.at(entry.state().pose());
        reference.inside(level.getBlockState(at),at);PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
        helper.assertTrue(entry.portalCooldown==reference.getPortalCooldown()&&entry.pendingPortal==null,"Standing portal contact did not refresh native cooldown");
        PackageAuthorityManager.consumeLight(level,entry);reference.discard();helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=100)
    public static void tripwireScheduledChecksIncludeAndReleaseRecords(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));level.setBlock(at,Blocks.TRIPWIRE.defaultBlockState(),2);
        var p=new Vec3(at.getX()+.5,at.getY()+.01,at.getZ()+.5);var entry=record(helper,p,PackageItem.containing(List.of()));var region=PackageRegion.at(entry.state().pose());
        PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
        helper.assertTrue(level.getBlockState(at).getValue(TripWireBlock.POWERED),"Record did not activate tripwire");
        helper.runAfterDelay(15,()->{
            helper.assertTrue(level.getBlockState(at).getValue(TripWireBlock.POWERED),"Scheduled tripwire check ignored a standing record");PackageAuthorityManager.consumeLight(level,entry);
            helper.runAfterDelay(15,()->{helper.assertTrue(!level.getBlockState(at).getValue(TripWireBlock.POWERED),"Tripwire did not release after record consumption");helper.succeed();});
        });
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void itemArrowBoatAndRavagerOnlyCallbacksAreNoOpsForPackages(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var box=PackageItem.containing(List.of());
        for(var state:List.of(Blocks.WHEAT.defaultBlockState(),Blocks.PITCHER_CROP.defaultBlockState(),Blocks.FROGSPAWN.defaultBlockState(),
                Blocks.LILY_PAD.defaultBlockState(),Blocks.HOPPER.defaultBlockState(),Blocks.OAK_BUTTON.defaultBlockState(),Blocks.STONE_BUTTON.defaultBlockState(),
                Blocks.DETECTOR_RAIL.defaultBlockState(),Blocks.SCULK_SHRIEKER.defaultBlockState(),Blocks.POINTED_DRIPSTONE.defaultBlockState(),Blocks.HAY_BLOCK.defaultBlockState())){
            var foundation=state.getBlock() instanceof CropBlock||state.getBlock() instanceof PitcherCropBlock?Blocks.FARMLAND:
                    state.is(Blocks.FROGSPAWN)||state.is(Blocks.LILY_PAD)?Blocks.WATER:Blocks.STONE;
            level.setBlock(at.below(),foundation.defaultBlockState(),2);level.setBlock(at.south(),Blocks.STONE.defaultBlockState(),2);level.setBlock(at,state,2);
            helper.assertTrue(level.getBlockState(at).getBlock()==state.getBlock(),"Invalid no-op fixture "+state);
            var p=Vec3.atCenterOf(at);var reference=new Reference(level,p,box);var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());
            reference.inside(state,at);state.getBlock().stepOn(level,at,state,reference);state.getBlock().fallOn(level,state,at,reference,10);
            helper.assertTrue(reference.isAlive()&&reference.getHealth()==5&&level.getBlockState(at).equals(state),"Reference package unexpectedly enters an excluded callback "+state);
            helper.assertTrue((PackageBlockMaterial.encode(state)&0xff0000)==0,"Verified no-op forced an environment material "+state);
            var center=entry.bounds().getCenter();var empty=new PackageEnvironmentEvent.Sample(1,32,0,0,0,(float)(center.x-region.originX()),(float)(center.y-region.originY()),(float)(center.z-region.originZ()),0,0);
            PackageLightGameplay.environmentStep(level,entry,region,empty);
            helper.assertTrue(PackageAuthorityManager.light(level,entry.identity)==entry&&entry.health==reference.getHealth(),"No-op record differs "+state);
            PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void farmlandConsumesChanceForEveryNativePackageStyleWithoutTrampling(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));
        for(var item:PackageStyles.ALL_BOXES){
            var box=new ItemStack(item);var p=new Vec3(at.getX()+.5,at.getY()+15/16.,at.getZ()+.5);
            var reference=new Reference(level,p,box);var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());
            helper.assertTrue(entry.width*entry.width*entry.height<=.512f,"Native package style crossed farmland volume threshold");
            level.setBlock(at,Blocks.FARMLAND.defaultBlockState(),2);var state=level.getBlockState(at);
            level.random.setSeed(924);state.getBlock().fallOn(level,state,at,reference,2);long expected=level.random.nextLong();
            var contact=batch(entry,region,at,(1<<27)|(1<<28));var landing=new PackageEnvironmentEvent.Sample(1,32,0,0,0,contact.px(),contact.py(),contact.pz(),contact.effects(),2);
            level.random.setSeed(924);PackageLightGameplay.environmentStep(level,entry,region,landing);
            helper.assertTrue(level.random.nextLong()==expected&&level.getBlockState(at).is(Blocks.FARMLAND),"Farmland chance/no-op order differs for "+item);
            PackageAuthorityManager.consumeLight(level,entry);reference.discard();
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=80)
    public static void witherAmplifiersHiddenStackAndInfinitePhaseMatchNativeTicks(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);var box=PackageItem.containing(List.of());
        var originalDifficulty=level.getDifficulty();level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
        try{
            for(boolean invulnerable:new boolean[]{false,true})for(int amplifier:new int[]{0,1,2})for(int duration:new int[]{-1,1,19,20,39,40,41,65}){
                level.setBlock(at,Blocks.AIR.defaultBlockState(),2);var reference=new Reference(level,p,box);reference.setInvulnerable(invulnerable);
                reference.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER,duration,amplifier));
                var entry=record(helper,p,box);entry.data.putBoolean("Invulnerable",invulnerable);entry.witherEffect=PackageLightWither.copy(reference.getEffect(net.minecraft.world.effect.MobEffects.WITHER));entry.witherTicks=duration;
                compareWitherTicks(helper,reference,entry,at,false);reference.discard();if(PackageAuthorityManager.light(level,entry.identity)==entry)PackageAuthorityManager.consumeLight(level,entry);
            }
            level.setBlock(at,Blocks.WITHER_ROSE.defaultBlockState(),2);
            var reference=new Reference(level,p,box);reference.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER,3,2));
            var entry=record(helper,p,box);entry.witherEffect=PackageLightWither.copy(reference.getEffect(net.minecraft.world.effect.MobEffects.WITHER));entry.witherTicks=3;
            compareWitherTicks(helper,reference,entry,at,true);reference.discard();if(PackageAuthorityManager.light(level,entry.identity)==entry)PackageAuthorityManager.consumeLight(level,entry);
        }finally{level.getServer().setDifficulty(originalDifficulty,true);}
        helper.succeed();
    }
    private static void compareWitherTicks(GameTestHelper helper,Reference reference,PackageLightStore.Entry entry,BlockPos at,boolean rose){
        var level=helper.getLevel();var p=entry.position();var region=PackageRegion.at(entry.state().pose());
        for(int age=1;age<=80;age++){
            reference.tickCount=age;reference.setPos(p);reference.setDeltaMovement(Vec3.ZERO);reference.tick();
            var contact=batch(entry,region,at,0);var sample=new PackageEnvironmentEvent.Sample(age,32,0,0,0,contact.px(),contact.py(),contact.pz(),rose?contact.effects():0,0,age);
            PackageLightGameplay.environmentStep(level,entry,region,sample);
            boolean alive=PackageAuthorityManager.light(level,entry.identity)==entry;
            helper.assertTrue(alive==reference.isAlive(),"Wither damage phase/lifetime differs at age "+age+" rose="+rose);
            if(!alive)return;
            var expected=reference.getEffect(net.minecraft.world.effect.MobEffects.WITHER);var actual=entry.witherEffect;
            helper.assertTrue(expected==null?actual==null:actual!=null&&expected.save().equals(actual.save()),"Wither amplifier/duration/hidden stack differs at age "+age);
            if(age==2&&actual!=null){
                var saved=PackageLightStore.get(level).save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
                var restored=PackageLightStore.load(saved,level.registryAccess()).byIdentity(entry.identity);
                helper.assertTrue(restored.witherEffect!=null&&restored.witherEffect.save().equals(actual.save()),"Wither stack/infinite phase lost on record reload");
            }
        }
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void nativeInsideDamageMatchesPackageEntity(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);
        level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);
        for(var state:List.of(Blocks.CACTUS.defaultBlockState(),Blocks.FIRE.defaultBlockState(),Blocks.SOUL_FIRE.defaultBlockState(),
                Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,3),Blocks.CAMPFIRE.defaultBlockState())){
            level.setBlock(at.below(),state.is(Blocks.SOUL_FIRE)?Blocks.SOUL_SOIL.defaultBlockState():Blocks.STONE.defaultBlockState(),2);
            level.setBlock(at,state,2);var box=PackageItem.containing(List.of());
            var reference=new Reference(level,p,box);reference.xOld=p.x-.01;reference.zOld=p.z;
            var entry=record(helper,p,box);entry.fireTicks=reference.getRemainingFireTicks();var region=PackageRegion.at(entry.state().pose());
            var sample=batch(entry,region,at,1<<29);
            helper.assertTrue(PackageLightGameplay.environmentValid(level,entry,region,sample),"Native contact fixture rejected "+state);
            reference.inside(state,at);PackageLightGameplay.environmentStep(level,entry,region,sample);
            boolean exists=PackageAuthorityManager.light(level,entry.identity)==entry;
            helper.assertTrue(exists==reference.isAlive(),"Native inside lifetime differs "+state);
            if(exists){helper.assertTrue(Math.abs(entry.health-reference.getHealth())<1e-6,"Native inside health differs "+state);
                helper.assertTrue(entry.fireTicks==reference.getRemainingFireTicks(),"Native inside fire differs "+state);PackageAuthorityManager.consumeLight(level,entry);}
            reference.discard();
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void waterloggedScaffoldingDestroysEvenInvulnerablePackages(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);
        var state=Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.WATERLOGGED,true);level.setBlock(at,state,2);
        var box=PackageItem.containing(List.of());var reference=new Reference(level,p,box);reference.setInvulnerable(true);
        var entity=PackageEntity.fromItemStack(level,p,box.copy());entity.setInvulnerable(true);level.addFreshEntity(entity);
        var entry=PackageAuthorityManager.queryLight(level,new AABB(p,p).inflate(2)).stream().filter(e->e.uuid.equals(entity.getUUID())).findFirst().orElseThrow();
        var region=PackageRegion.at(entry.state().pose());reference.inside(state,at);
        PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,0));
        helper.assertTrue(!reference.isAlive()&&PackageAuthorityManager.light(level,entry.identity)==null,"Waterlogged block used damage immunity instead of PackageEntity direct removal");
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void waterloggedOrdinaryBlocksAndOrderedDripleafCallback(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));
        for(var state:List.of(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED,true),
                Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true))){
            level.setBlock(at.below(),Blocks.CLAY.defaultBlockState(),2);level.setBlock(at,state,2);
            var p=new Vec3(at.getX()+.5,at.getY()+.75,at.getZ()+.5);var box=PackageItem.containing(List.of());
            var reference=new Reference(level,p,box);reference.setOnGround(true);reference.inside(state,at);
            var expected=level.getBlockState(at);level.setBlock(at,state,2);
            var entry=record(helper,p,box);var region=PackageRegion.at(entry.state().pose());var sample=batch(entry,region,at,1<<27);
            helper.assertTrue((PackageBlockMaterial.encode(state)&0xff0000)!=0,"Waterlogged ordinary state omitted from GPU behavior summary");
            helper.assertTrue(PackageLightGameplay.environmentValid(level,entry,region,sample),"Waterlogged ordinary contact rejected");
            PackageLightGameplay.environmentStep(level,entry,region,sample);
            helper.assertTrue(PackageAuthorityManager.light(level,entry.identity)==null&&!reference.isAlive(),"Waterlogged direct removal differs");
            helper.assertTrue(level.getBlockState(at).equals(expected),"Block callback did not precede direct water removal");
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void freezingUsesPackageTickPhaseAndInsertionDelaySurvivesAirGap(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));
        level.setBlock(at,Blocks.POWDER_SNOW.defaultBlockState(),2);var p=Vec3.atCenterOf(at);var box=PackageItem.containing(List.of());
        var reference=new Reference(level,p,box);reference.setTicksFrozen(139);reference.tickCount=159;
        var entry=record(helper,p,box);entry.insertionDelay=1;entry.environmentMotion=new PackageEnvironmentMotion(139,0,0,0,0,1,119);
        var region=PackageRegion.at(entry.state().pose());
        for(int age:new int[]{159,160}){
            reference.tickCount=age;reference.tick();
            var contact=batch(entry,region,at,0);var sample=new PackageEnvironmentEvent.Sample(age-119,32,contact.x(),contact.y(),contact.z(),contact.px(),contact.py(),contact.pz(),contact.effects(),0,age);
            PackageLightGameplay.environmentStep(level,entry,region,sample);
            helper.assertTrue((PackageAuthorityManager.light(level,entry.identity)==entry)==reference.isAlive(),"Freezing damage used global simulation phase instead of entity tick phase "+age);
            if(age==159){helper.assertTrue(entry.environmentMotion.frozenTicks()==reference.getTicksFrozen(),"Freeze threshold counter differs");
                helper.assertTrue(entry.insertionDelay==30,"Suppressed air ticks did not age insertion delay");}
        }
        helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=100)
    public static void pressurePlateScheduledChecksIncludeAndReleaseRecords(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),2);
        level.setBlock(at,Blocks.STONE_PRESSURE_PLATE.defaultBlockState(),2);
        var p=new Vec3(at.getX()+.5,at.getY()+.0625,at.getZ()+.5);var entry=record(helper,p,PackageItem.containing(List.of()));
        var region=PackageRegion.at(entry.state().pose());PackageLightGameplay.environmentStep(level,entry,region,batch(entry,region,at,1<<27));
        helper.assertTrue(level.getBlockState(at).getValue(PressurePlateBlock.POWERED),"Record did not activate the living-entity pressure plate");
        helper.runAfterDelay(25,()->{
            helper.assertTrue(level.getBlockState(at).getValue(PressurePlateBlock.POWERED),"Scheduled pressure plate check ignored a standing record");
            PackageAuthorityManager.consumeLight(level,entry);
            helper.runAfterDelay(25,()->{helper.assertTrue(!level.getBlockState(at).getValue(PressurePlateBlock.POWERED),"Pressure plate did not release after record consumption");helper.succeed();});
        });
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void crushingControllerOwnsRecordAndRejectsConcurrentInventory(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));
        var state=com.simibubi.create.AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState()
                .setValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.VALID,true);
        level.setBlock(at,state,2);
        var controller=(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity)level.getBlockEntity(at);controller.crushingspeed=1;
        var entry=record(helper,Vec3.atCenterOf(at),PackageItem.containing(List.of()));
        helper.assertTrue(((PackageCrushingAccess)controller).cmi$startPackage(entry.identity),"Controller did not accept the record identity");
        helper.assertTrue(controller.isOccupied()&&!controller.inventory.isItemValid(0,new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)),"Controller allowed inventory during record crushing");
        controller.tick();helper.assertTrue(PackageAuthorityManager.light(level,entry.identity)==null,"Controller did not apply PackageEntity crushing damage");
        helper.assertTrue(controller.isOccupied(),"Controller released the dead-record slot before the native next-tick boundary");
        controller.tick();helper.assertTrue(!controller.isOccupied(),"Controller retained a consumed record identity");helper.succeed();
    }
    @GameTest(template="package_output_test",timeoutTicks=40)
    public static void crushingInsideStuckMultiplierSurvivesStoppedWheels(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(2,4,2));var p=Vec3.atCenterOf(at);
        for(var facing:List.of(net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.DOWN,net.minecraft.core.Direction.SOUTH)){
            var state=com.simibubi.create.AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState()
                    .setValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.VALID,true)
                    .setValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.FACING,facing);
            level.setBlock(at,state,2);var controller=(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity)level.getBlockEntity(at);controller.crushingspeed=1;
            var reference=new Reference(level,p,PackageStyles.getDefaultBox());reference.fallDistance=3;reference.inside(state,at);
            helper.assertTrue(reference.fallDistance==0,"Native crusher did not reset fall distance");
            var expected=((PackageInitialEntityAccess)(Object)reference).cmi$stuckMultiplier();
            controller.crushingspeed=0;((PackageInitialEntityAccess)(Object)reference).cmi$stuckMultiplier(Vec3.ZERO);reference.inside(state,at);
            helper.assertTrue(((PackageInitialEntityAccess)(Object)reference).cmi$stuckMultiplier().equals(expected),"Native stopped crusher no longer traps its owned entity");
            controller.clear();controller.crushingspeed=1;
            var entry=record(helper,p,PackageStyles.getDefaultBox());entry.data.putBoolean("Invulnerable",true);var region=PackageRegion.at(entry.state().pose());
            for(int age=1;age<=2;age++){
                if(age==2)controller.crushingspeed=0;
                var contact=batch(entry,region,at,0);
                PackageLightGameplay.environmentStep(level,entry,region,new PackageEnvironmentEvent.Sample(age,32,0,0,0,contact.px(),contact.py(),contact.pz(),contact.effects(),3,age));
                var motion=entry.environmentMotion;
                helper.assertTrue(Math.abs(motion.stuckX()-expected.x)<1e-7&&Math.abs(motion.stuckY()-expected.y)<1e-7&&Math.abs(motion.stuckZ()-expected.z)<1e-7,
                        "Record crusher stuck multiplier differs facing="+facing+" age="+age);
                helper.assertTrue(motion.fallDistance()==0,"Crusher stuck callback retained fall distance");
            }
            controller.clear();reference.discard();PackageAuthorityManager.consumeLight(level,entry);
        }
        helper.succeed();
    }
}
