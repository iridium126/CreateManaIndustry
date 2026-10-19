package com.iridium126.createmanaindustry.content.allaystorm;

import java.lang.reflect.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Exercises the real private launch/advance chain without adding test-only production APIs. */
@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class StormMajorAttackGameTests {
    private static Object field(Object object,String name){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static void set(Object object,String name,Object value){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static Object call(String name,Object... args){try{for(var method:AllayStormManager.class.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(null,args);}throw new NoSuchMethodException(name);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked")
    private static Object launch(GameTestHelper h,int kind,int members){
        var level=h.getLevel();var ground=h.absolutePos(new BlockPos(2,30,2));level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(ground.offset(4,4,0),Blocks.STONE.defaultBlockState());
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"StormMajor"));player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(ground.above()));
        var data=new AllayStormData();data.active=true;data.count=members;data.stormSeed=123;data.createdAtGameTime=level.getGameTime();
        Object runtime=call("runtime",level);((List<?>)field(runtime,"waves")).clear();((Map<?,?>)field(runtime,"waveByTarget")).clear();((BitSet)field(runtime,"waveReserved")).clear();
        var tracks=(Map<UUID,Object>)field(runtime,"tracks");tracks.clear();
        try{Class<?> track=Class.forName(AllayStormManager.class.getName()+"$Track");var constructor=track.getDeclaredConstructor();constructor.setAccessible(true);var value=constructor.newInstance();set(value,"active",true);tracks.put(player.getUUID(),value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        set(runtime,"chaseX",player.getX()-12);set(runtime,"chaseZ",player.getZ()-12);
        double dome=ServerConfig.stormDomeChance,helix=ServerConfig.stormHelixChance;
        try{
            ServerConfig.stormDomeChance=kind==StormAttack.DOME?1:0;ServerConfig.stormHelixChance=kind==StormAttack.HELIX?1:0;
            long begin=System.nanoTime();call("tickWaves",level,runtime,data,List.of(player));
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("Storm planner kind={} members={} wallMicros={}",kind,members,(System.nanoTime()-begin)/1000);
        }finally{ServerConfig.stormDomeChance=dome;ServerConfig.stormHelixChance=helix;}
        var waves=(List<?>)field(runtime,"waves");
        if(members<100){h.assertTrue(waves.isEmpty(),"Original member thresholds were removed");return null;}
        h.assertTrue(waves.size()==1,"Eligible forced major attack did not START: "+AllayStormManager.attackStatus(level));Object wave=waves.getFirst();
        h.assertTrue((int)field(wave,"kind")==kind,"Wrong major attack selected");
        var start=(ClientboundStormWavePacket)call("wavePacket",wave,AllayStarfall.START,true,player.getId());
        h.assertTrue(start.event()==0&&start.members().length>0,"START omitted roster");
        float lock=(float)field(wave,kind==StormAttack.HELIX?"assemble":"lock");
        call(kind==StormAttack.HELIX?"advanceHelix":"advanceDome",level,runtime,wave,player,level.getGameTime()+10000,lock);
        h.assertTrue((boolean)field(wave,"locked"),"Major attack never LOCKed on rough ground");
        if(kind==StormAttack.HELIX){
            var flight=(AllayHelix.Flight)field(wave,"helix");h.assertTrue(flight.thrust()==lock,"Helix waited after assembling");
            double x=flight.controls()[9]-flight.controls()[3],y=flight.controls()[10]-flight.controls()[4],z=flight.controls()[11]-flight.controls()[5];
            h.assertTrue(Math.abs(Math.atan2(-y,Math.hypot(x,z))-Math.PI/4)<.04,"Helix axis is not downward 45 degrees");
            h.assertTrue((int)field(wave,"currentPhase")==4,"Immediate thrust missing");
        }else{
            call("advanceDome",level,runtime,wave,player,level.getGameTime()+10001,lock+AllayDome.WARNING+.1f);
            h.assertTrue((int)field(wave,"currentPhase")==4,"Dome never began closing");
        }
        return wave;
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void forcedDomeStartsAndClosesOnRoughGround(GameTestHelper h){launch(h,StormAttack.DOME,16384);h.succeed();}
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void helixAssemblesNearPlayerThenImmediatelyAttacks(GameTestHelper h){launch(h,StormAttack.HELIX,16384);h.succeed();}
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void majorMemberThresholdsRemain(GameTestHelper h){launch(h,StormAttack.DOME,32);launch(h,StormAttack.HELIX,32);h.succeed();}
}
