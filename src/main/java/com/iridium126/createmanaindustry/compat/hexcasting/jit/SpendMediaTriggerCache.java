package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.advancements.SpendMediaTrigger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/** Dispatches a cast-local snapshot without re-entering the trigger when no rule can match. */
public final class SpendMediaTriggerCache {
    private SpendMediaTriggerCache() {}

    /**
     * Returns false only when the rules aren't cached and HexCasting's normal trigger must run
     * to build the snapshot. A true result means the exact event has been handled or had no match.
     */
    public static boolean tryDispatchCached(ExecutionScope scope, SpendMediaTrigger trigger,
                                            ServerPlayer player,
                                            long mediaSpent, long mediaWasted) {
        if (scope == null) return false;
        ExecutionScope.SpendMediaListenerRule[] rules =
                scope.cachedSpendMediaListenerRules(trigger, player);
        return rules != null && dispatchKnownRules(player, mediaSpent, mediaWasted, rules);
    }

    /** Use rules already fetched by SpendMediaTriggerMixin so metrics and ordering stay single-pass. */
    public static boolean dispatchKnownRules(ServerPlayer player, long mediaSpent, long mediaWasted,
                                             ExecutionScope.SpendMediaListenerRule[] rules) {
        if (rules.length == 0) return true;
        CriterionTrigger.Listener<?> firstMatch = null;
        List<CriterionTrigger.Listener<?>> multipleMatches = null;
        for (ExecutionScope.SpendMediaListenerRule rule : rules) {
            if (!rule.matches(mediaSpent, mediaWasted)) continue;
            if (firstMatch == null) {
                firstMatch = rule.listener();
            } else {
                if (multipleMatches == null) {
                    multipleMatches = new ArrayList<>(2);
                    multipleMatches.add(firstMatch);
                }
                multipleMatches.add(rule.listener());
            }
        }
        if (firstMatch == null) return true;
        FastHexOPMediaPool.flushDeferredPersonalMediaWrites(player);
        var advancements = player.getAdvancements();
        if (multipleMatches == null) firstMatch.run(advancements);
        else for (CriterionTrigger.Listener<?> listener : multipleMatches) listener.run(advancements);
        return true;
    }
}
