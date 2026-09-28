package com.iridium126.createmanaindustry.content.allaystorm;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class AllayStormCommandTreeTest {
    @Test void stormIsUnderParticleAndRetainsStopAndCountChildren() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(AllayStormCommand.commandTree());
        var root = dispatcher.getRoot().getChild("cmi").getChild("particle");
        var storm = root.getChild("allaystorm");
        assertNotNull(storm);
        assertNotNull(storm.getChild("stop"));
        assertNotNull(storm.getChild("count"));
        assertNull(dispatcher.getRoot().getChild("cmip"));
    }
}
