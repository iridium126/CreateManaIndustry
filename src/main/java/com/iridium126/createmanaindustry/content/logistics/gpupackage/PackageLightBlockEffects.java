package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.core.*;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Tilt;
import net.minecraft.world.level.gameevent.GameEvent;

/** World mutations and scheduled block logic, called only for actual verified package contacts. */
final class PackageLightBlockEffects {
    private PackageLightBlockEffects() {}
    static void honeySlide(ServerLevel level,PackageLightStore.Entry entry){
        if(level.random.nextInt(5)==0)honeySound(level,entry);
        if(level.random.nextInt(5)==0)honeyParticles(level,entry,5);
    }
    private static void honeySound(ServerLevel level,PackageLightStore.Entry entry){
        if(!entry.data.getBoolean("Silent")){var p=entry.position();level.playSound(null,p.x,p.y,p.z,SoundEvents.HONEY_BLOCK_SLIDE,SoundSource.NEUTRAL,1,1);}
    }
    private static void honeyParticles(ServerLevel level,PackageLightStore.Entry entry,int count){
        var p=entry.position();level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,Blocks.HONEY_BLOCK.defaultBlockState()),p.x,p.y,p.z,count,0,0,0,0);
    }
    static void bubble(ServerLevel level,BlockPos at){
        if(!level.getBlockState(at.above()).isAir())return;
        for(int i=0;i<2;i++){
            level.sendParticles(ParticleTypes.SPLASH,at.getX()+level.random.nextDouble(),at.getY()+1.,at.getZ()+level.random.nextDouble(),1,0,0,0,1);
            level.sendParticles(ParticleTypes.BUBBLE,at.getX()+level.random.nextDouble(),at.getY()+1.,at.getZ()+level.random.nextDouble(),1,0,.01,0,.2);
        }
    }
    static void inside(ServerLevel level,PackageLightStore.Entry entry,BlockPos at,BlockState state,boolean grounded){
        if(state.getBlock() instanceof BigDripleafBlock&&grounded&&entry.position().y>at.getY()+.6875f
                &&state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.TILT)==Tilt.NONE&&!level.hasNeighborSignal(at)){
            level.setBlock(at,state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.TILT,Tilt.UNSTABLE),2);
            if(Tilt.UNSTABLE.causesVibration())level.gameEvent(null,GameEvent.BLOCK_CHANGE,at);
            level.scheduleTick(at,state.getBlock(),10);
        }
    }
    static void landed(ServerLevel level,PackageLightStore.Entry entry,BlockPos at,float distance){
        var state=level.getBlockState(at);
        // Copycat delegates only fallOn to its material. It does not delegate inside,
        // step, bounce, or speed callbacks to that material.
        if(state.getBlock() instanceof com.simibubi.create.content.decoration.copycat.CopycatBlock)
            state=com.simibubi.create.content.decoration.copycat.CopycatBlock.getMaterial(level,at);
        if(state.getBlock() instanceof FarmBlock){
            // Entity.canTrample consumes the chance before checking the remaining predicates.
            if(level.random.nextFloat()<distance-.5f&&level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                    &&entry.width*entry.width*entry.height>.512f){
                FarmBlock.turnToDirt(null,state,level,at);
                var p=entry.state().pose();double lift=Math.max(0,at.getY()+1.-p.y());
                if(lift>0)PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(
                        new PackageLease.Pose(p.x(),p.y()+lift,p.z(),p.vx(),p.vy(),p.vz(),p.yaw()),entry.state().flags()));
            }
        }else if(state.is(Blocks.TURTLE_EGG))egg(level,at,state,3);
        else if(state.is(Blocks.HONEY_BLOCK)){honeySound(level,entry);honeyParticles(level,entry,10);}
    }
    static void stepped(ServerLevel level,PackageLightStore.Entry entry,BlockPos at){
        var state=level.getBlockState(at);
        if(state.getBlock() instanceof RedStoneOreBlock){
            for(Direction direction:Direction.values()){
                var neighbor=at.relative(direction);
                if(level.getBlockState(neighbor).isSolidRender(level,neighbor))continue;
                double x=direction.getAxis()==Direction.Axis.X?.5+.5625*direction.getStepX():level.random.nextFloat();
                double y=direction.getAxis()==Direction.Axis.Y?.5+.5625*direction.getStepY():level.random.nextFloat();
                double z=direction.getAxis()==Direction.Axis.Z?.5+.5625*direction.getStepZ():level.random.nextFloat();
                level.addParticle(DustParticleOptions.REDSTONE,at.getX()+x,at.getY()+y,at.getZ()+z,0,0,0);
            }
            if(!state.getValue(RedStoneOreBlock.LIT))level.setBlock(at,state.setValue(RedStoneOreBlock.LIT,true),3);
        }else if(state.is(Blocks.TURTLE_EGG))egg(level,at,state,100);
        else if(state.getBlock() instanceof SculkSensorBlock&&SculkSensorBlock.canActivate(state)
                &&level.getBlockEntity(at) instanceof SculkSensorBlockEntity sensor
                &&sensor.getVibrationUser().canReceiveVibration(level,at,GameEvent.STEP,GameEvent.Context.of(state))){
            sensor.getListener().forceScheduleVibration(level,GameEvent.STEP,GameEvent.Context.of(state),entry.position());
        }
    }
    private static void egg(ServerLevel level,BlockPos at,BlockState state,int chance){
        if(!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)||level.random.nextInt(chance)!=0)return;
        level.playSound(null,at,SoundEvents.TURTLE_EGG_BREAK,SoundSource.BLOCKS,.7f,.9f+level.random.nextFloat()*.2f);
        int count=state.getValue(TurtleEggBlock.EGGS);
        if(count<=1)level.destroyBlock(at,false);
        else {level.setBlock(at,state.setValue(TurtleEggBlock.EGGS,count-1),2);
            level.gameEvent(GameEvent.BLOCK_DESTROY,at,GameEvent.Context.of(state));level.levelEvent(2001,at,Block.getId(state));}
    }
}
