package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetSkyMaskPreflightTest {
    @Test void historyMustNotDisappearWhenTheCurrentPortalDoesNotReadIt(){
        var result=TargetSkyMaskPreflight.inspect("complementary",List.of(19,5,4,5),false);
        assertFalse(result.eligible());assertEquals(List.of(4,5,19),result.retainedOutputs());
        assertTrue(result.reason().contains("retained"));
        assertEquals(false,TargetSkyMaskPreflight.metadata(result).get("effective"));
    }
    @Test void unknownConsumersAndUnsupportedPacksFailClosed(){
        assertFalse(TargetSkyMaskPreflight.inspect("other",List.of(),false).eligible());
        assertTrue(TargetSkyMaskPreflight.inspect("complementary",List.of(),true).reason().contains("later"));
        assertTrue(TargetSkyMaskPreflight.inspect("complementary",List.of(),false).reason().contains("not qualified"));
    }
}
