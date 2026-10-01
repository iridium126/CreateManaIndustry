package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.mixin.vanilla.MinecraftInvoker;
import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** GPU selects an exact admitted package. Detached packages use stable record interactions;
 * packages still awaiting entity removal use their existing native interaction entry point. */
public final class PackageFreeInteractionClient implements AutoCloseable {
    private record Context(ClientLevel level,LocalPlayer player,Entity camera,HitResult fallback,
                           int slot,ItemStack main,ItemStack off,PackageChainInteractionClient chain,PackagePoseQueryGpu.Ray chainRay) {}
    private record ChainFlight(PackageFreePickQueue.Input input) {}
    private record HoverFlight(ClientLevel level,LocalPlayer player,Entity camera,PackagePoseQueryGpu.Ray ray,
                               long publicationVersion,long submittedNanos) {}
    private record HoverResult(HoverFlight flight,PackagePoseQueryGpu.Result result,long completedNanos) {}
    private static boolean replaying;
    private static PackagePoseQueryGpu.Result replayChainPick;
    private static Context replayContext;
    private final PackageMixedPhysicsGpu physics;
    private final PackagePoolGpu pool;
    private final PackagePoseQueryGpu queries;
    private final PackageFreePickQueue queue;
    private final double ox,oy,oz;
    private boolean chainSubmitted,chainCompleted;
    private PackagePoseQueryGpu.Result chainPick;
    private PackageChainInteractionClient chain;
    private HoverFlight hoverFlight;
    private HoverResult hoverResult;
    private PackagePoseQueryGpu.Ray lastHoverRay;
    private long lastHoverPublication=-1;
    private boolean closed;
    public PackageFreeInteractionClient(PackageMixedPhysicsGpu physics,PackagePoolGpu pool,long epoch,
                                       double ox,double oy,double oz,Function<String,String> sources) {
        this.physics=physics;this.pool=pool;this.ox=ox;this.oy=oy;this.oz=oz;
        queries=new PackagePoseQueryGpu(Math.min(131072,pool.capacity()),epoch,sources);queue=new PackageFreePickQueue(epoch);
    }
    public boolean pending(){return !closed&&!replaying&&queue.phase()!=PackageFreePickQueue.Phase.EMPTY;}
    public void chainInteraction(PackageChainInteractionClient chain){this.chain=chain;}
    public static boolean nativeReplay(){return replaying;}
    public static PackagePoseQueryGpu.Result chainPick(PackageChainInteractionClient owner){return replaying&&replayContext!=null&&replayContext.chain==owner?replayChainPick:null;}
    public static PackagePoseQueryGpu.Ray chainRay(){return replayContext==null?null:replayContext.chainRay;}
    public boolean onInput(PackageFreePickQueue.Action action) {
        var mc=Minecraft.getInstance();
        if(closed||replaying||PackageChainInteractionClient.nativeReplay()||mc.level==null||mc.player==null||mc.gameMode==null||mc.getCameraEntity()==null
                ||mc.getConnection()==null||mc.screen!=null||mc.isPaused()||mc.player.isHandsBusy())return false;
        if(pending())return true;
        if(action==PackageFreePickQueue.Action.USE&&mc.gameMode.isDestroying())return false;
        if(!mc.player.getMainHandItem().isItemEnabled(mc.level.enabledFeatures()))return false;
        if(PackageAuthorityClient.activePackages()==0)return false;
        var camera=mc.getCameraEntity();
        // The vanilla pick already includes blocks, other entities and the allay proxy. GPU
        // packages can only replace a strictly closer hit; they cannot pick through an obstacle.
        var ray=interactionRay(mc,mc.hitResult);
        if(ray==null)return false;
        PackageChainInteractionClient selectedChain=action==PackageFreePickQueue.Action.USE&&chain!=null&&chain.hasPackages()?chain:null;
        PackagePoseQueryGpu.Ray chainRay=null;
        if(selectedChain!=null) {
            var chainFrom=mc.player.getEyePosition();var chainTo=com.simibubi.create.foundation.utility.RaycastHelper.getTraceTarget(mc.player,mc.player.blockInteractionRange()+1,chainFrom);
            var delta=chainTo.subtract(chainFrom);
            if(delta.lengthSqr()>0)chainRay=new PackagePoseQueryGpu.Ray((float)(chainFrom.x-ox),(float)(chainFrom.y-oy),(float)(chainFrom.z-oz),(float)delta.x,(float)delta.y,(float)delta.z);
            else selectedChain=null;
        }
        chainSubmitted=false;chainCompleted=selectedChain==null;chainPick=PackagePoseQueryGpu.Result.NONE;
        return queue.enqueue(action,ray,new Context(mc.level,mc.player,camera,mc.hitResult,mc.player.getInventory().selected,
                mc.player.getMainHandItem().copy(),mc.player.getOffhandItem().copy(),selectedChain,chainRay));
    }
    /** Engine boundary: only completed readback is consumed; native callbacks wait for tick(). */
    public void prepare(){if(!closed)queries.poll(completed->{
        if(completed.tag() instanceof ChainFlight flight) {
            if(flight.input!=queue.input())return;
            if(completed.results().size()!=1)throw new IllegalStateException("Parallel chain pick result length");
            var result=completed.results().getFirst();
            if(result.present()&&(!result.chain()||result.state()<0||result.flags()!=PackagePoolGpu.CHAIN))throw new IllegalStateException("Parallel chain pick domain/lifecycle");
            chainPick=result;chainCompleted=true;
        }else if(completed.tag() instanceof HoverFlight flight) {
            if(completed.results().size()!=1)throw new IllegalStateException("Free hover pick result length");
            if(hoverFlight==flight) {
                hoverFlight=null;
                hoverResult=new HoverResult(flight,completed.results().getFirst(),System.nanoTime());
            }
        }else queue.completed(completed);
    });}
    public void committed(long generation,float partial) {
        if(closed)return;
        if(!pool.sourceMatches(physics.bodyBuffer(),physics.chainBuffer(),physics.historyBuffer(),physics.bodyCount(),
                (float)ox,(float)oy,(float)oz))throw new IllegalStateException("Free input uses a different publication");
        var input=queue.queued();
        if(input==null){submitHover(generation,partial);return;}
        var context=(Context)input.context();
        if(context.chain!=null&&!chainSubmitted) {
            if(!queries.pick(PackagePoseQueryGpu.Input.of(physics,pool),context.chainRay,generation,new ChainFlight(input)))return;
            chainSubmitted=true;
        }
        if(queries.pickFree(PackagePoseQueryGpu.Input.of(physics,pool),input.ray(),physics.freeCount(),partial,generation,input))queue.submitted(input);
    }
    /** Apply the newest completed GPU target after vanilla and particle targets were resolved. */
    public void injectCrosshairPick(Minecraft mc,float partial) {
        var previous=hoverResult;
        if(closed||previous==null||PackageAuthorityClient.activePackages()==0||mc.level!=previous.flight.level
                ||mc.player!=previous.flight.player||mc.getCameraEntity()!=previous.flight.camera||mc.screen!=null
                ||mc.isPaused()||mc.hitResult==null)return;
        var flight=previous.flight;var result=previous.result;
        long now=System.nanoTime();
        if(now-flight.submittedNanos>100_000_000L||now-previous.completedNanos>100_000_000L
                ||physics.freePublicationVersion()!=flight.publicationVersion
                ||!result.present()||result.chain())return;
        var offer=PackageAuthorityClient.freePickOffer(result);
        if(offer==null)return;
        var entity=mc.level.getEntity(offer.entityId());
        if(!(entity instanceof PackageEntity box)||box.isRemoved()||!box.getUUID().equals(offer.entityUuid())
                ||!PackageRenderOwnership.matchesAuthority(box,offer)
                ||box.getBbWidth()!=offer.width()||box.getBbHeight()!=offer.height())return;
        var ray=interactionRay(mc,mc.hitResult);
        if(ray==null)return;
        if(!Float.isFinite(partial)||partial<0||partial>1)return;
        float centerX=result.px()+(result.x()-result.px())*partial;
        float centerY=result.py()+(result.y()-result.py())*partial;
        float centerZ=result.pz()+(result.z()-result.pz())*partial;
        var hit=PackageFreeHoverPick.intersection(ray,ox,oy,oz,centerX,centerY,centerZ,
                offer.width()*.5f,result.halfHeight());
        if(hit==null)return;
        var eye=mc.getCameraEntity().getEyePosition(1);
        double targetDistance=eye.distanceToSqr(hit),nativeDistance=mc.hitResult.getType()==HitResult.Type.MISS
                ?Double.POSITIVE_INFINITY:eye.distanceToSqr(mc.hitResult.getLocation());
        if(!(targetDistance+1e-7<nativeDistance))return;
        mc.hitResult=new EntityHitResult(box,hit);mc.crosshairPickEntity=box;
    }
    private void submitHover(long generation,float partial) {
        if(PackageAuthorityClient.activePackages()==0||queue.phase()!=PackageFreePickQueue.Phase.EMPTY||hoverFlight!=null
                ||queries.pending()>=3)return;
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.getCameraEntity()==null||mc.screen!=null||mc.isPaused())return;
        var ray=interactionRay(mc,mc.hitResult);if(ray==null)return;
        long publication=physics.freePublicationVersion();
        if(publication==lastHoverPublication&&sameRay(ray,lastHoverRay))return;
        var flight=new HoverFlight(mc.level,mc.player,mc.getCameraEntity(),ray,publication,System.nanoTime());
        if(queries.pickFree(PackagePoseQueryGpu.Input.of(physics,pool),ray,physics.freeCount(),partial,generation,flight)) {
            hoverFlight=flight;lastHoverRay=ray;lastHoverPublication=publication;
        }
    }
    private PackagePoseQueryGpu.Ray interactionRay(Minecraft mc,HitResult obstruction) {
        var camera=mc.getCameraEntity();if(camera==null||mc.player==null)return null;
        var from=camera.getEyePosition(1);var direction=camera.getViewVector(1);double length=mc.player.entityInteractionRange();
        if(obstruction!=null&&obstruction.getType()!=HitResult.Type.MISS)
            length=Math.min(length,from.distanceTo(obstruction.getLocation()));
        if(!(length>0)||!Double.isFinite(length))return null;
        return new PackagePoseQueryGpu.Ray((float)(from.x-ox),(float)(from.y-oy),(float)(from.z-oz),
                (float)(direction.x*length),(float)(direction.y*length),(float)(direction.z*length));
    }
    private static boolean sameRay(PackagePoseQueryGpu.Ray a,PackagePoseQueryGpu.Ray b) {
        return a!=null&&b!=null&&Math.abs(a.x()-b.x())<1e-5f&&Math.abs(a.y()-b.y())<1e-5f&&Math.abs(a.z()-b.z())<1e-5f
                &&Math.abs(a.dx()-b.dx())<1e-5f&&Math.abs(a.dy()-b.dy())<1e-5f&&Math.abs(a.dz()-b.dz())<1e-5f;
    }
    /** Main-thread boundary, outside GL. The operation is taken before native callbacks run. */
    public void tick() {
        if(closed)return;var phase=queue.phase();
        if(phase!=PackageFreePickQueue.Phase.READY&&phase!=PackageFreePickQueue.Phase.TIMED_OUT)return;
        if(phase==PackageFreePickQueue.Phase.READY&&!chainCompleted)return;
        var input=queue.input();var result=queue.result();queue.clear();
        if(phase==PackageFreePickQueue.Phase.TIMED_OUT)PackageAuthorityClient.closeAll("Free package picking exceeded two ticks",true);
        replay(input,result);
    }
    private void replay(PackageFreePickQueue.Input input,PackagePoseQueryGpu.Result result) {
        if(input==null||!(input.context() instanceof Context context))return;
        var mc=Minecraft.getInstance();
        if(mc.level!=context.level||mc.player!=context.player||mc.getCameraEntity()!=context.camera||mc.screen!=null
                ||mc.gameMode==null||mc.getConnection()==null||mc.isPaused()||mc.player.getInventory().selected!=context.slot
                ||!ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(),context.main)
                ||!ItemStack.isSameItemSameComponents(mc.player.getOffhandItem(),context.off))return;
        PackageEntity selected=null;
        var offer=result!=null?PackageAuthorityClient.freePickOffer(result):null;
        if(offer!=null && (offer.entityId()==-1||PackageLightClient.has(offer.baseline().identity()))) {
            var hand=mc.player.getMainHandItem().isEmpty()?net.minecraft.world.InteractionHand.MAIN_HAND:net.minecraft.world.InteractionHand.OFF_HAND;
            if(input.action()==PackageFreePickQueue.Action.ATTACK||mc.player.getItemInHand(hand).isEmpty()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundLightPackageInteraction(offer.baseline().identity(),input.action()==PackageFreePickQueue.Action.ATTACK,hand));mc.player.swing(hand);
            }
            return;
        }
        if(offer!=null) {
            var entity=mc.level.getEntity(offer.entityId());
            if(entity instanceof PackageEntity box&&!box.isRemoved()&&PackageRenderOwnership.matchesAuthority(box,offer)
                    &&box.getBbWidth()==offer.width()&&box.getBbHeight()==offer.height())selected=box;
        }
        if(selected==null&&(chainPick==null||!chainPick.present())&&PackageLightClient.input(input.action()==PackageFreePickQueue.Action.ATTACK))return;
        var hit=mc.hitResult;var crosshair=mc.crosshairPickEntity;
        Vec3 nativePosition=selected==null?null:selected.position();float nativeYaw=selected==null?0:selected.getYRot();
        Vec3 displayed=selected==null?null:new Vec3(ox+result.ptx(),oy+result.pty()-result.halfHeight(),oz+result.ptz());
        HitResult injected=selected==null?context.fallback:new EntityHitResult(selected,new Vec3(ox+result.tx(),oy+result.ty(),oz+result.tz()));
        if(injected==null)return;
        replaying=true;replayContext=context;replayChainPick=chainCompleted?chainPick:PackagePoseQueryGpu.Result.NONE;
        try {
            // Update the existing pre-detach entity for interactAt's relative hit vector
            // and client callbacks. Preserve vanilla's independent relative packet codec base.
            if(selected!=null){selected.setPos(displayed.x,displayed.y,displayed.z);selected.setYRot(result.previousTargetYaw());}
            mc.hitResult=injected;mc.crosshairPickEntity=selected;
            if(input.action()==PackageFreePickQueue.Action.USE)((MinecraftInvoker)(Object)mc).createmanaindustry$invokeStartUseItem();
            else ((MinecraftInvoker)(Object)mc).createmanaindustry$invokeStartAttack();
        }finally {
            // Do not undo a mod callback which actually moved/removed the entity or changed world.
            if(selected!=null&&!selected.isRemoved()&&selected.level()==context.level&&selected.position().equals(displayed)) {
                selected.setPos(nativePosition.x,nativePosition.y,nativePosition.z);
                if(selected.getYRot()==result.previousTargetYaw())selected.setYRot(nativeYaw);
            }
            if(mc.hitResult==injected)mc.hitResult=hit;
            if(mc.crosshairPickEntity==selected)mc.crosshairPickEntity=crosshair;
            replaying=false;replayContext=null;replayChainPick=null;
        }
    }
    @Override public void close(){if(closed)return;closed=true;queue.clear();hoverFlight=null;hoverResult=null;queries.close();}
}
