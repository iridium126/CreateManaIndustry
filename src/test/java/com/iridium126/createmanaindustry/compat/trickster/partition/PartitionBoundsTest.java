package com.iridium126.createmanaindustry.compat.trickster.partition;

import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PartitionBoundsTest {
    @Test void floorsNegativesAndIncludesBothBlocks(){var b=PartitionBounds.between(-.1,64.9,-.1,1.9,66.1,1.9);assertEquals(-1,b.minX());assertEquals(27,b.blocks());assertEquals(27000,b.cost());}
    @Test void singleBlockAndSwappedEndpoints(){assertEquals(1000,PartitionBounds.between(.1,2.9,.7,.9,2.1,.2).cost());assertEquals(PartitionBounds.between(0,1,2,4,5,6),PartitionBounds.between(4,5,6,0,1,2));}
    @Test void halfOpenMembership(){var b=new PartitionBounds(0,0,0,2,2,2);assertTrue(b.contains(0,0,0));assertTrue(b.contains(2.999,2.999,2.999));assertFalse(b.contains(3,1,1));assertFalse(b.contains(-.001,1,1));assertTrue(b.crosses(0,1,1,3,1,1));assertFalse(b.crosses(-1,1,1,4,1,1));}
    @Test void strictNestingButTouchingIsConflict(){var b=new PartitionBounds(0,0,0,9,9,9);assertFalse(b.conflicts(new PartitionBounds(1,1,1,8,8,8)));assertTrue(b.conflicts(new PartitionBounds(0,1,1,8,8,8)));assertTrue(b.conflicts(new PartitionBounds(10,10,10,11,11,11)));assertTrue(b.conflicts(new PartitionBounds(10,1,1,11,2,2)));assertFalse(b.conflicts(new PartitionBounds(11,0,0,12,9,9)));assertTrue(b.conflicts(b));}
    @Test void rejectsNonfiniteAndCoordinateOverflow(){for(double v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1e20})assertThrows(IllegalArgumentException.class,()->PartitionBounds.between(v,0,0,0,0,0));}
    @Test void volumeAndCostUseCheckedLongArithmetic(){assertThrows(ArithmeticException.class,()->new PartitionBounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE).blocks());assertThrows(ArithmeticException.class,()->new PartitionBounds(0,0,0,9999999,9999999,9999999).cost());}
    @Test void integerEncodingPreservesExpandedY(){var b=new PartitionBounds(-9,-20000000,7,11,20000000,8);assertEquals(b,PartitionBounds.decode(b.encode()));assertThrows(IllegalArgumentException.class,()->PartitionBounds.decode("1,2,3"));}
    @Test void sweptFacesBlockInsideAndOutside(){var b=new PartitionBounds(0,0,0,9,9,9);assertEquals(.005,b.contact(0,new double[]{100,0,0},new double[]{-1,1,1},new double[]{-.5,2,2}),1e-12);assertEquals(.005,b.contact(0,new double[]{-100,0,0},new double[]{.5,1,1},new double[]{1,2,2}),1e-12);assertEquals(.005,b.contact(0,new double[]{100,0,0},new double[]{9,1,1},new double[]{9.5,2,2}),1e-12);assertEquals(Double.POSITIVE_INFINITY,b.contact(0,new double[]{100,0,0},new double[]{-1,10,1},new double[]{-.5,11,2}));}
    @Test void touchingDoesNotBecomeStraddling(){var b=new PartitionBounds(0,0,0,9,9,9);assertFalse(b.straddles(new double[]{-1,1,1},new double[]{0,2,2}));assertTrue(b.straddles(new double[]{-.1,1,1},new double[]{.1,2,2}));assertFalse(b.straddles(new double[]{1,1,1},new double[]{2,2,2}));}
    @Test void diagonalSweepDetectsFiniteFaceBeforeBothEndpointsPassOutside(){var b=new PartitionBounds(0,0,0,9,9,9);double[] low={-2,1,-2},high={-1,2,-1},move={100,0,100};assertEquals(.01,b.contact(0,move,low,high),1e-9);assertEquals(.01,b.contact(2,move,low,high),1e-9);assertEquals(Double.POSITIVE_INFINITY,b.contact(1,move,low,high));}
    @Test void partitionPatternIsUniqueAndDirectionIndependent(){var p=GreatPattern.PARTITION;assertEquals(16,p.edges().size());assertNotEquals(GreatPattern.INVERSION,p);assertNotEquals(GreatPattern.SHARED_FATE,p);assertNotEquals(GreatPattern.TINCTURE_SHAPING,p);assertEquals(p,GreatPattern.fromPath(List.of(18,0,7,16,18,11,2,0)));assertEquals(p,GreatPattern.fromBytes(p.bytes()));}
}
