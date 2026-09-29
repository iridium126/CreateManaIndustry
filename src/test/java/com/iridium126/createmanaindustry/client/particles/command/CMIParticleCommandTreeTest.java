package com.iridium126.createmanaindustry.client.particles.command;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class CMIParticleCommandTreeTest {
    private CommandDispatcher<CommandSourceStack> dispatcher() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(CMIParticleCommand.commandTree());
        return dispatcher;
    }

    @Test void unifiedEmitGrammarAndRanges() {
        var dispatcher = dispatcher();
        accepts(dispatcher, "cmi particle emit mana_spark 1");
        accepts(dispatcher, "cmi particle emit mana_spark 1000000 0.1");
        accepts(dispatcher, "cmi particle emit mana_spark 1000000 3600");
        accepts(dispatcher, "cmi particle emit mana_spark 1000000 forever");
        rejects(dispatcher, "cmi particle emit mana_spark 0");
        rejects(dispatcher, "cmi particle emit mana_spark 4000001");
        rejects(dispatcher, "cmi particle emit mana_spark 1 0");
        rejects(dispatcher, "cmi particle emit mana_spark 1 3600.1");
        rejects(dispatcher, "cmi particle emit mana_spark");
    }

    @Test void oldAndRemovedParticleCommandsAreAbsent() {
        var dispatcher = dispatcher();
        assertNull(dispatcher.getRoot().getChild("cmip"));
        var particle = dispatcher.getRoot().getChild("cmi").getChild("particle");
        assertNotNull(particle.getChild("emit"));
        assertNotNull(particle.getChild("anim"));
        assertNotNull(particle.getChild("clear"));
        assertNotNull(particle.getChild("stats"));
        assertNotNull(particle.getChild("profile"));
        assertNotNull(particle.getChild("budget"));
        assertNotNull(particle.getChild("shaderpack"));
        assertNull(particle.getChild("spawn"));
        assertNull(particle.getChild("stream"));
        assertNull(particle.getChild("spray"));
        assertNull(particle.getChild("bench"));
    }
    @Test void packagePreviewAndCollisionPreparationCommandsParse() {
        var dispatcher=dispatcher();
        accepts(dispatcher,"cmi particle packagepreview 0");accepts(dispatcher,"cmi particle packagepreview 131072");
        rejects(dispatcher,"cmi particle packagepreview 131073");
        accepts(dispatcher,"cmi particle packagecollision");accepts(dispatcher,"cmi particle packagecollision capture");
        accepts(dispatcher,"cmi particle packagecollision clear");rejects(dispatcher,"cmi particle packagecollision capture 131072");
    }

    @Test void amountLimitsDependOnEmissionSourceAndDuration() {
        assertNull(CMIParticleCommand.amountValidationError(false, false, 1));
        assertNull(CMIParticleCommand.amountValidationError(false, false, 4_000_000));
        assertNotNull(CMIParticleCommand.amountValidationError(false, false, 4_000_001));
        assertNull(CMIParticleCommand.amountValidationError(false, true, 1));
        assertNull(CMIParticleCommand.amountValidationError(false, true, 1_000_000));
        assertNotNull(CMIParticleCommand.amountValidationError(false, true, 1_000_001));
        assertNull(CMIParticleCommand.amountValidationError(true, false, 1));
        assertNull(CMIParticleCommand.amountValidationError(true, false, 2_000));
        assertNotNull(CMIParticleCommand.amountValidationError(true, false, 2_001));
        assertNotNull(CMIParticleCommand.amountValidationError(true, true, 1));
    }

    @Test void durationRejectsNonFiniteAndOutOfRangeValues() {
        assertNull(CMIParticleCommand.durationValidationError(true, 0.1f));
        assertNull(CMIParticleCommand.durationValidationError(true, 3600f));
        assertNull(CMIParticleCommand.durationValidationError(true, -1f));
        assertNotNull(CMIParticleCommand.durationValidationError(true, Float.NaN));
        assertNotNull(CMIParticleCommand.durationValidationError(true, Float.POSITIVE_INFINITY));
        assertNotNull(CMIParticleCommand.durationValidationError(true, 0f));
        assertNotNull(CMIParticleCommand.durationValidationError(true, 3600.1f));
        assertNull(CMIParticleCommand.durationValidationError(false, Float.NaN));
    }

    private static void accepts(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
        var parsed = dispatcher.parse(command, null);
        assertTrue(parsed.getExceptions().isEmpty(), () -> command + " -> " + parsed.getExceptions());
        assertFalse(parsed.getReader().canRead(), () -> "Unparsed input: " + parsed.getReader().getRemaining());
    }

    private static void rejects(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
        var parsed = dispatcher.parse(command, null);
        assertTrue(!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()
                || parsed.getContext().getCommand() == null, command);
    }
}
