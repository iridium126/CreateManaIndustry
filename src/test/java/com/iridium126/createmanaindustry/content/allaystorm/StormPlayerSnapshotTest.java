package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.client.allaystorm.StormPlayerGrid;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormPlayerSnapshotTest {
    @Test void eyesReconstructFeetAndCentersForDifferentPoses(){
        var grid=new StormPlayerGrid();
        float[] feet={0,-6.1f,0, 6,0,0, 0,3,0};
        float[] eyes={0,-4.48f,0, 6,.4f,0, 0,6.24f,0};
        float[] centers={0,-5.2f,0, 6,.3f,0, 0,4.8f,0};
        float[] attacks=new float[272];attacks[0]=17;attacks[3]=1;
        grid.build(centers,eyes,feet,new int[]{91,17,31},3,attacks);
        var data=grid.data();int meta=(int)data[13],attack=(int)data[14];
        for(int i=0;i<3;i++){
            assertEquals(eyes[i*3+1],data[i*4+1]);
            assertEquals(feet[i*3+1],data[i*4+1]+data[(meta+i)*4],.00001);
            assertEquals(centers[i*3+1],data[i*4+1]+data[(meta+i)*4+1],.00001);
            assertEquals(new int[]{91,17,31}[i],Float.floatToRawIntBits(data[(meta+i)*4+2]));
        }
        assertEquals(17,data[attack*4]);assertEquals(1,data[attack*4+3]);
    }
    @Test void emptySnapshotClearsOldPlayersAndAttackTargets(){
        var grid=new StormPlayerGrid();float[] target=new float[272];target[3]=1;
        grid.build(new float[3],new float[3],new float[3],new int[]{8},1,target);
        grid.build(new float[0],new float[0],new float[0],new int[0],0,null);
        int attacks=(int)grid.data()[2];assertEquals(0,grid.data()[attacks*4+3]);
        assertTrue(grid.words()>=68*4);
    }
}
