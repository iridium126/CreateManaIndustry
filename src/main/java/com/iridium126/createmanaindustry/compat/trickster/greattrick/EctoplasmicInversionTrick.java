package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ChainState;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.InvalidInputsBlunder;
import dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder;
import dev.enjarai.trickster.spell.fragment.BooleanFragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import java.util.List;

public final class EctoplasmicInversionTrick extends GreatTrick {
    public EctoplasmicInversionTrick() { super(GreatPattern.INVERSION, 4000); }

    @Override
    public FragmentType<?> outputType() { return FragmentType.BOOLEAN; }

    @Override
    public Fragment activate(SpellContext context, List<Fragment> arguments) {
        var player = context.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
        if (!arguments.isEmpty()) throw new InvalidInputsBlunder();
        if (!BatteryAnima.consume(player, animaCost())) throw new GreatTricks.AnimaBlunder();
        ChainState state = (ChainState)context.state();
        CastChain chain = CastChains.current() != null ? CastChains.current() : state.cmi$getChain();
        state.cmi$setChain(chain);
        state.cmi$markResourceUse();
        ExecutionScope.resourceTransition();
        chain.toggle();
        SharedFateManager.refresh(player.getServer());
        HexJitRuntime.invalidate("ectoplasmic inversion toggled");
        return BooleanFragment.of(chain.inverted());
    }
}
