package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.samsthenerd.inline.api.InlineAPI;

/** Registers common Inline data only when Inline is loaded. */
public final class YsmInlineBootstrap {
    public static void register() { InlineAPI.INSTANCE.addDataType(InlineYsmGeometryData.TYPE); }
    private YsmInlineBootstrap() {}
}
