package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.fragment.*;
import dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class PartitionTrick extends GreatTrick {
    public PartitionTrick(){super(GreatPattern.PARTITION,0);}
    public List<FragmentType<?>> inputTypes(){return List.of(FragmentType.VECTOR,FragmentType.VECTOR);}
    public FragmentType<?> outputType(){return FragmentType.BOOLEAN;}
    public Component costDescription(){return Component.translatable("createmanaindustry.partition.cost");}
    private static PartitionBounds bounds(VectorFragment a,VectorFragment b){return PartitionBounds.between(a.vector().x(),a.vector().y(),a.vector().z(),b.vector().x(),b.vector().y(),b.vector().z());}
    public OptionalLong estimateAnimaCost(List<Fragment> args) {
        if(args.size()!=2||!(args.get(0) instanceof VectorFragment a)||!(args.get(1) instanceof VectorFragment b))return OptionalLong.empty();
        try{return OptionalLong.of(bounds(a,b).cost());}catch(IllegalArgumentException|ArithmeticException invalid){return OptionalLong.empty();}
    }
    private static Vec3 center(VectorFragment v){return new Vec3(Math.floor(v.vector().x())+.5,Math.floor(v.vector().y())+.5,Math.floor(v.vector().z())+.5);}
    public Fragment activate(SpellContext ctx,List<Fragment> args) {
        var player=ctx.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
        if(args.size()!=2||!(args.get(0) instanceof VectorFragment a)||!(args.get(1) instanceof VectorFragment b))return BooleanFragment.FALSE;
        var state=(ChainState)ctx.state();CastChain chain=CastChains.current()==null?state.cmi$getChain():CastChains.current();
        PartitionBounds bounds;
        try{bounds=bounds(a,b);bounds.cost();}catch(IllegalArgumentException|ArithmeticException invalid){return BooleanFragment.FALSE;}
        if(!PartitionManager.create(player,chain,bounds,center(a),center(b)))return BooleanFragment.FALSE;
        state.cmi$setChain(chain);state.cmi$markResourceUse();
        com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.resourceTransition();
        return BooleanFragment.TRUE;
    }
}
