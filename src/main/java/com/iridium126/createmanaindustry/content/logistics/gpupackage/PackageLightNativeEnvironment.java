package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Native callback batches operate only on verified contacts; no entity creation or world-wide scan. */
final class PackageLightNativeEnvironment {
    private PackageLightNativeEnvironment() {}
    private static AABB bounds(PackageLightStore.Entry entry,PackageRegion region,PackageEnvironmentEvent.Sample sample) {
        double x=region.originX()+sample.px(),y=region.originY()+sample.py(),z=region.originZ()+sample.pz();
        return new AABB(x-entry.width*.5,y-entry.height*.5,z-entry.width*.5,x+entry.width*.5,y+entry.height*.5,z+entry.width*.5);
    }
    static String rejection(ServerLevel level,PackageLightStore.Entry entry,PackageRegion region,PackageEnvironmentEvent.Sample sample) {
        if(entry.width>2||entry.height>2)return "native batch exceeds collider dimensions";
        AABB bounds=bounds(entry,region,sample);BlockPos first=BlockPos.containing(bounds.minX+1e-7,bounds.minY+1e-7,bounds.minZ+1e-7);
        BlockPos last=BlockPos.containing(bounds.maxX-1e-7,bounds.maxY-1e-7,bounds.maxZ-1e-7);
        int mask=sample.effects()&0x7ffffff;
        while(mask!=0){int bit=Integer.numberOfTrailingZeros(mask);mask&=mask-1;
            BlockPos at=first.offset(bit/9,bit/3%3,bit%3);
            if(at.getX()>last.getX()||at.getY()>last.getY()||at.getZ()>last.getZ()||!level.hasChunkAt(at))return "native batch outside contact bounds";
            if((PackageBlockMaterial.encode(level.getBlockState(at))&0xff0000)==0
                    &&!machine(level,at))return "native contact type changed";
        }
        return null;
    }
    private static boolean machine(ServerLevel level,BlockPos at){
        var state=level.getBlockState(at);
        return state.getBlock() instanceof com.simibubi.create.content.logistics.funnel.FunnelBlock
                ||state.getBlock() instanceof com.simibubi.create.content.logistics.chute.AbstractChuteBlock
                ||com.simibubi.create.content.kinetics.belt.BeltBlock.canTransportObjects(state)
                ||com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(level,at,com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour.TYPE)!=null;
    }
    /** PackageEntity.hurt ordering, including bypass removal and fire's fixed package damage. */
    static boolean hurt(ServerLevel level,PackageLightStore.Entry entry,DamageSource damage) {
        if(damage.is(DamageTypeTags.BYPASSES_INVULNERABILITY))return PackageAuthorityManager.consumeLight(level,entry);
        var box=entry.box(level);if(!box.getItem().canBeHurtBy(box,damage))return false;
        if(damage==level.damageSources().inWall()&&entry.insertionDelay<20)return false;
        if(damage.is(DamageTypeTags.IS_FALL)||entry.data.getBoolean("Invulnerable"))return false;
        if(damage.is(DamageTypeTags.IS_FIRE)){
            if(box.has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT))return false;
            if(entry.fireTicks<=0){entry.fireTicks=100;return false;}
            entry.health-=.15f;if(entry.health>.5f)return false;
        }
        PackageLightGameplay.destroy(level,entry);return true;
    }
    static boolean step(ServerLevel level,PackageLightStore.Entry entry,PackageRegion region,PackageEnvironmentEvent.Sample sample) {
        var old=entry.environmentMotion;
        long elapsed=Math.max(1L,(long)sample.ticks()-old.age());
        entry.insertionDelay=(int)Math.min(30L,entry.insertionDelay+elapsed);
        entry.environmentMotion=new PackageEnvironmentMotion(old.frozenTicks(),old.fallDistance(),old.stuckX(),old.stuckY(),old.stuckZ(),entry.insertionDelay,sample.ticks());
        if(PackageLightGameplay.advancePortal(level,entry,sample.ticks(),elapsed))return true;
        boolean grounded=(sample.effects()&(1<<27))!=0;
        var pose=entry.state().pose();
        var contactPose=new PackageLease.Pose(region.originX()+sample.px(),region.originY()+sample.py()-entry.height*.5,
                region.originZ()+sample.pz(),sample.vx(),sample.vy(),sample.vz(),pose.yaw());
        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(contactPose,grounded?PackageAuthorityRegion.GROUNDED:0));
        int frozenBefore=old.frozenTicks();
        if(entry.fireTicks>0){
            frozenBefore=0;
            if(entry.box(level).has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT))entry.fireTicks=Math.max(0,entry.fireTicks-4);
            else {if(entry.fireTicks%20==0&&hurt(level,entry,level.damageSources().onFire()))return true;entry.fireTicks--;}
            if(old.frozenTicks()>0)level.levelEvent(null,1009,BlockPos.containing(contactPose.x(),contactPose.y(),contactPose.z()),1);
        }
        if(PackageLightWither.tick(level,entry,sample.ticks()))return true;
        AABB bounds=bounds(entry,region,sample);var first=BlockPos.containing(bounds.minX+1e-7,bounds.minY+1e-7,bounds.minZ+1e-7);
        int mask=sample.effects()&0x7ffffff;boolean powder=false;float sx=0,sy=0,sz=0;
        if(grounded){var feet=BlockPos.containing(contactPose.x(),contactPose.y()-.2,contactPose.z());
            if((sample.effects()&(1<<28))!=0)PackageLightBlockEffects.landed(level,entry,feet,sample.fallDistance());
            PackageLightNativeMachines.landed(level,entry);if(PackageAuthorityManager.light(level,entry.identity)!=entry)return true;
            if(level.getBlockState(feet).is(Blocks.MAGMA_BLOCK)&&hurt(level,entry,level.damageSources().hotFloor()))return true;
            PackageLightBlockEffects.stepped(level,entry,feet);
        }
        if(!entry.state().pose().equals(contactPose)){
            // Centering/landing may move the body before Entity.checkInsideBlocks. Re-evaluate
            // this exceptional, already verified callback region, never ordinary package motion.
            bounds=entry.bounds();first=BlockPos.containing(bounds.minX+1e-7,bounds.minY+1e-7,bounds.minZ+1e-7);
            var last=BlockPos.containing(bounds.maxX-1e-7,bounds.maxY-1e-7,bounds.maxZ-1e-7);mask=0;
            for(int x=first.getX();x<=last.getX();x++)for(int y=first.getY();y<=last.getY();y++)for(int z=first.getZ();z<=last.getZ();z++){
                var at=new BlockPos(x,y,z);if((PackageBlockMaterial.encode(level.getBlockState(at))&0xff0000)!=0||machine(level,at))
                    mask|=1<<((x-first.getX())*9+(y-first.getY())*3+z-first.getZ());
            }
        }
        boolean inFire=false,honeySlideApplied=false;
        while(mask!=0){int bit=Integer.numberOfTrailingZeros(mask);mask&=mask-1;
            BlockPos at=first.offset(bit/9,bit/3%3,bit%3);BlockState state=level.getBlockState(at);int kind=PackageBlockMaterial.encode(state)>>>16&255;
            if(kind==0&&machine(level,at))kind=PackageBlockMaterial.MACHINE;
            switch(kind){
                case PackageBlockMaterial.POWDER_SNOW -> {
                    powder=true;
                    if(entry.fireTicks>0&&level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING))level.destroyBlock(at,false);
                    if(level.getBlockState(BlockPos.containing(entry.position())).is(Blocks.POWDER_SNOW)){sx=.9f;sy=1.5f;sz=.9f;}
                }
                case PackageBlockMaterial.WEB -> {sx=.25f;sy=.05f;sz=.25f;}
                case PackageBlockMaterial.HONEY -> {
                    var current=entry.position();double edge=.4375+entry.width*.5;
                    if(!honeySlideApplied&&(sample.effects()&(1<<31))!=0&&!grounded&&current.y<=at.getY()+.9375-1e-7
                            &&(Math.abs(at.getX()+.5-current.x)+1e-7>edge||Math.abs(at.getZ()+.5-current.z)+1e-7>edge)){
                        PackageLightBlockEffects.honeySlide(level,entry);honeySlideApplied=true;
                    }
                }
                case PackageBlockMaterial.BUBBLE -> PackageLightBlockEffects.bubble(level,at);
                case PackageBlockMaterial.BERRY -> {
                    sx=.8f;sy=.75f;sz=.8f;
                    if(state.getValue(SweetBerryBushBlock.AGE)>0&&(sample.effects()&(1<<29))!=0
                            &&hurt(level,entry,level.damageSources().sweetBerryBush()))return true;
                }
                case PackageBlockMaterial.LAVA,PackageBlockMaterial.LAVA_CAULDRON -> {
                    if(kind==PackageBlockMaterial.LAVA_CAULDRON&&(bounds.minY>=at.getY()+.9375||bounds.maxY<=at.getY()+.25))break;
                    inFire=true;
                    if(!entry.box(level).has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT)){
                        entry.fireTicks=Math.max(entry.fireTicks,300);if(hurt(level,entry,level.damageSources().lava()))return true;
                    }
                }
                case PackageBlockMaterial.FIRE -> {
                    inFire=true;
                    if(!entry.box(level).has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT)){
                        entry.fireTicks++;if(entry.fireTicks==0)entry.fireTicks=160;
                    }
                    if(hurt(level,entry,level.damageSources().inFire()))return true;
                }
                case PackageBlockMaterial.CACTUS -> {if(hurt(level,entry,level.damageSources().cactus()))return true;}
                case PackageBlockMaterial.WITHER_ROSE -> {
                    if(level.getDifficulty()!=net.minecraft.world.Difficulty.PEACEFUL&&!entry.data.getBoolean("Invulnerable"))PackageLightWither.rose(entry);
                }
                case PackageBlockMaterial.DRIPLEAF -> PackageLightBlockEffects.inside(level,entry,at,state,grounded);
                case PackageBlockMaterial.CAMPFIRE -> {
                    if(state.getValue(CampfireBlock.LIT)&&hurt(level,entry,level.damageSources().campfire()))return true;
                }
                case PackageBlockMaterial.PRESSURE_PLATE -> {
                    var plate=(PackagePressurePlateAccess)state.getBlock();int signal=plate.cmi$signal(state);
                    if(signal==0)plate.cmi$checkPressed(null,level,at,state,signal);
                }
                case PackageBlockMaterial.TRIPWIRE -> {
                    if(!state.getValue(TripWireBlock.POWERED))((PackageTripWireAccess)state.getBlock()).cmi$checkPressed(level,at);
                }
                case PackageBlockMaterial.WATER_CAULDRON,PackageBlockMaterial.SNOW_CAULDRON -> {
                    if(entry.fireTicks>0&&bounds.minY<at.getY()+(6+state.getValue(LayeredCauldronBlock.LEVEL)*3)/16.&&bounds.maxY>at.getY()+.25){
                        entry.fireTicks=0;
                        if(kind==PackageBlockMaterial.SNOW_CAULDRON)state=Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL,state.getValue(LayeredCauldronBlock.LEVEL));
                        LayeredCauldronBlock.lowerFillLevel(state,level,at);
                    }
                }
                case PackageBlockMaterial.DRILL,PackageBlockMaterial.SAW -> {
                    if(bounds.intersects(new AABB(at).deflate(.1))&&level.getBlockEntity(at) instanceof KineticBlockEntity kinetic&&kinetic.getSpeed()!=0
                            &&hurt(level,entry,kind==PackageBlockMaterial.DRILL?CreateDamageSources.drill(level):CreateDamageSources.saw(level)))return true;
                }
                case PackageBlockMaterial.TURNTABLE -> {
                    var p=entry.state().pose();
                    if((entry.state().flags()&PackageAuthorityRegion.GROUNDED)!=0&&p.vy()<=0&&p.y()>=at.getY()+.5
                            &&level.getBlockEntity(at) instanceof KineticBlockEntity kinetic&&kinetic.getSpeed()!=0)
                        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(p.x(),p.y(),p.z(),p.vx(),p.vy(),p.vz(),p.yaw()-kinetic.getSpeed()*.3f),entry.state().flags()&~PackageAuthorityRegion.GROUNDED));
                }
                case PackageBlockMaterial.CRUSHER -> {
                    if(state.getBlock() instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock
                            &&PackageLightCrushing.start(level,entry,at)){
                        var axis=state.getValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock.FACING).getAxis();
                        sx=axis==net.minecraft.core.Direction.Axis.X?.05f:.25f;
                        sy=axis==net.minecraft.core.Direction.Axis.Y?.05f:.25f;
                        sz=axis==net.minecraft.core.Direction.Axis.Z?.05f:.25f;
                    }
                    if(state.getBlock() instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock
                            &&level.getBlockEntity(at) instanceof KineticBlockEntity kinetic){
                        var p=entry.state().pose();
                        if(p.y()>=at.getY()+1.25&&(entry.state().flags()&PackageAuthorityRegion.GROUNDED)!=0){
                            var axis=state.getValue(com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock.AXIS);
                            float vx=p.vx(),vz=p.vz();
                            if(axis==net.minecraft.core.Direction.Axis.X){vz+=kinetic.getSpeed();vx+=(at.getX()+.5-p.x())*2;}
                            else if(axis==net.minecraft.core.Direction.Axis.Z){vx-=kinetic.getSpeed();vz+=(at.getZ()+.5-p.z())*2;}
                            PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(p.x(),p.y(),p.z(),vx,p.vy(),vz,p.yaw()),entry.state().flags()));
                        }
                    }
                }
                case PackageBlockMaterial.MACHINE,PackageBlockMaterial.PORTAL -> {
                    if(kind==PackageBlockMaterial.MACHINE)PackageLightNativeMachines.inside(level,entry,at);
                    else PackageLightGameplay.portalContact(level,entry,at,state);
                    if(PackageAuthorityManager.light(level,entry.identity)!=entry)return true;
                }
                default -> { }
            }
            if(state.is(Blocks.WATER)||state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED)){
                // Entity.checkInsideBlocks invokes state.entityInside before PackageEntity.onInsideBlock.
                // The latter removes directly, independently of invulnerability or item damage immunity.
                PackageLightGameplay.destroy(level,entry);return true;
            }
        }
        var feetPosition=BlockPos.containing(entry.position());
        if(entry.fireTicks>0&&(powder||level.getBlockState(feetPosition).is(Blocks.BUBBLE_COLUMN)
                ||level.isRainingAt(feetPosition)||level.isRainingAt(BlockPos.containing(feetPosition.getX(),bounds.maxY,feetPosition.getZ()))))entry.fireTicks=-20;
        if(entry.fireTicks<=0&&!inFire)entry.fireTicks=-20;
        boolean canFreeze=!com.simibubi.create.AllEntityTypes.PACKAGE.get().is(net.minecraft.tags.EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES);
        int frozen=powder&&canFreeze?Math.min(140,frozenBefore+1):Math.max(0,frozenBefore-2);
        if(frozen>=140&&sample.ticks()%40==0&&canFreeze&&hurt(level,entry,level.damageSources().freeze()))return true;
        entry.environmentMotion=new PackageEnvironmentMotion(frozen,grounded||sx+sy+sz>0?0:sample.fallDistance(),sx,sy,sz,entry.insertionDelay,sample.ticks());
        entry.environmentStep=sample.step();PackageLightStore.get(level).setDirty();return false;
    }
}
