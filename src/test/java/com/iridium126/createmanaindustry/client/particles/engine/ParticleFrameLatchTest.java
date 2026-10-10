package com.iridium126.createmanaindustry.client.particles.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParticleFrameLatchTest {
    @Test void shadowAndAfterSkyShareOneAttemptAndNextFrameCanAdvance(){var f=new ParticleFrameLatch();assertTrue(f.enter(true));long token=f.token();assertTrue(f.claim());f.complete(true);assertTrue(f.ready());assertFalse(f.claim());f.leave();assertFalse(f.ready());assertTrue(f.enter(true));assertTrue(f.token()>token);assertTrue(f.claim());f.complete(true);f.leave();}
    @Test void failedFrameCannotRetryOrExposePreviousCommit(){var f=new ParticleFrameLatch();f.enter(true);f.claim();f.complete(false);assertFalse(f.claim());assertFalse(f.ready());f.leave();f.enter(true);assertTrue(f.claim());f.complete(true);f.leave();}
    @Test void nestedAndAuxiliaryViewsCannotAdvanceOrReplaceOuterReadiness(){var f=new ParticleFrameLatch();f.enter(true);f.claim();f.complete(true);long token=f.token();assertFalse(f.enter(false));assertFalse(f.claim());assertFalse(f.ready());f.leave();assertEquals(token,f.token());assertTrue(f.ready());f.leave();f.enter(false);assertFalse(f.claim());assertFalse(f.ready());assertEquals(token,f.token());f.leave();}
    @Test void absentShadowUsesAfterSkyOnceAndScopeAlwaysBalances(){var f=new ParticleFrameLatch();f.enter(true);try{assertTrue(f.claim());f.complete(true);}finally{f.leave();}assertFalse(f.active());assertThrows(IllegalStateException.class,f::leave);}
}
