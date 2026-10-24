package com.iridium126.createmanaindustry.compat.trickster.casting;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CastChainTest {
    @Test void continuationOwnersKeepScopeLiveUntilLastBranchEnds() {
        var chain=new CastChain();Object root=new Object(),continuation=new Object();
        chain.retain(root);chain.retain(continuation);chain.toggle();
        chain.release(root);assertTrue(chain.live());assertTrue(chain.inverted());
        chain.toggle();assertFalse(chain.inverted());chain.release(continuation);assertFalse(chain.live());
    }
    @Test void savedStateIsSuspendedUntilAnExecutorActuallyResumes() {
        var chain=new CastChain();chain.bind(UUID.randomUUID());chain.toggle();chain.retain(new Object());
        var saved=CastChain.restore(chain.snapshot());
        assertEquals(chain.id(),saved.id());assertEquals(chain.player(),saved.player());assertTrue(saved.inverted());assertFalse(saved.live());
        assertThrows(IllegalStateException.class,()->saved.bind(UUID.randomUUID()));
    }
    @Test void independentlyCreatedChainsNeverShareSwitches() {
        var first=new CastChain();var second=new CastChain();first.toggle();
        assertTrue(first.inverted());assertFalse(second.inverted());assertNotEquals(first.id(),second.id());
    }
}
