package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.exception.blunder.*;
import dev.enjarai.trickster.spell.fragment.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

public final class TinctureShapingTrick extends GreatTrick {
    public TinctureShapingTrick() { super(GreatPattern.TINCTURE_SHAPING, 32000); }
    public List<FragmentType<?>> inputTypes() { return List.of(FragmentType.ENTITY, PotionFragment.TYPE); }
    public FragmentType<?> outputType() { return FragmentType.ENTITY; }
    public Fragment activate(SpellContext context, List<Fragment> arguments) {
        var player = context.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
        if (arguments.size() != 2 || !(arguments.get(0) instanceof EntityFragment target)
                || !(arguments.get(1) instanceof PotionFragment potion)) throw new InvalidInputsBlunder();
        var entity = target.getEntity(context).orElseThrow(() -> new PotionBlunder("missing"));
        if (!(entity instanceof LivingEntity living) || !entity.isAlive()) throw new PotionBlunder("not_living");
        var state = (ChainState)context.state();
        var chain = CastChains.current() == null ? state.cmi$getChain() : CastChains.current();
        try (var ignored = CastChains.enter(chain)) {
            // Native entity range includes the configured player true-name exemption.
            if (!new StaffCastEnv(player, InteractionHand.MAIN_HAND).isEntityInRange(entity))
                throw new PotionBlunder("range");
        }
        var effect = potion.toEffect();
        var original = living.getEffect(effect.getEffect());
        PotionFragment before = original == null ? null : PotionFragment.fromEffect(original);
        try (var receipt = BatteryAnima.reserve(player, animaCost()).orElseThrow(() -> new GreatTricks.AnimaBlunder(animaCost()))) {
            // Do not interpret false as unchanged: vanilla can alter only the hidden effect chain.
            living.addEffect(effect, player);
            var applied = living.getEffect(effect.getEffect());
            if (applied == null || Objects.equals(before, PotionFragment.fromEffect(applied)))
                throw new PotionBlunder("unchanged");
            receipt.commit();
        }
        state.cmi$markResourceUse();
        ExecutionScope.resourceTransition();
        return target;
    }
}
