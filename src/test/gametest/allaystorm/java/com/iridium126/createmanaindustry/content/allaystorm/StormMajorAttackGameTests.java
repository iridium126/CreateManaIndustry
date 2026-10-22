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
    private static Scene launch(GameTestHelper h,int kind,int members){
        return launch(h,kind,members,null);
    }
    @SuppressWarnings("unchecked")
    private static Scene launch(GameTestHelper h,int kind,int members,com.google.gson.JsonObject evidence){
        var level=h.getLevel();var ground=h.absolutePos(new BlockPos(2,30,2));level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(ground.offset(4,4,0),Blocks.STONE.defaultBlockState());
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"StormMajor"));player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(ground.above()));
        var data=new AllayStormData();data.active=true;data.count=members;data.stormSeed=123;data.createdAtGameTime=level.getGameTime();level.setData(com.iridium126.createmanaindustry.CMIAttachments.STORM_DATA.get(),data);
        Object runtime=call("runtime",level);((List<?>)field(runtime,"waves")).clear();((MajorAttackSchedule)field(runtime,"schedule")).reset();((BitSet)field(runtime,"waveReserved")).clear();
        var tracks=(Map<UUID,Object>)field(runtime,"tracks");tracks.clear();
        try{Class<?> track=Class.forName(AllayStormManager.class.getName()+"$Track");var constructor=track.getDeclaredConstructor();constructor.setAccessible(true);var value=constructor.newInstance();set(value,"active",true);set(value,"authority",true);tracks.put(player.getUUID(),value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
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
        var identity=StormIdentity.of(level,data);var eligibleMask=new BitSet();eligibleMask.set(0,start.members().length);var readyMask=new BitSet();readyMask.set(0,(start.members().length*95+99)/100-1);readyMask.set(start.members().length-1);
        if(evidence!=null){var gson=new com.google.gson.Gson();readyMask=BitSet.valueOf(gson.fromJson(evidence.get("ready"),long[].class));eligibleMask=BitSet.valueOf(gson.fromJson(evidence.get("eligible"),long[].class));lock=start.startSec()+evidence.get("gatherSeconds").getAsFloat();}
        var context=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?player:null);
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),start.waveId(),readyMask.toLongArray(),eligibleMask.toLongArray()),context);
        h.assertTrue((boolean)field(wave,"gathered"),"95 percent GPU feedback did not pass real handler");
        var finished=(BitSet)field(wave,"completed");h.assertTrue(finished.cardinality()==start.members().length-readyMask.cardinality(),"Unready participants were not released");
        for(int rank=finished.nextSetBit(0);rank>=0;rank=finished.nextSetBit(rank+1))h.assertTrue(!((BitSet)field(runtime,"waveReserved")).get(start.members()[rank]),"Unready member remained reserved");
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
            var seen=new BitSet();for(var w:waves){int[] members=(int[])field(w,"members");var completed=(BitSet)field(w,"completed");for(int rank=0;rank<members.length;rank++)if(!completed.get(rank)){int member=members[rank];h.assertTrue(!seen.get(member),"Active major attacks shared a reserved member");seen.set(member);}}
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

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void gatherCannotReleaseAPreviouslyFinishedMembersNewOwner(GameTestHelper h){
        var scene=launch(h,StormAttack.HELIX,16384);var w=scene.wave();var rt=scene.runtime();int[] members=(int[])field(w,"members");var completed=(BitSet)field(w,"completed");int rank=completed.nextSetBit(0),member=members[rank];var reserved=(BitSet)field(rt,"waveReserved");reserved.set(member);
        set(w,"locked",false);set(w,"gathered",false);float now=(h.getLevel().getGameTime()&((1<<21)-1))/20f;set(w,"assemble",now+20);
        var selected=new BitSet();selected.set(0,members.length);selected.andNot(completed);var identity=StormIdentity.of(h.getLevel(),scene.data());
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),(int)field(w,"id"),selected.toLongArray(),selected.toLongArray()),ctx);
        h.assertTrue((boolean)field(w,"gathered")&&reserved.get(member),"Gather feedback released a new attack's reservation");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeFixed12288AndFrozenCenter(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();h.assertTrue(((int[])field(w,"members")).length==12288,"Dome did not select exactly 12288");
        float x=(float)field(w,"centerX"),y=(float)field(w,"centerY"),z=(float)field(w,"centerZ");h.assertTrue(Math.abs(y-scene.player().getBoundingBox().getCenter().y)<.001,"Dome center is not player body center");
        set(w,"locked",false);set(w,"gathered",false);scene.player().setPos(x+100,y+20,z-100);call("advanceDome",h.getLevel(),scene.runtime(),w,null,h.getLevel().getGameTime()+5,(float)field(w,"start")+.3f);
        h.assertTrue((float)field(w,"centerX")==x&&(float)field(w,"centerY")==y&&(float)field(w,"centerZ")==z,"Dome followed original target");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void livingThreshold16383Rejected(GameTestHelper h){launch(h,StormAttack.DOME,16383);launch(h,StormAttack.HELIX,16383);h.succeed();}
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeHighRankCompletionAndGatherTimeout(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();var rt=scene.runtime();var track=((Map<UUID,Object>)field(rt,"tracks")).get(scene.player().getUUID());set(track,"authority",true);
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
        var identity=StormIdentity.of(h.getLevel(),scene.data());var bits=new BitSet();bits.set(12287);int id=(int)field(w,"id"),member=((int[])field(w,"members"))[12287];AllayStormManager.completeAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(identity.dimension(),identity.epoch(),id,bits.toLongArray()),ctx);
        h.assertTrue(!((BitSet)field(rt,"waveReserved")).get(member),"Rank 12287 failed to release");set(w,"locked",false);set(w,"gathered",false);float deadline=(float)field(w,"assemble");boolean advanced=(boolean)call("advanceDome",h.getLevel(),rt,w,scene.player(),h.getLevel().getGameTime()+2000,deadline);h.assertTrue(!advanced,"Unready Dome launched at its timeout");h.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void gatheredFeedbackChecksIdentityAndImmediatelyLocks(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();var rt=scene.runtime();var track=((Map<UUID,Object>)field(rt,"tracks")).get(scene.player().getUUID());set(track,"authority",true);set(w,"locked",false);set(w,"gathered",false);float now=(h.getLevel().getGameTime()&((1<<21)-1))/20f;set(w,"assemble",now+20);
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(w,"id");
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch()-1,id,new long[0],new long[0]),ctx);h.assertTrue(!(boolean)field(w,"gathered"),"Old epoch authorized launch");
        set(track,"authority",false);AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id,new long[0],new long[0]),ctx);h.assertTrue(!(boolean)field(w,"gathered"),"Non-authority authorized launch");set(track,"authority",true);
        var ready=new BitSet();ready.set(0,11674);var eligible=(BitSet)ready.clone();AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id,ready.toLongArray(),eligible.toLongArray()),ctx);call("advanceDome",h.getLevel(),rt,w,null,h.getLevel().getGameTime()+1,now+.05f);h.assertTrue((boolean)field(w,"locked")&&Math.abs((float)field(w,"lock")-now-.05f)<.001,"Ready Dome inserted a wait");h.succeed();
    }
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void dome12287AvailableSkipsBut12288Launches(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var rt=scene.runtime();var waves=(List<?>)field(rt,"waves");waves.clear();var reserved=(BitSet)field(rt,"waveReserved");reserved.set(0,16384);reserved.clear(0,12287);double saved=ServerConfig.stormDomeChance;try{ServerConfig.stormDomeChance=1;var schedule=(MajorAttackSchedule)field(rt,"schedule");schedule.reset();schedule.due(StormAttack.DOME,h.getLevel().getGameTime()-600,30);call("tryMajor",h.getLevel(),rt,scene.data(),List.of(scene.player()),StormAttack.DOME);h.assertTrue(waves.isEmpty(),"12287 available members launched Dome");reserved.clear(12287);schedule.reset();schedule.due(StormAttack.DOME,h.getLevel().getGameTime()-600,30);call("tryMajor",h.getLevel(),rt,scene.data(),List.of(scene.player()),StormAttack.DOME);h.assertTrue(waves.size()==1&&((int[])field(waves.getFirst(),"members")).length==12288,"12288 available members did not launch");}finally{ServerConfig.stormDomeChance=saved;}h.succeed();
    }
    @SuppressWarnings("unchecked")
    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domeHitsOtherPlayersOnceAndKeepsVanillaInvulnerability(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"DomeVictim")){@Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}};try{var protection=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");protection.setAccessible(true);protection.setInt(player,0);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}player.setGameMode(GameType.SURVIVAL);player.setPos(scene.player().position());var tracks=(Map<UUID,Object>)field(scene.runtime(),"tracks");tracks.put(player.getUUID(),tracks.get(scene.player().getUUID()));
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?player:null);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(scene.wave(),"id");var members=(int[])field(scene.wave(),"members");
        var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),0,members[0],(float)player.getX()+1,(float)player.getY(),(float)player.getZ());float before=player.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(player.getHealth()<before,"Other active player was not hit");float after=player.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(player.getHealth()==after,"Duplicate Dome contact damaged twice");AllayStormManager.handleWaveContact(new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),0,members[1],packet.relX(),packet.relY(),packet.relZ()),ctx);h.assertTrue(player.getHealth()==after,"Dome bypassed vanilla invulnerability");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=100) public static void domeTemplateCrossesStationaryCenterAndSettlesOnce(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var wave=scene.wave();var victim=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"DomeCenter")){@Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}};
        try{var f=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");f.setAccessible(true);f.setInt(victim,0);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        double cx=(float)field(wave,"centerX"),cy=(float)field(wave,"centerY"),cz=(float)field(wave,"centerZ");victim.setGameMode(GameType.SURVIVAL);victim.setPos(cx,cy-.9,cz);
        @SuppressWarnings("unchecked") var tracks=(Map<UUID,Object>)field(scene.runtime(),"tracks");tracks.put(victim.getUUID(),tracks.get(scene.player().getUUID()));
        Vec3 hit=null;double best=100;
        for(double t=0;t<DomeTemplate.duration(0);t+=.025){var point=DomeTemplate.point(0,t*6).add(cx,cy-.3,cz);double distance=point.distanceTo(victim.position().add(0,.5,0));if(distance<best){best=distance;hit=point;}}
        h.assertTrue(best<1.6,"Reference core channel never contacts stationary center player");
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?victim:null);
        var identity=StormIdentity.of(h.getLevel(),scene.data());int member=((int[])field(wave,"members"))[0],id=(int)field(wave,"id");var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),0,member,(float)hit.x,(float)hit.y,(float)hit.z);
        float health=victim.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(victim.getHealth()<health,"Stationary center candidate did not settle damage");health=victim.getHealth();AllayStormManager.handleWaveContact(packet,ctx);h.assertTrue(victim.getHealth()==health,"Center contact applied twice");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=40) public static void domePausedClocksSurviveSnapshotsAndLock(GameTestHelper h){
        var scene=launch(h,StormAttack.DOME,16384);var w=scene.wave();var rt=scene.runtime();set(w,"locked",false);set(w,"gathered",false);
        float now=(h.getLevel().getGameTime()&((1<<21)-1))/20f;set(w,"assemble",now+20);float deadline=(float)field(w,"assemble");
        var ctx=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
        float[] times=new float[66];Arrays.fill(times,-1);times[65]=0;var flows=new DomeGatherState(times);var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(w,"id");
        var wrong=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch()-1,id,new long[0],new long[0],flows);AllayStormManager.gatheredAttack(wrong,ctx);h.assertTrue(((DomeGatherState)field(w,"gatherFlows")).revision()==0,"Old identity paused a current flow");
        var progress=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id,new long[0],new long[0],flows);AllayStormManager.gatheredAttack(progress,ctx);
        h.assertTrue(!(boolean)field(w,"gathered")&&(float)field(w,"assemble")==deadline,"Pause alone launched or extended the attack");
        var snapshot=(ClientboundStormWavePacket)call("wavePacket",w,0,true,scene.player().getId());h.assertTrue(snapshot.gatherFlows().pausedAt(65)==0&&snapshot.gatherFlows().revision()==1,"Observer lost pause state");
        var ready=new BitSet();ready.set(0,12288);ready.andNot((BitSet)field(w,"completed"));
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id,ready.toLongArray(),ready.toLongArray()),ctx);h.assertTrue(!(boolean)field(w,"gathered"),"A stale flow snapshot authorized launch");
        AllayStormManager.gatheredAttack(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket(identity.dimension(),identity.epoch(),id,ready.toLongArray(),ready.toLongArray(),flows),ctx);
        call("advanceDome",h.getLevel(),rt,w,null,h.getLevel().getGameTime()+1,now+.05f);h.assertTrue((boolean)field(w,"locked"),"Matching clocks did not immediately LOCK");
        var lock=(ClientboundStormWavePacket)call("wavePacket",w,2,false,-1);h.assertTrue(lock.gatherFlows().pausedAt(65)==0,"LOCK changed frozen phase");h.succeed();
    }

    @GameTest(template="storm_combat_test",timeoutTicks=100) public static void domeProductionGpuFeedbackLocksAndDamagesOnce(GameTestHelper h){
        String path=System.getProperty("cmi.dome.evidence");
        if(path==null){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("Dome GPU replay not requested; use -PdomeEvidence=build/dome-v14/gpu-evidence.json");h.succeed();return;}
        try{
            var evidence=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(path))).getAsJsonObject();
            try(var in=DomeTemplate.class.getResourceAsStream("/data/createmanaindustry/dome-template-v4.bin")){
                var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));h.assertTrue(hash.equals(evidence.get("sha256").getAsString()),"GPU evidence belongs to a different template");
            }
            h.assertTrue(evidence.has("production")&&evidence.get("production").getAsBoolean(),"Diagnostic contact bypass is not production GPU evidence");var scene=launch(h,StormAttack.DOME,16384,evidence);var victim=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"GpuDomeCenter")){@Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}};victim.setGameMode(GameType.SURVIVAL);
            @SuppressWarnings("unchecked") var tracks=(Map<UUID,Object>)field(scene.runtime(),"tracks");tracks.put(victim.getUUID(),tracks.get(scene.player().getUUID()));
            var invulnerable=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");invulnerable.setAccessible(true);invulnerable.setInt(victim,0);
            double x=(float)field(scene.wave(),"centerX"),y=(float)field(scene.wave(),"centerY"),z=(float)field(scene.wave(),"centerZ");victim.setPos(x,y-.9,z);
            var hit=evidence.getAsJsonArray("contact");int rank=hit.get(0).getAsInt(),member=((int[])field(scene.wave(),"members"))[rank];h.assertTrue(!((BitSet)field(scene.wave(),"completed")).get(rank),"Actual GPU contact came from an unready member");
            var identity=StormIdentity.of(h.getLevel(),scene.data());int id=(int)field(scene.wave(),"id");
            var packet=new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),(float)field(scene.wave(),"lock")+hit.get(4).getAsFloat(),member,(float)x+hit.get(1).getAsFloat(),(float)y+hit.get(2).getAsFloat(),(float)z+hit.get(3).getAsFloat());
            var context=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?victim:null);
            float health=victim.getHealth();AllayStormManager.handleWaveContact(packet,context);h.assertTrue(victim.getHealth()<health,"Production GPU center contact did not settle server damage");health=victim.getHealth();AllayStormManager.handleWaveContact(packet,context);h.assertTrue(victim.getHealth()==health,"Production GPU contact settled twice");
            if(evidence.has("injectedBoundaryMember")&&evidence.get("injectedBoundaryMember").getAsInt()>=0){
                int boundaryRank=evidence.get("injectedBoundaryMember").getAsInt(),boundaryMember=((int[])field(scene.wave(),"members"))[boundaryRank];var completed=new BitSet();completed.set(boundaryRank);
                var authority=(net.neoforged.neoforge.network.handling.IPayloadContext)Proxy.newProxyInstance(StormMajorAttackGameTests.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(proxy,method,args)->method.getName().equals("player")?scene.player():null);
                var completion=new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(identity.dimension(),identity.epoch(),id,completed.toLongArray());AllayStormManager.completeAttack(completion,authority);
                var reserved=(BitSet)field(scene.runtime(),"waveReserved");h.assertTrue(((BitSet)field(scene.wave(),"completed")).get(boundaryRank)&&!reserved.get(boundaryMember)&&!((BitSet)field(scene.wave(),"membership")).get(boundaryMember),"GPU boundary completion did not release server ownership");
                victim.invulnerableTime=0;float after=victim.getHealth();AllayStormManager.handleWaveContact(new com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormWaveContactPacket(identity.dimension(),id,identity.epoch(),packet.sampleSec(),boundaryMember,packet.relX(),packet.relY(),packet.relZ()),context);h.assertTrue(victim.getHealth()==after,"Late boundary-member contact settled damage");
                reserved.set(boundaryMember);AllayStormManager.completeAttack(completion,authority);h.assertTrue(reserved.get(boundaryMember),"Duplicate boundary completion released a new owner");
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("Dome GPU boundary rank={} released; late contact and duplicate completion rejected",boundaryRank);
            }
            h.succeed();
        }catch(Exception failure){throw new IllegalStateException("Dome GPU evidence replay failed",failure);}
    }

}
