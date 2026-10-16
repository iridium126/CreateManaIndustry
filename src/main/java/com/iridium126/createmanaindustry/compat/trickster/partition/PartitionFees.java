package com.iridium126.createmanaindustry.compat.trickster.partition;

import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.fragment.*;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.minecraft.network.chat.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Audited distance-priced native tricks. Validate before their handlers extract items or evaluate exponents. */
public final class PartitionFees {
    private static final Set<String> VECTOR_TRICKS=Set.of("PlaceBlockTrick","ReleaseEntityTrick","SummonLightningTrick","SummonArrowTrick","SummonDragonBreathTrick","SummonFireballTrick","SummonTntTrick","SummonWindChargeTrick");
    private PartitionFees(){}
    public static Vec3 vec(org.joml.Vector3dc v){return new Vec3(v.x(),v.y(),v.z());}
    public static void check(Object trick,SpellContext ctx,List<Fragment> args) {
        if(args.isEmpty()||!PartitionManager.active(ctx.source().getWorld()))return;
        String name=trick.getClass().getSimpleName();Vec3 origin=vec(ctx.source().getPos());
        if(name.equals("SwapBlockTrick")&&args.size()==2&&args.get(0) instanceof VectorFragment a&&args.get(1) instanceof VectorFragment b)require(ctx,vec(a.vector()),vec(b.vector()));
        else if(VECTOR_TRICKS.contains(name)&&args.getFirst() instanceof VectorFragment v)require(ctx,origin,vec(v.vector()));
        else if((name.equals("StoreEntityTrick")||name.equals("PrepareDisplaceEntityTrick"))&&args.getFirst() instanceof EntityFragment e) {
            var entity=e.getEntity(ctx).orElse(null);
            if(entity!=null)require(ctx,name.equals("StoreEntityTrick")?ctx.source().getPlayer().map(p->p.position()).orElse(origin):origin,entity.position());
        }else if(name.equals("DisplaceEntityTrick")&&args.size()==2&&args.getFirst() instanceof DisplaceFragment d&&args.get(1) instanceof VectorFragment v) {
            require(ctx,origin,origin.add(vec(v.vector())));
            var entity=d.getEntity(ctx).orElse(null);
            if(entity!=null&&entity.level()==ctx.source().getWorld())require(ctx,origin,entity.position());
        }
    }
    private static void require(SpellContext ctx,Vec3 from,Vec3 to){if(PartitionManager.crosses(ctx.source().getWorld(),from,to))throw new InfiniteDistanceBlunder();}
    @SuppressWarnings("serial")
    public static final class InfiniteDistanceBlunder extends WrappableBlunder {
        public MutableComponent createMessage(TrickContext ctx){return Component.translatable("createmanaindustry.partition.infinite_cost");}
    }
}
