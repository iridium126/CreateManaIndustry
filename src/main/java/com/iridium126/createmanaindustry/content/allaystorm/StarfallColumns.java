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
        final Map<UUID,BitSet> reported=new HashMap<>();
        Active(ClientboundStormColumnPacket packet){this.packet=packet;}
    }
    private final LinkedHashMap<Integer,Active> columns=new LinkedHashMap<>();
    public final StormAttackStats stats=new StormAttackStats();
    private long nextAttempt=Long.MIN_VALUE;
    private int cursor;
    public List<ClientboundStormColumnPacket> snapshots(){return columns.values().stream().map(a->a.packet).toList();}
    public boolean isEmpty(){return columns.isEmpty();}
    void reserve(ClientboundStormColumnPacket packet,BitSet reserved){
        if(packet.members().length!=64||columns.size()>=64||columns.containsKey(packet.id()))throw new IllegalArgumentException("Column capacity or roster");
        for(int member:packet.members())if(reserved.get(member))throw new IllegalArgumentException("Member already reserved");
        columns.put(packet.id(),new Active(packet));for(int member:packet.members())reserved.set(member);
    }
    public void clear(){columns.clear();nextAttempt=Long.MIN_VALUE;cursor=0;}
    public void abort(int id,BitSet reserved,Consumer<ClientboundStormColumnPacket> send){
        var active=columns.remove(id);if(active==null)return;
        for(int rank=0;rank<64;rank++)if(!active.completed.get(rank))reserved.clear(active.packet.members()[rank]);
        send.accept(active.packet.aborted());
    }
    public static float clock(long tick){return (tick&((1<<21)-1))/20f;}
    private static float time(ClientboundStormColumnPacket p,float now){return p.start()+AllayStarfall.elapsed(now,p.start());}

    public void tick(ServerLevel level,AllayStormData data,double cx,double cz,List<ServerPlayer> players,
                     BitSet reserved,IntSupplier ids,Consumer<ClientboundStormColumnPacket> send){
        long tick=level.getGameTime();float now=clock(tick);
        var currentIdentity=StormIdentity.of(level,data);
        var it=columns.values().iterator();
        while(it.hasNext()){
            var a=it.next();var p=a.packet;float at=time(p,now);
            boolean expired=at>p.end()||a.completed.cardinality()==64;
            // Frozen footprints remain independent of their triggering player.
            if(expired||!data.active||p.epoch()!=currentIdentity.epoch()||!p.dimension().equals(currentIdentity.dimension())){if(!expired)send.accept(p.aborted());for(int rank=0;rank<64;rank++)if(!a.completed.get(rank))reserved.clear(p.members()[rank]);it.remove();}
        }
        int interval=Math.max(1,(int)Math.round(ServerConfig.stormStarfallInterval*20));
        if(nextAttempt!=Long.MIN_VALUE&&tick<nextAttempt)return;
        nextAttempt=tick+interval;
        if(!data.active||players.isEmpty())return;
        var ordered=new ArrayList<>(players);ordered.sort(Comparator.comparing(ServerPlayer::getUUID));
        int first=Math.floorMod(cursor++,ordered.size());
        int available=-1; // Compute only after the first successful draw and capacity gate.
        for(int i=0;i<ordered.size();i++){
            var player=ordered.get((first+i)%ordered.size());
            // The manager shares active/alive/dimension/sky qualification with major attacks.
            float radius=AllayStormData.vortexRadius(data.count);
            if(Math.hypot(player.getX()-cx,player.getZ()-cz)>radius)continue;
            if(level.random.nextDouble()>=ServerConfig.stormStarfallChance){stats.count(StormAttackStats.Reason.DRAW_MISSED);continue;}
            if(columns.size()>=ServerConfig.stormStarfallMaxActiveColumns){stats.count(StormAttackStats.Reason.CAPACITY);continue;}
            if(available<0)available=AllayStarfall.available(data.count,data.dead,reserved);
            if(available<64){stats.count(data.aliveCount()<64?StormAttackStats.Reason.TOO_FEW:StormAttackStats.Reason.RESERVED);continue;}
            int seed=level.random.nextInt(1<<24);
            int[] roster=AllayStarfall.selectExact(data.count,data.dead,reserved,StarfallColumnRules.MEMBERS,seed);
            if(roster.length!=StarfallColumnRules.MEMBERS){stats.count(data.aliveCount()<64?StormAttackStats.Reason.TOO_FEW:StormAttackStats.Reason.RESERVED);continue;}
            float gather=ServerConfig.stormChaseY-8;
            List<Vec3> occupied=columns.values().stream().map(a->a.packet).filter(p->time(p,now)<=p.bloomEnd()).map(p->new Vec3(p.x(),p.y(),p.z())).toList();
            Vec3 landing=StarfallColumnRules.landing(player.getX(),player.getZ(),cx,cz,radius,seed,occupied,(x,z)->terrain(level,x,z,gather,Double.NaN));
            if(landing==null){stats.count(StormAttackStats.Reason.RESOURCE);continue;}
            float y=(float)ground(level,landing.x,landing.z)+.02f,start=now+.2f;
            // Conservative travel budget includes the storm envelope and the vertically staggered bundle.
            float assemble=start+Math.max(4,(radius*2+16)*2/AllayStarfall.SPEED);
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
    public boolean complete(int id,BitSet ranks,BitSet reserved){var a=columns.get(id);if(a==null||ranks.length()>64)return false;for(int rank=ranks.nextSetBit(0);rank>=0;rank=ranks.nextSetBit(rank+1))if(!a.completed.get(rank)){a.completed.set(rank);reserved.clear(a.packet.members()[rank]);}return true;}
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

    /** Returns true only after final validation and exactly-once admission. */
    public boolean contact(ServerPlayer player,AllayStormData data,StormIdentity identity,int id,long epoch,float sample,int member,Vec3 contact){
        Active a=columns.get(id);if(a==null||!data.active||!player.isAlive()||player.isSpectator()||identity.epoch()!=epoch)return false;
        var p=a.packet;if(!p.dimension().equals(player.level().dimension().location())||p.epoch()!=epoch||member<0||member>=data.count||data.dead.get(member))return false;
        int rank=-1;for(int i=0;i<p.members().length;i++)if(p.members()[i]==member){rank=i;break;}if(rank<0||a.completed.get(rank))return false;
        if(!Double.isFinite(contact.x)||!Double.isFinite(contact.y)||!Double.isFinite(contact.z))return false;
        BitSet reported=a.reported.computeIfAbsent(player.getUUID(),k->new BitSet());if(reported.get(rank))return false;reported.set(rank);
        var source=new DamageSource(player.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(CMIDamageTypes.STORM_PECK),contact);
        player.hurt(source,(float)ServerConfig.stormWaveDamage);return true;
    }
}
