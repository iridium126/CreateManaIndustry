package com.iridium126.createmanaindustry.client.packages.feedback;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageEnvironmentLayoutTest {
    @Test void fullPopulationStillFitsTheMinimumSsboLimitAndEmptyHistoryIsBounded() {
        var layout=new PackageEnvironmentLayout(131072);
        assertTrue(layout.storageBytes(131072)<=128L*1024*1024);
        assertTrue(layout.storageBytes(0)<16L*1024*1024);
        assertEquals(19L*48,layout.storageBytes(1)-layout.storageBytes(0));
        assertEquals(131071,layout.workspaceSample(131071));
        assertEquals(262143,layout.inlineSample(131071));
    }
    @Test void metadataDoesNotAliasWorkspaceInlineOrPartialWords() {
        for(int n:new int[]{1,11,12,13,31,32,127,128,129,131072}) {
            var layout=new PackageEnvironmentLayout(n);
            assertTrue(layout.inlineSample(n-1)<layout.allocatorSample());
            assertTrue(layout.pendingSamples()*128>=n);
            assertTrue(layout.stackSamples()*12>=n);
            assertEquals(layout.pendingSample()+layout.pendingSamples(),layout.stackSample());
            assertEquals(layout.stackSample()+layout.stackSamples(),layout.pageSample());
            assertThrows(IllegalArgumentException.class,()->layout.storageBytes(n+1));
            assertThrows(IllegalArgumentException.class,()->layout.workspaceSample(n));
        }
    }
}
