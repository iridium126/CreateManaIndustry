package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class HexJitCommandTreeTest {
    @Test void jitCommandsAreUnderCmiHexjit() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(HexJitRuntime.commandTree());
        var jit = dispatcher.getRoot().getChild("cmi").getChild("hexjit");
        assertNotNull(jit.getChild("status"));
        assertNotNull(jit.getChild("clear"));
        assertNull(dispatcher.getRoot().getChild("cmi_hexjit"));
        assertNull(dispatcher.getRoot().getChild("cmi_hex_jit"));
    }
}
