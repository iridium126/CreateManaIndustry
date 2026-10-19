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
    private record Scene(Object wave,FakePlayer player,AllayStormData data,Object runtime){}
    @SuppressWarnings("unchecked")
    private static Scene launch(GameTestHelper h,int kind,int members){
        var level=h.getLevel();var ground=h.absolutePos(new BlockPos(2,30,2));level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(ground.offset(4,4,0),Blocks.STONE.defaultBlockState());
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"StormMajor"));player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(ground.above()));
        var data=new AllayStormData();data.active=true;data.count=members;data.stormSeed=123;data.createdAtGameTime=level.getGameTime();level.setData(com.iridium126.createmanaindustry.CMIAttachments.STORM_DATA.get(),data);
        Object runtime=call("runtime",level);((List<?>)field(runtime,"waves")).clear();((MajorAttackSchedule)field(runtime,"schedule")).reset();((BitSet)field(runtime,"waveReserved")).clear();
        var tracks=(Map<UUID,Object>)field(runtime,"tracks");tracks.clear();
        try{Class<?> track=Class.forName(AllayStormManager.class.getName()+"$Track");var constructor=track.getDeclaredConstructor();constructor.setAccessible(true);var value=constructor.newInstance();set(value,"active",true);tracks.put(player.getUUID(),value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        set(runtime,"chaseX",player.getX()-12);set(runtime,"chaseZ",player.getZ()-12);
        double dome=ServerConfig.stormDomeChance,helix=ServerConfig.stormHelixChance;
        try{
            ServerConfig.stormDomeChance=kind==StormAttack.DOME?1:0;ServerConfig.stormHelixChance=kind==StormAttack.HELIX?1:0;
            ((MajorAttackSchedule)field(runtime,"schedule")).due(kind,level.getGameTime()-600,30);
            long begin=System.nanoTime();call("tryMajor",level,runtime,data,List.of(player),kind);
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("Storm planner kind={} members={} wallMicros={}",kind,members,(System.nanoTime()-begin)/1000);
        }finally{ServerConfig.stormDomeChance=dome;ServerConfig.stormHelixChance=helix;}
        var waves=(List<?>)field(runtime,"waves");
        if(members<16384){h.assertTrue(waves.isEmpty(),"Original member thresholds were removed");return null;}
        h.assertTrue(waves.size()==1,"Eligible forced major attack did not START: "+AllayStormManager.attackStatus(level));Object wave=waves.getFirst();
        h.assertTrue((int)field(wave,"kind")==kind,"Wrong major attack selected");
        var start=(ClientboundStormWavePacket)call("wavePacket",wave,AllayStarfall.START,true,player.getId());
        h.assertTrue(start.event()==0&&start.members().length>0,"START omitted roster");
        float lock=(float)field(wave,kind==StormAttack.HELIX?"assemble":"lock");
        set(wave,"gathered",true);
        call(kind==StormAttack.HELIX?"advanceHelix":"advanceDome",level,runtime,wave,player,level.getGameTime()+10000,lock);
        h.assertTrue((boolean)field(wave,"locked"),"Major attack never LOCKed on rough ground");
        if(kind==StormAttack.HELIX){
            var flight=(AllayHelix.Flight)field(wave,"helix");h.assertTrue(flight.thrust()==lock,"Helix waited after assembling");
            double x=flight.controls()[9]-flight.controls()[3],y=flight.controls()[10]-flight.controls()[4],z=flight.controls()[11]-flight.controls()[5];
            h.assertTrue(start.members().length==4096&&start.groupEnds()[0]==2048,"Helix fixed roster missing");
            h.assertTrue(Math.abs(Math.hypot((float)field(wave,"offsetX"),(float)field(wave,"offsetZ"))-(AllayStormData.vortexRadius(data.count)))<.001,"Helix not anchored outside storm");
            h.assertTrue((int)field(wave,"currentPhase")==4,"Immediate thrust missing");
        }else{
            call("advanceDome",level,runtime,wave,player,level.getGameTime()+10001,lock+.1f);
            h.assertTrue((int)field(wave,"currentPhase")==4,"Dome never began closing");
        }
        return new Scene(wave,player,data,runtime);
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void forcedDomeStartsAndClosesOnRoughGround(GameTestHelper h){launch(h,StormAttack.DOME,16384);h.succeed();}
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void helixAssemblesRelativeToStormThenImmediatelyAttacks(GameTestHelper h){launch(h,StormAttack.HELIX,16384);h.succeed();}
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void majorMemberThresholdsRemain(GameTestHelper h){launch(h,StormAttack.DOME,32);launch(h,StormAttack.HELIX,32);h.succeed();}
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void independentMajorsCanShareOneTarget(GameTestHelper h){
        var scene=launch(h,StormAttack.HELIX,65536);var rt=scene.runtime();double saved=ServerConfig.stormDomeChance;try{
            ServerConfig.stormDomeChance=1;((MajorAttackSchedule)field(rt,"schedule")).due(StormAttack.DOME,h.getLevel().getGameTime()-600,30);
            call("tryMajor",h.getLevel(),rt,scene.data(),List.of(scene.player()),StormAttack.DOME);
            var waves=(List<?>)field(rt,"waves");h.assertTrue(waves.size()==2,"Independent draws suppressed a second attack on the same player");
            var seen=new BitSet();for(var w:waves)for(int member:(int[])field(w,"members")){h.assertTrue(!seen.get(member),"Major attacks shared a member");seen.set(member);}
        }finally{ServerConfig.stormDomeChance=saved;}h.succeed();
    }
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void oldCompletionCannotReleaseReassignedMember(GameTestHelper h){
        var scene=launch(h,StormAttack.HELIX,16384);var rt=scene.runtime();var wave=scene.wave();var track=((Map<UUID,Object>)field(rt,"tracks")).get(scene.player().getUUID());set(track,"authority",true);
        var context=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
        var mask=new BitSet();mask.set(0);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(wave,"id"),member=((int[])field(wave,"members"))[0];var reserved=(BitSet)field(rt,"waveReserved");
        var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(identity.dimension(),identity.epoch(),id,mask.toLongArray());
        AllayStormManager.completeAttack(packet,context);h.assertTrue(!reserved.get(member),"Completed member stayed reserved");reserved.set(member);
        AllayStormManager.completeAttack(packet,context);h.assertTrue(reserved.get(member),"Duplicate old completion released a new owner");
        call("releaseWave",rt,wave);h.assertTrue(reserved.get(member),"Old wave retirement released a reassigned member");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void helixGatherFollowsBodyAndFlightFreezes(GameTestHelper h){
        var scene=launch(h,StormAttack.HELIX,16384);var wave=scene.wave();var rt=scene.runtime();var player=scene.player();long tick=h.getLevel().getGameTime();float clock=(tick&((1<<21)-1))/20f;
        set(wave,"locked",false);set(wave,"gathered",false);set(wave,"assemble",clock+1);set(wave,"lastAimTick",tick);var before=AllayHelix.axis((AllayHelix.Flight)field(wave,"helix"));
        set(rt,"chaseX",(double)field(rt,"chaseX")+7);set(rt,"chaseZ",(double)field(rt,"chaseZ")-9);player.setPos(player.getX()+50,player.getY()+40,player.getZ()-80);
        call("advanceHelix",h.getLevel(),rt,wave,player,tick+5,clock+.25f);
        h.assertTrue(Math.abs((float)field(wave,"centerX")-(float)((double)field(rt,"chaseX")+(float)field(wave,"offsetX")))<.001,"Gather anchor followed player instead of body");
        var axis=AllayHelix.axis((AllayHelix.Flight)field(wave,"helix"));h.assertTrue(Math.acos(Math.clamp(before.dot(axis),-1,1))<=Math.toRadians(2.01),"Gather aim exceeded 8 degrees per second");
        set(wave,"assemble",clock+.5f);set(wave,"gathered",true);call("advanceHelix",h.getLevel(),rt,wave,player,tick+10,clock+.5f);var frozen=((AllayHelix.Flight)field(wave,"helix")).controls().clone();
        player.setPos(player.getX()-100,player.getY()+80,player.getZ()+100);call("advanceHelix",h.getLevel(),rt,wave,player,tick+15,clock+.75f);
        h.assertTrue(Arrays.equals(frozen,((AllayHelix.Flight)field(wave,"helix")).controls()),"Launched route followed a moving target");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void deadHelixMemberDoesNotHoldReservation(GameTestHelper h){var scene=launch(h,StormAttack.HELIX,16384);int member=((int[])field(scene.wave(),"members"))[0];scene.data().dead.set(member);call("retireDeadMember",h.getLevel(),scene.runtime(),member);h.assertTrue(((BitSet)field(scene.wave(),"completed")).get(0),"Dead member blocked completion");h.assertTrue(!((BitSet)field(scene.runtime(),"waveReserved")).get(member),"Dead member stayed reserved");h.succeed();}

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeFixed8192AndFrozenCenter(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();h.assertTrue(((int[])field(w,"members")).length==8192,"Dome did not select exactly 8192");
        float x=(float)field(w,"centerX"),y=(float)field(w,"centerY"),z=(float)field(w,"centerZ");h.assertTrue(Math.abs(y-scene.player().getBoundingBox().getCenter().y)<.001,"Dome center is not player body center");
        set(w,"locked",false);set(w,"gathered",false);scene.player().setPos(x+100,y+20,z-100);call("advanceDome",h.getLevel(),scene.runtime(),w,null,h.getLevel().getGameTime()+5,(float)field(w,"start")+.3f);
        h.assertTrue((float)field(w,"centerX")==x&&(float)field(w,"centerY")==y&&(float)field(w,"centerZ")==z,"Dome followed original target");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void livingThreshold16383Rejected(GameTestHelper h){launch(h,StormAttack.DOME,16383);launch(h,StormAttack.HELIX,16383);h.succeed();}
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeHighRankCompletionAndGatherTimeout(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();var rt=scene.runtime();var track=((Map<UUID,Object>)field(rt,"tracks")).get(scene.player().getUUID());set(track,"authority",true);
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
        var identity=StormIdentity.of(h.getLevel(),scene.data());var bits=new BitSet();bits.set(8191);int id=(int)field(w,"id"),member=((int[])field(w,"members"))[8191];AllayStormManager.completeAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(identity.dimension(),identity.epoch(),id,bits.toLongArray()),ctx);
        h.assertTrue(!((BitSet)field(rt,"waveReserved")).get(member),"Rank 8191 failed to release");set(w,"locked",false);set(w,"gathered",false);float deadline=(float)field(w,"assemble");boolean advanced=(boolean)call("advanceDome",h.getLevel(),rt,w,scene.player(),h.getLevel().getGameTime()+2000,deadline);h.assertTrue(!advanced,"Unready Dome launched at its timeout");h.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void gatheredFeedbackChecksIdentityAndImmediatelyLocks(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();var rt=scene.runtime();var track=((Map<UUID,Object>)field(rt,"tracks")).get(scene.player().getUUID());set(track,"authority",true);set(w,"locked",false);set(w,"gathered",false);float now=(h.getLevel().getGameTime()&((1<<21)-1))/20f;set(w,"assemble",now+20);
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(w,"id");
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch()-1,id),ctx);h.assertTrue(!(boolean)field(w,"gathered"),"Old epoch authorized launch");
        set(track,"authority",false);AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id),ctx);h.assertTrue(!(boolean)field(w,"gathered"),"Non-authority authorized launch");set(track,"authority",true);
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id),ctx);call("advanceDome",h.getLevel(),rt,w,null,h.getLevel().getGameTime()+1,now+.05f);h.assertTrue((boolean)field(w,"locked")&&Math.abs((float)field(w,"lock")-now-.05f)<.001,"Ready Dome inserted a wait");h.succeed();
    }
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void dome8191AvailableSkipsBut8192Launches(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var rt=scene.runtime();var waves=(List<?>)field(rt,"waves");waves.clear();var reserved=(BitSet)field(rt,"waveReserved");reserved.set(0,16384);reserved.clear(0,8191);double saved=ServerConfig.stormDomeChance;try{ServerConfig.stormDomeChance=1;var schedule=(MajorAttackSchedule)field(rt,"schedule");schedule.reset();schedule.due(StormAttack.DOME,h.getLevel().getGameTime()-600,30);call("tryMajor",h.getLevel(),rt,scene.data(),List.of(scene.player()),StormAttack.DOME);h.assertTrue(waves.isEmpty(),"8191 available members launched Dome");reserved.clear(8191);schedule.reset();schedule.due(StormAttack.DOME,h.getLevel().getGameTime()-600,30);call("tryMajor",h.getLevel(),rt,scene.data(),List.of(scene.player()),StormAttack.DOME);h.assertTrue(waves.size()==1&&((int[])field(waves.getFirst(),"members")).length==8192,"8192 available members did not launch");}finally{ServerConfig.stormDomeChance=saved;}h.succeed();
    }
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeHitsOtherPlayersOnceAndKeepsVanillaInvulnerability(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"DomeVictim")){@Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}};try{var protection=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");protection.setAccessible(true);protection.setInt(player,0);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}player.setGameMode(GameType.SURVIVAL);player.setPos(scene.player().position());var tracks=(Map<UUID,Object>)field(scene.runtime(),"tracks");tracks.put(player.getUUID(),tracks.get(scene.player().getUUID()));
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?player:null);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(scene.wave(),"id");var members=(int[])field(scene.wave(),"members");
        var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),0,members[0],(float)player.getX()+1,(float)player.getY(),(float)player.getZ());float before=player.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(player.getHealth()<before,"Other active player was not hit");float after=player.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(player.getHealth()==after,"Duplicate Dome contact damaged twice");AllayStormManager.handleWaveContact(new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),0,members[1],packet.relX(),packet.relY(),packet.relZ()),ctx);h.assertTrue(player.getHealth()==after,"Dome bypassed vanilla invulnerability");h.succeed();
    }

}
