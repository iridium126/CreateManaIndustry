package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyRenderScopeTest {
    @Test void signedTargetClockNeverLeaksToIntegratedServerThread()throws Exception {
        var context=new AllaySkyContext(1,-12000,40,.3f,.6f,2,true,.5f,.25f);
        SkyRenderScope.enter(context,10);
        try {
            assertTrue(SkyRenderScope.active());assertEquals(-11997,SkyRenderScope.dayTime());
            assertEquals(.3f,SkyRenderScope.rain());assertEquals(.6f,SkyRenderScope.thunder());
            try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertFalse(executor.submit(SkyRenderScope::active).get());
            }
            assertThrows(IllegalStateException.class,()->SkyRenderScope.enter(context,0));
        } finally {SkyRenderScope.leave();}
        assertFalse(SkyRenderScope.active());
        SkyRenderScope.enter(new AllaySkyContext(1,-12000,40,0,0,2,false,.5f,1),500);
        try{assertEquals(-12000,SkyRenderScope.dayTime());}finally{SkyRenderScope.leave();}
    }
}
