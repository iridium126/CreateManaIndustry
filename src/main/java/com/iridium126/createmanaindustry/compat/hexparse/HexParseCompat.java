package com.iridium126.createmanaindustry.compat.hexparse;

import at.petrak.hexcasting.api.casting.iota.Iota;
import io.yukkuric.hexparse.api.HexParseAPI;
import io.yukkuric.hexparse.parsers.nbt2str.INbt2Str;
import io.yukkuric.hexparse.parsers.str2nbt.IStr2Nbt;
import net.neoforged.fml.ModList;

/** Installs optional parsers for CMI's custom Hexcasting iotas. */
public final class HexParseCompat {
    public static void register() {
        if (!ModList.get().isLoaded("hexparse")) return;

        if (ModList.get().isLoaded("trickster")) register(TrickIotaHexParseAdapter.INSTANCE, TrickIotaHexParseAdapter.INSTANCE);
        if (ModList.get().isLoaded("yes_steve_model")) {
            register(YsmIotaHexParseAdapter.CUBE, YsmIotaHexParseAdapter.CUBE);
            register(YsmIotaHexParseAdapter.GROUP, YsmIotaHexParseAdapter.GROUP);
        }
    }

    private static <T extends Iota> void register(INbt2Str<T> back, IStr2Nbt forth) {
        HexParseAPI.AddForthParser(forth);
        HexParseAPI.AddBackParser(back);
    }

    private HexParseCompat() {}
}
