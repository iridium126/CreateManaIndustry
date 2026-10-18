package com.iridium126.createmanaindustry.compat.trickster.sharedfate;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.forge.xplat.ForgeXplatImpl;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingContexts;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingOrigin;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSourceDescriptor;
import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.blunder.SharedFateBlunder;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;
import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundSharedFatePacket;
import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundSharedFatePacket.*;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread authority. A renderer never decides whether a relationship exists. */
public final class SharedFateManager {
    private static final Map<MinecraftServer, Runtime> RUNTIMES = new IdentityHashMap<>();
    private static final double OBSERVER_DISTANCE = 283;
    // max particle fade (256) + fade tail + sag/drift
    private SharedFateManager() {
    }
    private static Runtime runtime(MinecraftServer server) {
        return RUNTIMES.computeIfAbsent(server, Runtime::new);
    }
    public static void register() {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
                var runtime = RUNTIMES.get(event.getServer());
                if (runtime != null) {
                    runtime.refresh();
                    if ((event.getServer().getTickCount() & 1) == 0) runtime.sync();
                }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> RUNTIMES.remove(event.getServer()));
    }
    public static void bind(ServerPlayer caster, Entity target, CastChain chain, long cost) {
        Runtime runtime=runtime(caster.getServer());
        runtime.refresh();
        CastingOrigin origin = CastingContexts.origin();
        validate(caster,target,chain,origin);
        var rejection=runtime.graph.check(caster.getUUID(),target.getUUID(),target instanceof ServerPlayer);
        if(rejection!=SharedFateGraph.Rejection.NONE)throw new SharedFateBlunder(rejection.name().toLowerCase(Locale.ROOT));
        if(!BatteryAnima.consume(caster,cost))throw new GreatTricks.AnimaBlunder(cost);
        chain.addFateTarget(target.getUUID(), origin);
        runtime.add(caster,target,chain);
        runtime.sync();
    }
    private static void validate(ServerPlayer caster, Entity target, CastChain chain, CastingOrigin origin) {
        if(caster.getUUID().equals(target.getUUID()))throw new SharedFateBlunder("self");
        if(target.level()!=CastingContexts.world(caster,origin))throw new SharedFateBlunder("dimension");
        if(!target.isAlive()||target.isRemoved()||!EntityFragment.isValidEntity(target))
        throw new SharedFateBlunder("missing");
        try(var ignored=CastChains.enter(chain)) {
            if(!CastingContexts.environment(caster,origin).isVecInRange(target.position()))throw new SharedFateBlunder("range");
        }
    }
    public static void resume(CastChain chain,ServerPlayer caster) {
        if(chain.fateTargets().isEmpty())return;
        Runtime runtime=runtime(caster.getServer());
        for(UUID targetId:chain.fateTargets()) {
            var existing=runtime.graph.find(caster.getUUID(),targetId);
            if(existing!=null&&existing.chain().equals(chain.id()))continue;
            CastingOrigin origin=chain.fateOrigin(targetId);
            if(origin!=null&&origin.source().resolve(caster.getServer()).status()==CircleSourceDescriptor.Status.WAITING)continue;
            var world=CastingContexts.world(caster,origin);
            Entity target=world==null?null:world.getEntity(targetId);
            try {
                if(target==null)throw new SharedFateBlunder("missing");
                if(origin!=null&&origin.source().resolve(caster.getServer()).status()==CircleSourceDescriptor.Status.INVALID)throw new SharedFateBlunder("missing");
                validate(caster,target,chain,origin);
                if(runtime.graph.check(caster.getUUID(),targetId,target instanceof ServerPlayer)!=SharedFateGraph.Rejection.NONE)
                throw new SharedFateBlunder("duplicate");
                runtime.add(caster,target,chain);
            } catch(SharedFateBlunder invalid) {
                chain.removeFateTarget(targetId);
            }
        }
        runtime.sync();
    }
    public static void endChain(CastChain chain) {
        Runtime runtime = RUNTIMES.get(chain.runtime());
        if (runtime != null) runtime.stop(runtime.links.values().stream().filter(l->l.edge.chain().equals(chain.id())).toList(),true);
        chain.restoreFateTargets(List.of());
    }
    public static void suspend(MinecraftServer server,UUID caster) {
        Runtime runtime=RUNTIMES.get(server);
        if(runtime!=null) {
            runtime.stop(runtime.links.values().stream().filter(l->l.edge.caster().equals(caster)).toList(),false);
            runtime.observers.remove(caster);
        }
    }
    public static void refresh(MinecraftServer server) {
        Runtime runtime=RUNTIMES.get(server);
        if(runtime!=null)runtime.refresh();
    }
    /** Snapshot before executing a node: a fatal final incident must still travel down these edges. */
    public static List<UUID> outgoing(MinecraftServer server,UUID caster) {
        Runtime runtime=RUNTIMES.get(server);
        if(runtime==null)return List.of();
        runtime.refresh(caster);
        return runtime.graph.outgoing(caster).stream().map(SharedFateGraph.Edge::target).toList();
    }
    public static int bindingCount(MinecraftServer server) {
        Runtime runtime=RUNTIMES.get(server);
        return runtime==null?0:runtime.links.size();
    }
    public static Entity target(MinecraftServer server, UUID caster, UUID target) {
        Runtime runtime = RUNTIMES.get(server);
        if (runtime == null) return null;
        var edge = runtime.graph.find(caster, target);
        LinkData link = edge == null ? null : runtime.links.get(edge.id());
        if (link == null || link.origin != null
                && link.origin.source().resolve(server).status() != CircleSourceDescriptor.Status.READY) return null;
        return link.level.getEntity(target);
    }
    private static Pose pose(Entity entity) {
        return new Pose(entity.getUUID(),entity.getId(),entity.position(),entity.getBbHeight());
    }
    private static final class LinkData {
        final SharedFateGraph.Edge edge;
        final CastChain chain;
        final WeakReference<ServerPlayer> caster;
        final ServerLevel level;
        final long born;
        final CastingOrigin origin;
        Pose casterPose,targetPose;
        LinkData(SharedFateGraph.Edge edge,CastChain chain,ServerPlayer caster,Entity target) {
            this.edge=edge;
            this.chain=chain;
            this.caster=new WeakReference<>(caster);
            level=(ServerLevel)target.level();
            origin=chain.fateOrigin(target.getUUID());
            born=level.getGameTime();
            casterPose=sourcePose(caster);
            targetPose=pose(target);
        }
        Pose sourcePose(ServerPlayer player) {
            return origin==null?pose(player):new Pose(edge.id(),-1,origin.position(),0,true);
        }
        Link packet(int action,boolean animate) {
            return new Link(edge.id(),origin==null?edge.caster():edge.id(),edge.target(),action,animate);
        }
    }
    private static final class Observer {
        final Set<UUID> known=new HashSet<>();
        final Map<UUID,Pose> lastPoses=new HashMap<>();
    }
    private static final class Runtime {
        final MinecraftServer server;
        final SharedFateGraph graph=new SharedFateGraph();
        final Map<UUID,LinkData> links=new LinkedHashMap<>();
        final Map<UUID,Observer> observers=new HashMap<>();
        Runtime(MinecraftServer server) {
            this.server=server;
        }
        void add(ServerPlayer caster,Entity target,CastChain chain) {
            var edge=graph.add(chain.id(),caster.getUUID(),target.getUUID(),target instanceof ServerPlayer);
            links.put(edge.id(),new LinkData(edge,chain,caster,target));
        }
        void refresh() {
            refreshLinks(links.values());
        }
        void refresh(UUID caster) {
            refreshLinks(graph.outgoing(caster).stream().map(edge->links.get(edge.id())).toList());
        }
        void refreshLinks(Collection<LinkData> candidates) {
            var removed=new ArrayList<LinkData>();
            // Share one fresh normal range environment per caster; inversion belongs to each edge's chain.
            var ranges=new HashMap<UUID,StaffCastEnv>();
            for(LinkData link:candidates) {
                if(link.origin!=null&&link.origin.source().resolve(server).status()==CircleSourceDescriptor.Status.WAITING)continue;
                ServerPlayer caster=link.caster.get();
                Entity target=link.level.getEntity(link.edge.target());
                if(caster==null||!caster.isAlive()||caster.isRemoved()||!link.chain.live()||(link.origin==null&&caster.serverLevel()!=link.level)
                    ||(link.origin!=null&&link.origin.source().resolve(server).status()==CircleSourceDescriptor.Status.INVALID)
                    ||target==null||!target.isAlive()||target.isRemoved()
                        ||!EntityFragment.isValidEntity(target)) {
                    removed.add(link);
                    continue;
                }
                link.casterPose=link.sourcePose(caster);
                link.targetPose=pose(target);
                var range=link.origin==null?ranges.computeIfAbsent(caster.getUUID(),id->new StaffCastEnv(caster,InteractionHand.MAIN_HAND))
                :CastingContexts.environment(caster,link.origin);
                try(var ignored=CastChains.enter(link.chain)) {
                    if(!range.isVecInRange(target.position()))removed.add(link);
                }
            }
            stop(removed,true);
        }
        void stop(List<LinkData> removed,boolean forget) {
            if(removed.isEmpty())return;
            for(ServerPlayer player:server.getPlayerList().getPlayers()) {
                Observer observer=observers.get(player.getUUID());
                if(observer==null)continue;
                var commands=new ArrayList<Link>();
                var poses=new LinkedHashMap<UUID,Pose>();
                for(LinkData link:removed) if(observer.known.remove(link.edge.id())) {
                    if(player.serverLevel()!=link.level)continue;
                    commands.add(link.packet(ClientboundSharedFatePacket.REMOVE,false));
                    poses.put(link.casterPose.entity(),link.casterPose);
                    poses.put(link.targetPose.entity(),link.targetPose);
                }
                send(player,commands,new ArrayList<>(poses.values()));
            }
            for(LinkData link:removed) {
                links.remove(link.edge.id());
                graph.remove(link.edge);
                if(forget)link.chain.removeFateTarget(link.edge.target());
            }
            if(forget) {
                var changed=new HashSet<UUID>();
                for(LinkData link:removed)if(changed.add(link.chain.id()))persistStaffTargets(link.caster.get(),link.chain);
            }
        }
        void sync() {
            for(ServerPlayer player:server.getPlayerList().getPlayers()) {
                Observer observer=observers.computeIfAbsent(player.getUUID(),id->new Observer());
                var desired=new HashSet<UUID>();
                var commands=new ArrayList<Link>();
                var poses=new LinkedHashMap<UUID,Pose>();
                for(LinkData link:links.values()) {
                    if(player.serverLevel()!=link.level)continue;
                    Vec3 a=link.casterPose.position(),b=link.targetPose.position();
                    if(distanceSquared(player.position(),a,b)>OBSERVER_DISTANCE*OBSERVER_DISTANCE)continue;
                    desired.add(link.edge.id());
                    poses.put(link.casterPose.entity(),link.casterPose);
                    poses.put(link.targetPose.entity(),link.targetPose);
                    if(!observer.known.contains(link.edge.id()))commands.add(link.packet(ClientboundSharedFatePacket.ADD,link.born==link.level.getGameTime()));
                }
                for(UUID id:observer.known)if(!desired.contains(id)) {
                    LinkData link=links.get(id);
                    if(link!=null) {
                        commands.add(link.packet(ClientboundSharedFatePacket.REMOVE,false));
                        poses.put(link.casterPose.entity(),link.casterPose);
                        poses.put(link.targetPose.entity(),link.targetPose);
                    }
                }
                observer.known.clear();
                observer.known.addAll(desired);
                var changed=new ArrayList<Pose>();
                for(Pose pose:poses.values())if(!pose.equals(observer.lastPoses.put(pose.entity(),pose)))changed.add(pose);
                observer.lastPoses.keySet().retainAll(poses.keySet());
                send(player,commands,changed);
            }
        }
    }
    private static void persistStaffTargets(ServerPlayer player,CastChain chain) {
        if(player==null)return;
        var image=player.getPersistentData().getCompound(ForgeXplatImpl.TAG_VM);
        var data=image.getCompound("userData");
        String encoded=data.getString("cmi_cast_chain");
        if(encoded.isEmpty()||!CastChain.decode(encoded).id().equals(chain.id()))return;
        var targets=new ListTag();
        chain.fateTargets().forEach(id->targets.add(StringTag.valueOf(id.toString())));
        data.put("cmi_shared_fate_targets",targets);
        data.putString("cmi_effect_origins",chain.encodeOrigins());
    }
    private static void send(ServerPlayer player,List<Link> links,List<Pose> poses) {
        for(int li=0,pi=0;li<links.size()||pi<poses.size();) {
            int le=Math.min(li+ClientboundSharedFatePacket.MAX_LINKS,links.size()),pe=Math.min(pi+ClientboundSharedFatePacket.MAX_POSES,poses.size());
                PacketDistributor.sendToPlayer(player,new ClientboundSharedFatePacket(player.serverLevel().dimension().location(),
            links.subList(li,le),poses.subList(pi,pe)));
            li=le;
            pi=pe;
        }
    }
    private static double distanceSquared(Vec3 point,Vec3 a,Vec3 b) {
        Vec3 delta=b.subtract(a);
        double length=delta.lengthSqr();
        double t=length<1e-12?0:Math.max(0,Math.min(1,point.subtract(a).dot(delta)/length));
        return point.distanceToSqr(a.add(delta.scale(t)));
    }
}
