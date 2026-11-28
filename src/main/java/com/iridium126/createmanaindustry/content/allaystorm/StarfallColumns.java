package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-owned frozen column plans; member motion and touchdown remain GPU-owned. */
public final class StarfallColumns {
    private static final class Active {
        final ClientboundStormColumnPacket packet;
        final BitSet completed=new BitSet();
        final AllayDome.Contacts reported=new AllayDome.Contacts();
        Active(ClientboundStormColumnPacket packet){this.packet=packet;}
    }
    private final LinkedHashMap<Integer,Active> columns=new LinkedHashMap<>();
    public final StormAttackStats stats=new StormAttackStats();
    public List<ClientboundStormColumnPacket> snapshots(){return columns.values().stream().map(a->a.packet).toList();}
    public boolean isEmpty(){return columns.isEmpty();}
    void reserve(ClientboundStormColumnPacket packet,BitSet reserved){
        if(packet.members().length!=64||columns.size()>=64||columns.containsKey(packet.id()))throw new IllegalArgumentException("Column capacity or roster");
        for(int member:packet.members())if(reserved.get(member))throw new IllegalArgumentException("Member already reserved");
        columns.put(packet.id(),new Active(packet));StormAttackMembers.reserve(reserved,packet.members());
    }
    public void clear(){columns.clear();}
    public void abort(int id,BitSet reserved,Consumer<ClientboundStormColumnPacket> send){
        var active=columns.remove(id);if(active==null)return;
        StormAttackMembers.releaseUnfinished(reserved,active.packet.members(),active.completed);
        send.accept(active.packet.aborted());
    }
    public static float clock(long tick){return (tick&((1<<21)-1))/20f;}
    private static float time(ClientboundStormColumnPacket p,float now){return p.start()+StormAttackMembers.elapsed(now,p.start());}

    public void tick(ServerLevel level,AllayStormData data,double cx,double cz,List<ServerPlayer> players,
                     BitSet reserved,IntSupplier ids,Consumer<ClientboundStormColumnPacket> send){
        tick(level,data,cx,cz,players,reserved,ids,send,(member,point,margin)->{});
    }
    public void tick(ServerLevel level,AllayStormData data,double cx,double cz,List<ServerPlayer> players,
                     BitSet reserved,IntSupplier ids,Consumer<ClientboundStormColumnPacket> send,StormMemberReturns.Release expired){
        tick(level,data,cx,cz,players,reserved,ids,send,expired,
                StormNearbyRoster.lazy(data,new Vec3(cx,ServerConfig.stormChaseY,cz),level.getGameTime()+4,reserved));
    }
    void tick(ServerLevel level,AllayStormData data,double cx,double cz,List<ServerPlayer> due,
                     BitSet reserved,IntSupplier ids,Consumer<ClientboundStormColumnPacket> send,StormMemberReturns.Release expired,
                     java.util.function.Supplier<StormNearbyRoster> nearby){
        long tick=level.getGameTime();float now=clock(tick);
        var currentIdentity=StormIdentity.of(level,data);
        var it=columns.values().iterator();
        while(it.hasNext()){
            var a=it.next();var p=a.packet;float at=time(p,now);
            boolean expiring=at>p.end()||a.completed.cardinality()==64;
            // Frozen footprints remain independent of their triggering player.
            if(expiring||!data.active||p.epoch()!=currentIdentity.epoch()||!p.dimension().equals(currentIdentity.dimension())){
                if(!expiring)send.accept(p.aborted());
                var landing=new Vec3(p.x(),p.y(),p.z());
                for(int rank=0;rank<64;rank++)if(!a.completed.get(rank)){reserved.clear(p.members()[rank]);if(expiring&&data.active&&p.epoch()==currentIdentity.epoch()&&p.dimension().equals(currentIdentity.dimension()))expired.accept(p.members()[rank],landing,4);}
                it.remove();}
        }
        if(!data.active||due.isEmpty())return;
        int available=-1; // Compute only after the first successful draw and capacity gate.
        float radius=AllayStormData.vortexRadius(data.count);
        for(var player:due){
            if(columns.size()>=ServerConfig.stormStarfallMaxActiveColumns){stats.count(StormAttackStats.Reason.CAPACITY);continue;}
            if(available<0)available=StormAttackMembers.available(data.count,data.dead,reserved);
            if(available<64){stats.count(data.aliveCount()<64?StormAttackStats.Reason.TOO_FEW:StormAttackStats.Reason.RESERVED);continue;}
            int seed=level.random.nextInt(1<<24);
            float gather=ServerConfig.stormChaseY-8;
            List<Vec3> occupied=columns.values().stream().map(a->a.packet).filter(p->time(p,now)<=p.bloomEnd()).map(p->new Vec3(p.x(),p.y(),p.z())).toList();
            Vec3 landing=StarfallColumnRules.landing(player.getX(),player.getZ(),cx,cz,radius,seed,occupied,(x,z)->terrain(level,x,z,gather,Double.NaN));
            if(landing==null){stats.count(StormAttackStats.Reason.RESOURCE);continue;}
            var slots=new Vec3[StarfallColumnRules.MEMBERS];
            var origin=new Vec3(landing.x,gather,landing.z);
            for(int rank=0;rank<slots.length;rank++)slots[rank]=StarfallColumnRules.gather(origin,rank,seed);
            var match=nearby.get().match(slots,seed);
            int[] roster=match.members();
            if(roster.length!=slots.length){stats.count(StormAttackStats.Reason.RESERVED);continue;}
            float y=(float)ground(level,landing.x,landing.z)+.02f,start=now+.2f;
            float legacy=Math.max(4,(radius*2+16)*2/StormAttackMembers.SPEED);
            float assemble=start+Math.min(legacy,StormNearbyRoster.gatherSeconds(match.farthest(),StormMotion.INTERCEPT-StormMotion.COLUMN_ORBIT));
            float touch=assemble+StarfallColumnRules.descentSeconds(gather+6-y);
            float bloomEnd=touch+StarfallColumnRules.delay(63)+StarfallColumnRules.ARRIVAL_GRACE+StarfallColumnRules.BLOOM;
            float end=bloomEnd+Math.max(4,StarfallColumnRules.descentSeconds(gather+6-y)*.5f);
            int id=ids.getAsInt();
            var packet=new ClientboundStormColumnPacket(level.dimension().location(),StormIdentity.of(level,data).epoch(),id,false,seed,start,assemble,touch,bloomEnd,end,
                (float)landing.x,y,(float)landing.z,gather,(float)cx,(float)cz,radius,roster);
            reserve(packet,reserved);available-=64;send.accept(packet);stats.count(StormAttackStats.Reason.LAUNCHED);
        }
    }

    public long[] completed(int id){var a=columns.get(id);return a==null?new long[0]:a.completed.toLongArray();}
    ClientboundStormColumnPacket plan(int id){var a=columns.get(id);return a==null?null:a.packet;}
    public boolean complete(int id,BitSet ranks,BitSet reserved){return complete(id,ranks,reserved,(member,point,margin)->{});}
    public boolean complete(int id,BitSet ranks,BitSet reserved,StormMemberReturns.Release finished){var a=columns.get(id);if(a==null||ranks.length()>64)return false;var landing=new Vec3(a.packet.x(),a.packet.y(),a.packet.z());for(int rank=ranks.nextSetBit(0);rank>=0;rank=ranks.nextSetBit(rank+1))if(!a.completed.get(rank)){a.completed.set(rank);reserved.clear(a.packet.members()[rank]);finished.accept(a.packet.members()[rank],landing,4);}return true;}
    public void deadMember(int member,BitSet reserved,Consumer<com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket> send){
        for(var a:columns.values())for(int rank=0;rank<64;rank++)if(a.packet.members()[rank]==member&&!a.completed.get(rank)){
            a.completed.set(rank);reserved.clear(member);send.accept(new com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackCompletedPacket(a.packet.dimension(),a.packet.epoch(),a.packet.id(),a.completed.toLongArray()));return;
        }
    }
    private static double ground(ServerLevel level,double x,double z){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        if(!level.hasChunk(bx>>4,bz>>4))return Double.NaN;
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,bx,bz)-1;
        var pos=new BlockPos(bx,y,bz);var state=level.getBlockState(pos);
        if(!state.getFluidState().isEmpty())return Double.NaN;
        var shape=state.getCollisionShape(level,pos);
        return shape.isEmpty()?Double.NaN:y+shape.max(net.minecraft.core.Direction.Axis.Y);
    }
    public static boolean terrain(ServerLevel level,double x,double z,double gatherY,double expectedY){
        double base=ground(level,x,z);
        return Double.isFinite(base)&&gatherY>base+6;
    }

    /** Returns true after admitting this attack sample, including when vanilla blocks damage. */
    public boolean contact(ServerPlayer player,AllayStormData data,StormIdentity identity,int id,long epoch,long sampleTick,float sample,Vec3 contact){
        Active a=columns.get(id);if(a==null||a.completed.cardinality()==64||!data.active||!player.isAlive()||player.isSpectator()||identity.epoch()!=epoch)return false;
        var p=a.packet;if(!p.dimension().equals(player.level().dimension().location())||p.epoch()!=epoch||!Float.isFinite(sample))return false;
        if(StormAttackMembers.elapsed(sample,p.assemble())<0||StormAttackMembers.elapsed(sample,p.end())>=0)return false;
        if(!Double.isFinite(contact.x)||!Double.isFinite(contact.y)||!Double.isFinite(contact.z))return false;
        if(!a.reported.add(player.getUUID(),sampleTick))return false;
        var source=new DamageSource(player.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(CMIDamageTypes.STORM_PECK),contact);
        player.hurt(source,(float)ServerConfig.stormWaveDamage);return true;
    }
}
