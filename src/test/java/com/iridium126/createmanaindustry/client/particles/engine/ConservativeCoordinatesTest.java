package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.engine.hardware.ConservativeCoordinates;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeCoordinatesTest {
    @Test void coversExactPointsAndAllTrueNeighbours(){
        var random=new Random(126);
        for(int i=0;i<20000;i++){
            float[] point={(random.nextFloat()-.5f)*60000000,(random.nextFloat()-.5f)*512,(random.nextFloat()-.5f)*60000000};
            int[] cell=new int[3];for(int k=0;k<3;k++){cell[k]=ConservativeCoordinates.quantize(point[k]);assertTrue(ConservativeCoordinates.contains(cell[k],point[k]));}
            float radius=random.nextFloat()*4+.001f;float[] origin=point.clone();origin[0]+=(random.nextFloat()-.5f)*radius;
            assertTrue(ConservativeCoordinates.overlaps(cell,origin,radius));
        }
    }
    @Test void identityMovementAndUnknownInvalidateReuse(){
        int[] cell={0,-1,64};float[] current={.001f,-.001f,1};
        assertTrue(ConservativeCoordinates.reusable(7,7,cell,current));
        assertFalse(ConservativeCoordinates.reusable(7,8,cell,current));
        assertFalse(ConservativeCoordinates.reusable(7,7,cell,new float[]{20,-.001f,1}));
        assertFalse(ConservativeCoordinates.reusable(7,7,cell,new float[]{Float.NaN,0,1}));
        assertThrows(IllegalArgumentException.class,()->ConservativeCoordinates.quantize(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,()->ConservativeCoordinates.quantize(Float.MAX_VALUE));
    }
}
