package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormMemberReturnsTest {
    @Test void penaltyDecaysCapsAndClears(){
        var returns=new StormMemberReturns();var home=new Vec3(0,64,0);
        assertEquals(0,returns.penalty(3,1000,home));
        returns.released(3,1000,new Vec3(50,64,0),4);
        assertEquals(81,returns.penalty(3,1000,home),1e-9);
        assertEquals(43.5,returns.penalty(3,1100,home),1e-9);
        assertEquals(0,returns.penalty(3,1220,home),1e-9);
        returns.released(4,1000,new Vec3(500,64,0),0);
        assertEquals(96,returns.penalty(4,1000,home),1e-9);
        returns.dead(4);assertEquals(0,returns.penalty(4,1000,home));
        returns.clear();assertEquals(0,returns.penalty(3,1000,home));
    }
    @Test void nonfiniteAndOutOfRangeReleasesAreIgnored(){
        var returns=new StormMemberReturns();
        returns.released(-1,100,new Vec3(1,2,3),0);
        returns.released(5,100,new Vec3(Double.NaN,2,3),0);
        returns.released(StormAttackMembers.MAX_MEMBERS,100,Vec3.ZERO,0);
        assertEquals(0,returns.penalty(-1,100,Vec3.ZERO));
        assertEquals(0,returns.penalty(5,100,Vec3.ZERO));
        assertEquals(0,returns.penalty(StormAttackMembers.MAX_MEMBERS,100,Vec3.ZERO));
    }
}