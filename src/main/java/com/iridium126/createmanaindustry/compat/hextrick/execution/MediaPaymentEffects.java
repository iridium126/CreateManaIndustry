package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.advancements.HexAdvancementTriggers;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.api.mod.HexStatistics;
import at.petrak.hexcasting.common.lib.HexDamageTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/** Invoked only by the transfer transaction's final commit callback. */
public final class MediaPaymentEffects {
    private MediaPaymentEffects() {}

    public static void commit(ServerPlayer player, long extracted, long wasted, long missing, float damage) {
        long overcast = 0;
        if (damage > 0) {
            double before = player.getHealth() * HexConfig.common().mediaToHealthRate();
            Mishap.trulyHurt(player, player.damageSources().source(HexDamageTypes.OVERCAST), damage);
            overcast = Mth.ceil(before - player.getHealth() * HexConfig.common().mediaToHealthRate());
            HexAdvancementTriggers.OVERCAST_TRIGGER.get().trigger(player, (int) overcast);
            player.awardStat(HexStatistics.MEDIA_OVERCAST, (int) overcast);
        }
        long used = extracted + overcast;
        long healthWaste = Math.max(0, overcast - missing);
        wasted = healthWaste > Long.MAX_VALUE - wasted ? Long.MAX_VALUE : wasted + healthWaste;
        player.awardStat(HexStatistics.MEDIA_USED, (int) used);
        HexAdvancementTriggers.SPEND_MEDIA_TRIGGER.get().trigger(player, used, wasted);
    }
}
