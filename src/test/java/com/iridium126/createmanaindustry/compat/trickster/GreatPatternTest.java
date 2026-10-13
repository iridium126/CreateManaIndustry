package com.iridium126.createmanaindustry.compat.trickster;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreatPatternTest {
    @Test void latticeHasNineteenUniqueRadiusTwoDots() {
        var dots=new HashSet<String>(); int[] rows=new int[5];
        for(int i=0;i<19;i++) {
            int q=GreatPattern.Q[i],r=GreatPattern.R[i];
            assertTrue(Math.max(Math.max(Math.abs(q),Math.abs(r)),Math.abs(q+r))<=2);
            dots.add(q+":"+r);rows[r+2]++;
        }
        assertEquals(19,dots.size());assertArrayEquals(new int[]{3,4,5,4,3},rows);
    }
    @Test void crossingAddsAllIntermediateDots() {
        assertEquals(List.of(8,9,10,11),GreatPattern.between(7,11));
        assertEquals(List.of(4,9,14,18),GreatPattern.between(0,18));
        assertEquals(GreatPattern.fromPath(List.of(7,8,9,10,11)),GreatPattern.fromPath(List.of(7,11)));
    }
    @Test void reverseAndDifferentStartIdentifySameInversion() {
        var path=new ArrayList<>(List.of(0,4,9,14,18,15,11,10,9,8,7,3,0));
        Collections.reverse(path);assertEquals(GreatPattern.INVERSION,GreatPattern.fromPath(path));
        assertEquals(GreatPattern.INVERSION,GreatPattern.fromPath(List.of(9,14,18,15,11,10,9,8,7,3,0,4,9)));
        assertEquals(GreatPattern.INVERSION,GreatPattern.fromBytes(GreatPattern.INVERSION.bytes()));
    }
    @Test void backtrackingErasesAndOtherRepeatedEdgesAreRejected() {
        assertEquals(List.of(7),GreatPattern.move(List.of(7,8,9,10,11),7));
        var path=List.of(9,10,5,9);
        assertSame(path,GreatPattern.move(path,10));
        assertThrows(IllegalArgumentException.class,()->GreatPattern.fromBytes(new byte[]{0,19}));
        assertThrows(IllegalArgumentException.class,()->GreatPattern.fromBytes(new byte[]{7,11}));
        assertThrows(IllegalArgumentException.class,()->GreatPattern.fromBytes(new byte[]{0}));
    }
}
