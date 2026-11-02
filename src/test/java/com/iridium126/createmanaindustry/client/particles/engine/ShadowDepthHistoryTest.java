package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowDepthHistoryTest {
    private static ShadowDepthHistory.Stamp stamp(long session,long options,String world,int width,long frame,double x,double angle,float projection){
        float[] matrix=new float[16];matrix[0]=projection;
        return new ShadowDepthHistory.Stamp(session,options,world,width,100,frame,x,0,0,(float)Math.sin(Math.toRadians(angle)),0,(float)Math.cos(Math.toRadians(angle)),matrix);
    }
    @Test void identityAndCameraGatesAreIndependent(){
        var h=new ShadowDepthHistory();var old=stamp(1,2,"a",101,5,0,0,1);h.publish(old);
        assertTrue(h.accepts(stamp(1,2,"a",101,6,8,14.99,1)));
        for(var now:new ShadowDepthHistory.Stamp[]{stamp(2,2,"a",101,6,0,0,1),stamp(1,3,"a",101,6,0,0,1),stamp(1,2,"b",101,6,0,0,1),stamp(1,2,"a",102,6,0,0,1),stamp(1,2,"a",101,7,0,0,1),stamp(1,2,"a",101,6,8.01,0,1),stamp(1,2,"a",101,6,0,15.01,1),stamp(1,2,"a",101,6,0,0,2)})assertFalse(h.accepts(now));
        h.invalidate("reload");assertFalse(h.accepts(stamp(1,2,"a",101,6,0,0,1)));assertEquals("reload",h.reason());
    }
}
