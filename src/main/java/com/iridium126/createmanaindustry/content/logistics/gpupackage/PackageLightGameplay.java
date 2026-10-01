package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundLightPackageInteraction;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.transport.BeltTunnelInteractionHandler;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.chute.AbstractChuteBlock;
import com.simibubi.create.content.logistics.funnel.*;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Native item/behaviour operations with a record replacing the entity lifecycle. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class PackageLightGameplay {
    private PackageLightGameplay() {}
    public static void interact(ServerboundLightPackageInteraction request,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player)||player.isSpectator())return;
        var level=player.serverLevel();var entry=PackageAuthorityManager.light(level,request.identity());if(entry==null)return;
        var from=player.getEyePosition();var to=from.add(player.getViewVector(1).scale(player.entityInteractionRange()));
        var hit=entry.bounds().inflate(.1).clip(from,to).orElse(null);if(hit==null)return;
        for(var other:PackageAuthorityManager.queryLight(level,new AABB(from,hit).inflate(.1)))if(other!=entry){var obstruction=other.bounds().clip(from,hit).orElse(null);if(obstruction!=null&&from.distanceToSqr(obstruction)+.01<from.distanceToSqr(hit))return;}
        var blocked=level.clip(new ClipContext(from,hit,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(blocked.getType()!=HitResult.Type.MISS&&blocked.getLocation().distanceToSqr(from)+.01<hit.distanceToSqr(from))return;
        // Resolve the current server record, never a client-provided position or inventory.
        if(request.attack()){if(player.getAbilities().mayBuild&&!entry.data.getBoolean("Invulnerable")&&entry.box(level).getItem().canBeHurtBy(entry.box(level),level.damageSources().playerAttack(player)))destroy(level,entry);return;}
        if(!player.getItemInHand(request.hand()).isEmpty())return;
        var stack=entry.box(level);if(stack.isEmpty())return;
        if(!PackageAuthorityManager.consumeLight(level,entry))return;
        player.setItemInHand(request.hand(),stack.copy());
        level.playSound(null,BlockPos.containing(entry.position()),SoundEvents.ITEM_PICKUP,SoundSource.PLAYERS,.2f,.75f+level.random.nextFloat());
    }
    /** Only unleased records use CPU motion during disconnection, reload or GPU recovery. */
    static void fallback(ServerLevel level,PackageLightStore.Entry entry) {
        var p=entry.state().pose();boolean grounded=(entry.state().flags()&PackageAuthorityRegion.GROUNDED)!=0;
        if(grounded&&p.vx()==0&&p.vy()==0&&p.vz()==0&&!level.noCollision(entry.bounds().move(0,-.001,0)))return;
        var motion=new Vec3(p.vx()/20.,p.vy()/20.-.08,p.vz()/20.);
        var collisions=new java.util.ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
        for(var other:PackageAuthorityManager.queryLight(level,entry.bounds().expandTowards(motion)))if(other!=entry&&other.bounds().maxY<entry.bounds().minY+.125)collisions.add(Shapes.create(other.bounds()));
        for(var other:level.getEntitiesOfClass(com.simibubi.create.content.logistics.box.PackageEntity.class,entry.bounds().expandTowards(motion)))if(!other.noPhysics&&other.getBoundingBox().maxY<entry.bounds().minY+.125)collisions.add(Shapes.create(other.getBoundingBox()));
        var moved=Entity.collideBoundingBox(null,motion,entry.bounds(),level,collisions);
        grounded=motion.y<0&&motion.y!=moved.y;double friction=grounded?level.getBlockState(BlockPos.containing(p.x(),p.y()-.5000001,p.z())).getBlock().getFriction()*.91:.91;
        float vx=(float)(motion.x==moved.x?motion.x*friction*20:0),vy=(float)(motion.y==moved.y?motion.y*.98*20:0),vz=(float)(motion.z==moved.z?motion.z*friction*20:0);
        if(Math.abs(vx)<.06)vx=0;if(Math.abs(vy)<.06)vy=0;if(Math.abs(vz)<.06)vz=0;
        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(p.x()+moved.x,p.y()+moved.y,p.z()+moved.z,vx,vy,vz,p.yaw()),grounded?PackageAuthorityRegion.GROUNDED:0));
    }
    static boolean center(ServerLevel level,PackageLightStore.Entry entry,Vec3 target) {
        PackageAuthorityManager.pauseForMachine(level,entry);var p=entry.state().pose();var pos=entry.position().lerp(target,.2);
        float yaw=net.createmod.catnip.math.AngleHelper.angleLerp(.5f,p.yaw(),((int)p.yaw())/90*90);
        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(pos.x,pos.y,pos.z,p.vx()*.75f,p.vy()*.1875f,p.vz()*.75f,yaw),entry.state().flags()));
        entry.insertionDelay=Math.max(entry.insertionDelay-3,0);return entry.insertionDelay==0;
    }
    static void tick(ServerLevel level,PackageLightStore.Entry entry) {
        entry.insertionDelay=Math.min(entry.insertionDelay+1,30);
        var bounds=entry.bounds().deflate(.001);var pos=entry.position();var box=entry.box(level);
        if(!PackageItem.isPackage(box)){PackageAuthorityManager.consumeLight(level,entry);return;}
        if(pos.y<level.getMinBuildHeight()-64){PackageAuthorityManager.consumeLight(level,entry);return;}
        if(entry.fireTicks>0) {
            boolean damageAllowed=!entry.data.getBoolean("Invulnerable")&&box.getItem().canBeHurtBy(box,level.damageSources().onFire());
            boolean destroyed=burnTick(entry,damageAllowed);PackageLightStore.get(level).setDirty();
            if(destroyed){destroy(level,entry);return;}
        }
        for(var blockPos:BlockPos.betweenClosed(BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ),BlockPos.containing(bounds.maxX,bounds.maxY,bounds.maxZ))) {
            var state=level.getBlockState(blockPos);var block=state.getBlock();
            if(!state.getFluidState().isEmpty()&&!state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)){destroy(level,entry);return;}
            if(block instanceof FireBlock||state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                var damage=state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)?level.damageSources().lava():level.damageSources().inFire();
                if(!entry.data.getBoolean("Invulnerable")&&box.getItem().canBeHurtBy(box,damage)) {
                    boolean destroyed=burnContact(entry);PackageLightStore.get(level).setDirty();
                    if(destroyed){destroy(level,entry);return;}
                }
            }
            if(block==Blocks.NETHER_PORTAL&&entry.portalCooldown==0){portal(level,entry,blockPos.immutable(),state);return;}
            if(block==Blocks.END_PORTAL&&entry.portalCooldown==0){endPortal(level,entry);return;}
            if(block==Blocks.END_GATEWAY&&entry.portalCooldown==0&&level.getBlockEntity(blockPos) instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity gateway&&!gateway.isCoolingDown()) {
                var destination=gateway.getPortalPosition(level,blockPos);if(destination!=null){var current=entry.state().pose();PackageAuthorityManager.transferLight(level,level,entry,new PackageLease.Pose(destination.x,destination.y,destination.z,current.vx(),current.vy(),current.vz(),current.yaw()));net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity.triggerCooldown(level,blockPos,state,gateway);return;}
            }
            if(block instanceof FunnelBlock&&!state.getValue(AbstractFunnelBlock.POWERED)&&!state.getValue(FunnelBlock.EXTRACTING)) {
                var facing=AbstractFunnelBlock.getFunnelFacing(state);var open=Vec3.atCenterOf(blockPos).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(-.125));var diff=pos.subtract(open);
                if((facing.getAxis().choose(diff.x,diff.y,diff.z)<0)==(facing.getAxisDirection()==Direction.AxisDirection.POSITIVE))continue;
                var filter=BlockEntityBehaviour.get(level,blockPos,FilteringBehaviour.TYPE);
                if(filter!=null&&filter.test(box)&&!center(level,entry,open.add(0,facing==Direction.UP?.25:-.5,0)))return;
                if(AbstractFunnelBlock.tryInsert(level,blockPos,box.copy(),false).isEmpty()){PackageAuthorityManager.consumeLight(level,entry);return;}
            }
        }
        // Create's landing callbacks use the same DirectBeltInputBehaviour and belt inventory.
        var feet=BlockPos.containing(pos);for(var at:List.of(feet,feet.below())) {
            var state=level.getBlockState(at);var input=BlockEntityBehaviour.get(level,at,DirectBeltInputBehaviour.TYPE);
            boolean chute=state.getBlock() instanceof AbstractChuteBlock;
            boolean belt=BeltBlock.canTransportObjects(state);
            if(entry.state().pose().vy()>0||pos.y<at.getY()||pos.y>at.getY()+1.15)continue;
            if(chute&&input!=null&&input.canInsertFromSide(Direction.UP)) {
                if(!center(level,entry,Vec3.atBottomCenterOf(at.above())))return;
                if(input.handleInsertion(box.copy(),Direction.UP,false).isEmpty()){if(entry.tossedBy!=null){var player=level.getServer().getPlayerList().getPlayer(entry.tossedBy);if(player!=null)com.simibubi.create.foundation.advancement.AllAdvancements.PACKAGE_CHUTE_THROW.awardTo(player);}PackageAuthorityManager.consumeLight(level,entry);return;}
            }else if(belt) {
                if(BeltTunnelInteractionHandler.getTunnelOnPosition(level,at)!=null)continue;
                if(!center(level,entry,Vec3.atCenterOf(at).add(0,5/16.,0)))return;
                var handler=level.getCapability(Capabilities.ItemHandler.BLOCK,at,null);
                if(handler!=null&&handler.insertItem(0,box.copy(),false).isEmpty()){PackageAuthorityManager.consumeLight(level,entry);return;}
            }else if(input!=null&&!chute) {
                if(!center(level,entry,Vec3.atCenterOf(at).add(0,5/16.,0)))return;
                if(input.handleInsertion(box.copy(),Direction.DOWN,false).isEmpty()){PackageAuthorityManager.consumeLight(level,entry);return;}
            }
        }
    }
    /** Create ignites an unlit package, then damages it on subsequent fire contacts. */
    static boolean burnContact(PackageLightStore.Entry entry) {
        if(entry.fireTicks<=0){entry.fireTicks=100;return false;}
        entry.health-=.15f;return entry.health<=.5f;
    }
    static boolean burnTick(PackageLightStore.Entry entry,boolean damageAllowed) {
        if(entry.fireTicks<=0)return false;
        if(damageAllowed&&entry.fireTicks%20==0)entry.health-=.15f;
        entry.fireTicks--;return damageAllowed&&entry.health<=.5f;
    }
    private static void portal(ServerLevel source,PackageLightStore.Entry entry,BlockPos sourcePos,net.minecraft.world.level.block.state.BlockState state) {
        var key=source.dimension()==net.minecraft.world.level.Level.NETHER?net.minecraft.world.level.Level.OVERWORLD:net.minecraft.world.level.Level.NETHER;var target=source.getServer().getLevel(key);if(target==null)return;
        var p=entry.state().pose();double scale=net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(source.dimensionType(),target.dimensionType());var position=target.getWorldBorder().clampToBounds(p.x()*scale,p.y(),p.z()*scale);target.getChunk(position);
        var found=target.getPortalForcer().findClosestPortalPosition(position,key==net.minecraft.world.level.Level.NETHER,target.getWorldBorder());
        if(found.isEmpty()){var created=target.getPortalForcer().createPortal(position,state.getValue(NetherPortalBlock.AXIS));if(created.isEmpty())return;found=java.util.Optional.of(created.get().minCorner);}
        var dest=found.get();var destinationState=target.getBlockState(dest);var axis=destinationState.getValue(NetherPortalBlock.AXIS);var sourceAxis=state.getValue(NetherPortalBlock.AXIS);
        var original=net.minecraft.BlockUtil.getLargestRectangleAround(sourcePos,sourceAxis,21,Direction.Axis.Y,21,at->source.getBlockState(at).equals(state));
        var rectangle=net.minecraft.BlockUtil.getLargestRectangleAround(dest,axis,21,Direction.Axis.Y,21,at->target.getBlockState(at).equals(destinationState));
        var dimensions=net.minecraft.world.entity.EntityDimensions.fixed(entry.width,entry.height);var relative=net.minecraft.world.level.portal.PortalShape.getRelativePosition(original,sourceAxis,entry.position(),dimensions);
        double along=entry.width*.5+(rectangle.axis1Size-entry.width)*relative.x,vertical=(rectangle.axis2Size-entry.height)*relative.y,across=.5+relative.z;
        var positionOut=new Vec3(rectangle.minCorner.getX()+(axis==Direction.Axis.X?along:across),rectangle.minCorner.getY()+vertical,rectangle.minCorner.getZ()+(axis==Direction.Axis.X?across:along));
        positionOut=net.minecraft.world.level.portal.PortalShape.findCollisionFreePosition(positionOut,target,null,dimensions);
        float vx=p.vx(),vz=p.vz(),yaw=p.yaw();if(axis!=sourceAxis){vx=p.vz();vz=-p.vx();yaw+=90;}
        PackageAuthorityManager.transferLight(source,target,entry,new PackageLease.Pose(positionOut.x,positionOut.y,positionOut.z,vx,p.vy(),vz,yaw));
    }
    private static void endPortal(ServerLevel source,PackageLightStore.Entry entry) {
        var key=source.dimension()==net.minecraft.world.level.Level.END?net.minecraft.world.level.Level.OVERWORLD:net.minecraft.world.level.Level.END;var target=source.getServer().getLevel(key);if(target==null)return;
        var pos=key==net.minecraft.world.level.Level.END?ServerLevel.END_SPAWN_POINT:target.getSharedSpawnPos();target.getChunk(pos);
        var p=entry.state().pose();float yaw=p.yaw();if(key==net.minecraft.world.level.Level.END){net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(target,pos.below(),true);yaw=Direction.WEST.toYRot();}else pos=target.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,pos);
        PackageAuthorityManager.transferLight(source,target,entry,new PackageLease.Pose(pos.getX()+.5,pos.getY(),pos.getZ()+.5,p.vx(),p.vy(),p.vz(),yaw));
    }
    public static void destroy(ServerLevel level,PackageLightStore.Entry entry) {
        var stack=entry.box(level).copy();var pos=entry.position();if(!PackageAuthorityManager.consumeLight(level,entry))return;
        net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClientsTrackingChunk(level,new net.minecraft.world.level.ChunkPos(BlockPos.containing(pos)),new com.simibubi.create.content.logistics.box.PackageDestroyPacket(entry.bounds().getCenter(),stack));
        AllSoundEvents.PACKAGE_POP.playOnServer(level,BlockPos.containing(pos));
        var contents=PackageItem.getContents(stack);
        for(int i=0;i<contents.getSlots();i++) {
            var item=contents.getStackInSlot(i).copy();
            if(item.getItem() instanceof SpawnEggItem egg&&egg.getType(item).spawn(level,item,null,BlockPos.containing(pos),MobSpawnType.SPAWN_EGG,false,false)!=null)item.shrink(1);
            if(!item.isEmpty())level.addFreshEntity(new ItemEntity(level,pos.x,pos.y,pos.z,item));
        }
    }
    @SubscribeEvent public static void explode(ExplosionEvent.Detonate event){if(event.getLevel() instanceof ServerLevel level){var pos=event.getExplosion().center();var damage=net.minecraft.world.level.Explosion.getDefaultDamageSource(level,event.getExplosion().getDirectSourceEntity());double radius=event.getExplosion().radius()*2;for(var entry:PackageAuthorityManager.queryLight(level,new AABB(pos,pos).inflate(radius)))if(!entry.data.getBoolean("Invulnerable")&&entry.box(level).getItem().canBeHurtBy(entry.box(level),damage)&&entry.position().distanceToSqr(pos)<=radius*radius&&level.clip(new ClipContext(pos,entry.bounds().getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty())).getType()==HitResult.Type.MISS)destroy(level,entry);}}
    public static boolean arrowHit(net.minecraft.world.entity.projectile.AbstractArrow arrow) {
        if(!(arrow.level() instanceof ServerLevel level))return false;var motion=arrow.getDeltaMovement();if(motion.lengthSqr()<1e-12)return false;
        var from=arrow.position();var end=from.add(motion);double nearest=motion.lengthSqr();PackageLightStore.Entry selected=null;
        var block=level.clip(new ClipContext(from,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,arrow));if(block.getType()!=HitResult.Type.MISS)nearest=from.distanceToSqr(block.getLocation());
        for(var entry:PackageAuthorityManager.queryLight(level,new AABB(from,end).inflate(.3))){var hit=entry.bounds().inflate(.3).clip(from,end).orElse(null);if(hit!=null&&from.distanceToSqr(hit)<nearest){nearest=from.distanceToSqr(hit);selected=entry;}}
        if(selected==null)return false;
        if(arrow.getOwner() instanceof net.minecraft.world.entity.player.Player player&&!player.getAbilities().mayBuild)return false;
        if(selected.data.getBoolean("Invulnerable")||!selected.box(level).getItem().canBeHurtBy(selected.box(level),level.damageSources().arrow(arrow,arrow.getOwner())))return false;
        destroy(level,selected);if(arrow.getPierceLevel()<=0){arrow.discard();return true;}return false;
    }
}

