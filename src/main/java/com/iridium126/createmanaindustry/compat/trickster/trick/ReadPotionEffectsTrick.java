package com.iridium126.createmanaindustry.compat.trickster.trick;

import com.iridium126.createmanaindustry.compat.trickster.fragment.PotionFragment;

import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.UnknownEntityBlunder;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.type.Signature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/** Ordinary zero-cost entity query, available without Hexcasting. */
public final class ReadPotionEffectsTrick extends Trick<ReadPotionEffectsTrick> {
    public ReadPotionEffectsTrick() {
        super(Pattern.of(1,0,3,6,7,4,1,2,5,8,7),
                Signature.of(FragmentType.ENTITY, ReadPotionEffectsTrick::run, PotionFragment.TYPE.listOfRet()));
    }
    public List<PotionFragment> run(SpellContext context, EntityFragment fragment) {
        var target = fragment.getEntity(context).orElseThrow(UnknownEntityBlunder::new);
        if (!(target instanceof LivingEntity living)) return List.of();
        return living.getActiveEffects().stream()
                .sorted(Comparator.comparing(effect -> BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString()))
                .map(PotionFragment::fromEffect).toList();
    }
}
