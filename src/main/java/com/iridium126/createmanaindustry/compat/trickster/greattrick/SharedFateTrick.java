package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.trickster.blunder.SharedFateBlunder;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ChainState;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.InvalidInputsBlunder;
import dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import java.util.List;

public final class SharedFateTrick extends GreatTrick {
    public SharedFateTrick() { super(GreatPattern.SHARED_FATE, 32000); }

    @Override
    public List<FragmentType<?>> inputTypes() { return List.of(FragmentType.ENTITY); }

    @Override
    public FragmentType<?> outputType() { return FragmentType.ENTITY; }

    @Override
    public Fragment activate(SpellContext context, List<Fragment> arguments) {
        var player = context.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
        if (arguments.size() != 1 || !(arguments.getFirst() instanceof EntityFragment target))
            throw new InvalidInputsBlunder();
        var entity = target.getEntity(context).orElseThrow(() -> new SharedFateBlunder("missing"));
        ChainState state = (ChainState)context.state();
        CastChain chain = CastChains.current() != null ? CastChains.current() : state.cmi$getChain();
        SharedFateManager.bind(player, entity, chain, animaCost());
        state.cmi$markResourceUse();
        ExecutionScope.resourceTransition();
        return target;
    }
}
