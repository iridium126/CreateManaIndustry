package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

/** Native chain membership bridge. Lookup preparation is budgeted; an unindexed package
 * remains Create-owned. Render admission tests owning conveyors, never individual package bounds. */
public final class PackageChainClientOwnership implements PackageChainClientHooks.Listener,AutoCloseable {
    public static final PackageChainClientOwnership INSTANCE=new PackageChainClientOwnership();
    private record Native(ChainConveyorPackage box,BlockPos connection) {}
    private static final class Index {
        final ChainConveyorBlockEntity conveyor;
        final Map<PackageLease.Identity,Native> boxes=new HashMap<>();
        final Set<Claim> claims=new HashSet<>();
        Iterator<ChainConveyorPackage> loop,travelling;
        Iterator<Map.Entry<BlockPos,List<ChainConveyorPackage>>> connections;
        BlockPos connection;
        boolean queued,replacing,removed;
        int rebinding;
        Index(ChainConveyorBlockEntity c){conveyor=c;restart();}
        void restart(){boxes.clear();loop=conveyor.getLoopingPackages().iterator();connections=conveyor.getTravellingPackages().entrySet().iterator();travelling=Collections.emptyIterator();connection=null;}
    }
    private static final class Claim {
        final ClientboundChainPackagePacket offer,active;
        final ClientboundChainPackagePacket.Track track;
        final Index index;
        final int candidate;
        ChainConveyorPackage box;
        boolean rebinding,releasing,terminal;
        Claim(ClientboundChainPackagePacket o,ClientboundChainPackagePacket a,ClientboundChainPackagePacket.Track t,Index i,ChainConveyorPackage b,int c){offer=o;active=a;track=t;index=i;box=b;candidate=c;}
    }
    private final Map<ChainConveyorBlockEntity,Index> indices=new IdentityHashMap<>();
    private final Map<Integer,ClientboundChainPackagePacket.Track> tracks=new HashMap<>();
    private final Map<PackageLease.Identity,Claim> claims=new HashMap<>();
    private final ArrayDeque<Index> work=new ArrayDeque<>();
    private final Set<ChainConveyorBlockEntity> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    private PackageChainAcquisitionGpu acquisition;
    private PackageChainCheckpointGpu checkpoints;
    private PackageChainFrameScene frames;
    private double ox,oy,oz;
    private Consumer<String> failed;
    private long epoch;
    private boolean closing;
    private PackageChainClientOwnership() {}
    public void attach(PackageChainAcquisitionGpu acquisition,PackageChainCheckpointGpu checkpoints,PackageChainFrameScene frames,double ox,double oy,double oz,Consumer<String> failed) {
        if(this.acquisition!=null)throw new IllegalStateException("Chain native ownership already attached");
        if(checkpoints==null || checkpoints.epoch()!=acquisition.epoch())throw new IllegalArgumentException("Chain checkpoint epoch");
        this.checkpoints=checkpoints;this.frames=Objects.requireNonNull(frames);this.ox=ox;this.oy=oy;this.oz=oz;
        this.acquisition=Objects.requireNonNull(acquisition);this.failed=Objects.requireNonNull(failed);epoch=acquisition.epoch();
    }
    public void track(ClientboundChainPackagePacket.Track track) {
        tracks.put(track.index(),track);index(track);
    }
    private Index index(ClientboundChainPackagePacket.Track track) {
        var level=Minecraft.getInstance().level;var be=level==null?null:level.getBlockEntity(track.conveyor());
        if(!(be instanceof ChainConveyorBlockEntity conveyor) || conveyor.isRemoved() || conveyor.isVirtual())return null;
        var index=indices.get(conveyor);
        if(index==null){index=new Index(conveyor);indices.put(conveyor,index);queue(index);}return index;
    }
    private void queue(Index index){if(!index.queued){index.queued=true;work.addLast(index);}}
    /** Configured soft main-thread budget; only owning-thread access to native lists. */
    public void prepare() {
        if(acquisition==null)return;
        long until=System.nanoTime()+ClientConfig.packageMainThreadBudgetNanos();int count=0;
        while(!work.isEmpty() && count<2048 && (count%16!=0 || System.nanoTime()<until)) {
            var index=work.removeFirst();index.queued=false;
            if(index.replacing){queue(index);break;}
            try {
                if(index.loop.hasNext())accept(index,index.loop.next(),null);
                else {
                    while(!index.travelling.hasNext() && index.connections.hasNext()) {
                        var next=index.connections.next();index.connection=next.getKey();index.travelling=next.getValue().iterator();
                    }
                    if(index.travelling.hasNext())accept(index,index.travelling.next(),index.connection);
                    else {if(index.rebinding!=0)failed.accept("Native chain snapshot lost a leased identity");continue;}
                }
                count++;queue(index);
            }catch(ConcurrentModificationException changed){index.restart();queue(index);}
        }
    }
    private void accept(Index index,ChainConveyorPackage box,BlockPos connection) {
        if(!(box instanceof PackageIdentified identified) || identified.cmi$packageId()<=0 || identified.cmi$packageGeneration()<=0)return;
        var identity=new PackageLease.Identity(identified.cmi$packageId(),identified.cmi$packageGeneration());
        var previous=index.boxes.putIfAbsent(identity,new Native(box,connection));
        if(previous!=null && previous.box!=box){failed.accept("Duplicate native chain identity");return;}
        var claim=claims.get(identity);
        if(claim!=null && claim.index==index && claim.rebinding) {
            if(!Objects.equals(claim.track.connection(),connection)){failed.accept("Leased chain identity moved without a new generation");return;}
            claim.box=box;
            if(!acquire(claim)){failed.accept("Native chain snapshot could not restore ownership");return;}
            claim.rebinding=false;if(!claim.terminal)index.rebinding--;dirty.add(index.conveyor);
        }
    }
    private Native nativeBox(ClientboundChainPackagePacket offer) {
        var track=tracks.get(offer.baseline().track());if(track==null)return null;
        var index=index(track);if(index==null || index.replacing)return null;
        var result=index.boxes.get(offer.baseline().identity());
        return result!=null && Objects.equals(result.connection,track.connection())?result:null;
    }
    public boolean covered(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket checkpoint) {
        var box=nativeBox(offer);if(box==null || box.box.item==null || box.box.item.isEmpty())return false;
        return BuiltInRegistries.ITEM.getKey(box.box.item.getItem()).equals(offer.model())
                && PackageItem.getWidth(box.box.item)==offer.width() && PackageItem.getHeight(box.box.item)==offer.height();
    }
    public double hook(ClientboundChainPackagePacket offer) {
        var box=nativeBox(offer);if(box==null)throw new IllegalArgumentException("Native chain model not ready");return PackageItem.getHookDistance(box.box.item);
    }
    public PackageChainUpload.Pendulum pendulum(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket checkpoint) {
        var box=nativeBox(offer);if(box==null)return null;
        var data=box.box.physicsData(Minecraft.getInstance().level);
        if(data==null || data.pos==null || data.motion==null)return null;
        return new PackageChainUpload.Pendulum(data.pos.x,data.pos.y,data.pos.z,(float)data.motion.x,(float)data.motion.y,(float)data.motion.z,data.yaw);
    }
    public void activated(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket active,ClientboundChainPackagePacket.Track track,int candidate) {
        if(acquisition==null || active.epoch()!=epoch)return;
        var index=index(track);var box=nativeBox(offer);
        if(index==null || box==null || claims.containsKey(offer.baseline().identity())){acquisition.requestRelease(offer.baseline().index());return;}
        if(checkpoints!=null)checkpoints.activateCandidate(candidate);
        var claim=new Claim(offer,active,track,index,box.box,candidate);
        claims.put(offer.baseline().identity(),claim);index.claims.add(claim);
        if(!acquire(claim)){forget(claim);acquisition.requestRelease(offer.baseline().index());return;}
        dirty.add(index.conveyor);
    }
    private boolean acquire(Claim claim) {
        var access=(PackageChainAccess)claim.index.conveyor;
        boolean result=access.cmi$acquirePackage(claim.box,claim.track.connection(),new PackageOwnershipList.Owner<ChainConveyorPackage>() {
            @Override public void materialize(ChainConveyorPackage box){retainedCheckpoint(claim,box);}
            @Override public void released(ChainConveyorPackage box,boolean removed) {
                dirty.add(claim.index.conveyor);
                if(claim.index.replacing){claim.box=null;return;}
                if(removed)claim.box=null;
                // A conveyor lifecycle packet is retired by the server against this exact
                // track. Let its RELEASED baseline win; a client-side RELEASE here can race
                // that packet and freeze an older pose into a transformed conveyor.
                if(!claim.releasing && !claim.index.removed && !closing && acquisition!=null)
                    acquisition.requestRelease(claim.offer.baseline().index());
            }
        });
        if(result)ChainConveyorPackage.physicsDataCache.get(claim.index.conveyor.getLevel()).invalidate(claim.box.netId);
        return result;
    }
    /** Batch publication avoids copying the remaining Create list once per acquired package. */
    public void publishRenderMembership() {
        for(var conveyor:dirty)((PackageChainRenderAccess)conveyor).cmi$publishRenderPackages();dirty.clear();
    }
    public int ownedCount(){return claims.size();}
    /** Create's chain renderer is off-screen/global with a 256-block view distance.
     * Sodium owns separate global BE lists, so LevelRenderer's vanilla lists cannot
     * supply this mask. Check the renderer once per owning conveyor instead. */
    public void mainVisibilityMask(int[] mask,Frustum frustum) {
        Objects.requireNonNull(mask);Arrays.fill(mask,0);
        var mc=Minecraft.getInstance();var dispatcher=mc.getBlockEntityRenderDispatcher();
        var camera=mc.gameRenderer.getMainCamera().getPosition();
        for(var index:indices.values()) {
            var conveyor=index.conveyor;
            if(index.claims.isEmpty()||conveyor.isRemoved()||conveyor.getLevel()!=mc.level)continue;
            var localCamera=frames.localRenderCamera(index.claims.iterator().next().track.index(),camera);
            if(localCamera==null)continue;
            var renderer=dispatcher.getRenderer(conveyor);
            if(renderer==null||!renderer.shouldRender(conveyor,localCamera))continue;
            if(!renderer.shouldRenderOffScreen(conveyor)&&!ClientHooks.isBlockEntityRendererVisible(dispatcher,conveyor,frustum))continue;
            admit(mask,index);
        }
    }
    /** Mirrors Iris's block-entity shadow admission for each GPU-owned package candidate. */
    public void shadowVisibilityMask(int[] mask,Set<ChainConveyorBlockEntity> visible,double cameraX,double cameraY,double cameraZ,
            boolean cullByEntityFrustum,double maxDistance) {
        visibilityMask(mask,visible,cullByEntityFrustum,cameraX,cameraY,cameraZ,maxDistance);
    }
    private void visibilityMask(int[] mask,Set<ChainConveyorBlockEntity> visible,boolean cullByEntityFrustum,
            double cameraX,double cameraY,double cameraZ,double maxDistance) {
        Objects.requireNonNull(mask);Arrays.fill(mask,0);
        if(visible==null || visible.isEmpty())return;
        for(ChainConveyorBlockEntity conveyor:visible) {
            Index index=indices.get(conveyor);if(index==null || index.claims.isEmpty())continue;
            BlockPos pos=conveyor.getBlockPos();
            if(cullByEntityFrustum) {
                if(boxCullerRejects(pos.getX()-1,pos.getY()-1,pos.getZ()-1,pos.getX()+1,pos.getY()+1,pos.getZ()+1,
                        cameraX,cameraY,cameraZ,maxDistance))continue;
            }
            admit(mask,index);
        }
    }
    private static void admit(int[] mask,Index index) {
        for(Claim claim:index.claims) {
            int candidate=claim.candidate;
            if(candidate<0 || (candidate>>>5)>=mask.length)throw new IllegalStateException("Chain visibility candidate outside GPU mask");
            mask[candidate>>>5]|=1<<(candidate&31);
        }
    }
    private static boolean boxCullerRejects(double minX,double minY,double minZ,double maxX,double maxY,double maxZ,
            double cameraX,double cameraY,double cameraZ,double distance) {
        return maxX<cameraX-distance || minX>cameraX+distance
                || maxY<cameraY-distance || minY>cameraY+distance
                || maxZ<cameraZ-distance || minZ>cameraZ+distance;
    }
    public void released(ClientboundChainPackagePacket offer,PackageChainAuthority.Baseline baseline) {
        released(offer,baseline,null,0,0,0);
    }
    public void released(ClientboundChainPackagePacket offer,PackageChainAuthority.Baseline baseline,
                         PackagePoseQueryGpu.Result pose,double ox,double oy,double oz) {
        var claim=claims.get(offer.baseline().identity());if(claim==null || claim.offer.epoch()!=offer.epoch())return;
        claim.releasing=true;
        if(claim.box!=null) {
            if(pose==null)checkpoint(claim,claim.box,baseline);else checkpoint(claim,claim.box,pose,ox,oy,oz);
            ((PackageChainAccess)claim.index.conveyor).cmi$restorePackage(claim.box);
            dirty.add(claim.index.conveyor);
        }
        forget(claim);
    }
    /** Native BE snapshots may arrive before the retired-admission fence completes. A terminal
     * notice makes absence expected without restoring a still-visible GPU package prematurely. */
    public void terminalNotice(ClientboundChainPackagePacket notice) {
        var claim=claims.get(notice.baseline().identity());
        if(claim!=null && !claim.terminal && claim.offer.epoch()==notice.epoch()
                && notice.baseline().leaseEpoch()>=claim.active.baseline().leaseEpoch()
                && notice.baseline().revision()>=claim.active.baseline().revision()) {
            claim.terminal=true;if(claim.rebinding)claim.index.rebinding--;
        }
    }
    private void forget(Claim claim){claims.remove(claim.offer.baseline().identity(),claim);claim.index.claims.remove(claim);if(claim.rebinding){claim.rebinding=false;if(!claim.terminal)claim.index.rebinding--;}}
    private static void checkpoint(Claim claim,ChainConveyorPackage box,PackageChainAuthority.Baseline baseline) {
        if(!(box instanceof PackageIdentified id) || id.cmi$packageId()!=claim.offer.baseline().identity().id()
                || id.cmi$packageGeneration()!=claim.offer.baseline().identity().generation())return;
        var pose=baseline.state().pose();box.chainPosition=baseline.state().progress();box.yaw=pose.yaw();box.worldPosition=new Vec3(pose.x(),pose.y(),pose.z());
        var data=box.physicsData(claim.index.conveyor.getLevel());if(data==null)return;
        var target=box.worldPosition.add(0,-9.0/16,0);data.prevTargetPos=data.targetPos=target;
        // Initial acquisition may not have a completed bulk checkpoint yet.
        if(data.pos==null)data.pos=target;data.prevPos=data.pos;data.prevYaw=data.yaw;
        data.flipped=claim.track.geometry().reversed();data.setBE(claim.index.conveyor);
        ChainConveyorPackage.physicsDataCache.get(claim.index.conveyor.getLevel()).put(box.netId,data);
    }
    private static void checkpoint(Claim claim,ChainConveyorPackage box,PackagePoseQueryGpu.Result result,double ox,double oy,double oz) {
        if(!(box instanceof PackageIdentified id) || id.cmi$packageId()!=claim.offer.baseline().identity().id()
                || id.cmi$packageGeneration()!=claim.offer.baseline().identity().generation())return;
        var saved=PackageChainUpload.checkpoint(result,claim.offer.baseline().identity(),claim.track,ox,oy,oz);
        applyCheckpoint(claim,box,saved);
    }
    private void retainedCheckpoint(Claim claim,ChainConveyorPackage box) {
        var identity=claim.offer.baseline().identity();
        if(!(box instanceof PackageIdentified id) || id.cmi$packageId()!=identity.id() || id.cmi$packageGeneration()!=identity.generation())return;
        var pose=checkpoints==null?PackagePoseQueryGpu.Result.NONE:checkpoints.find(claim.candidate,identity.id(),identity.generation());
        if(!pose.present()){checkpoint(claim,box,claim.active.baseline());return;}
        applyCheckpoint(claim,box,PackageChainUpload.retainedCheckpoint(pose,identity,claim.track,ox,oy,oz));
    }
    private static void applyCheckpoint(Claim claim,ChainConveyorPackage box,PackageChainUpload.Checkpoint saved) {
        var p=saved.pendulum();box.chainPosition=saved.progress();box.yaw=saved.targetYaw();
        box.worldPosition=new Vec3(saved.hookX(),saved.hookY(),saved.hookZ());
        var level=claim.index.conveyor.getLevel();var data=box.physicsData(level);if(data==null)return;
        var previous=saved.previous();data.pos=new Vec3(p.x(),p.y(),p.z());data.motion=new Vec3(p.vx(),p.vy(),p.vz());
        data.prevPos=new Vec3(previous.x(),previous.y(),previous.z());data.yaw=p.yaw();data.prevYaw=previous.yaw();
        data.targetPos=box.worldPosition.add(0,-9.0/16,0);data.prevTargetPos=new Vec3(previous.targetX(),previous.targetY(),previous.targetZ());
        data.flipped=claim.track.geometry().reversed();data.setBE(claim.index.conveyor);
        data.lastTick=net.createmod.catnip.animation.AnimationTickHolder.getTicks();
        // Exact retained native identity; emergency use is immediately followed by ownership
        // restoration, while materialization retains ownership and does not commit gameplay.
        ChainConveyorPackage.physicsDataCache.get(level).put(box.netId,data);
    }
    @Override public void beforeRead(ChainConveyorBlockEntity conveyor) {
        var index=indices.get(conveyor);if(index==null)return;index.replacing=true;
        for(var claim:index.claims)if(!claim.rebinding){claim.rebinding=true;if(!claim.terminal)index.rebinding++;}
    }
    @Override public void afterRead(ChainConveyorBlockEntity conveyor) {
        var index=indices.get(conveyor);if(index==null)return;index.replacing=false;index.restart();
        queue(index);
    }
    @Override public void removed(ChainConveyorBlockEntity conveyor) {
        var index=indices.get(conveyor);if(index==null)return;
        // The server invalidates this conveyor's track and sends one RELEASED
        // checkpoint per lease. This only removes the stale render/index admission;
        // revoking the shared world would unnecessarily stop free packages and every
        // unrelated conveyor in the same GPU session.
        index.removed=true;
        indices.remove(conveyor);work.remove(index);index.queued=false;
    }
    @Override public void added(ChainConveyorBlockEntity conveyor,ChainConveyorPackage box,BlockPos connection) {
        var index=indices.get(conveyor);if(index!=null && !index.replacing)accept(index,box,connection);
    }
    @Override public void close() {
        if(closing)return;closing=true;
        try {
            // Only completed work is eligible, including at a resource/GL failure boundary.
            // A wait failure must not prevent restoring the last previously confirmed pose.
            if(checkpoints!=null)try{checkpoints.poll();}
            catch(RuntimeException error){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] final checkpoint poll failed",error);}
            for(var claim:List.copyOf(claims.values())) {
                claim.releasing=true;
                if(claim.box!=null) {
                    try{retainedCheckpoint(claim,claim.box);}
                    catch(RuntimeException error){
                        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] native chain checkpoint failed; retaining server baseline",error);
                        try{checkpoint(claim,claim.box,claim.active.baseline());}
                        catch(RuntimeException baselineError){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] native chain baseline restore failed",baselineError);}
                    }
                    try{((PackageChainAccess)claim.index.conveyor).cmi$restorePackage(claim.box);}
                    catch(RuntimeException error){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] native chain restore failed",error);}
                }
                dirty.add(claim.index.conveyor);
            }
            for(var conveyor:dirty)try{((PackageChainRenderAccess)conveyor).cmi$publishRenderPackages();}
            catch(RuntimeException error){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] native render restore failed",error);}
        }finally{dirty.clear();claims.clear();indices.clear();tracks.clear();work.clear();acquisition=null;checkpoints=null;frames=null;failed=null;ox=oy=oz=0;epoch=0;closing=false;}
    }
}
