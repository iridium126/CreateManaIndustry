package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.samsthenerd.inline.api.InlineAPI;

/** Registers the shared Inline data type for YSM geometry iotas. */
public final class YsmInlineBootstrap {
    public static void register() { InlineAPI.INSTANCE.addDataType(InlineYsmGeometryData.TYPE); }
    private YsmInlineBootstrap() {}
}
