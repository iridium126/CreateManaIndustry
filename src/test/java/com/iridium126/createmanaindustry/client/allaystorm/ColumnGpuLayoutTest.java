package com.iridium126.createmanaindustry.client.allaystorm;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class ColumnGpuLayoutTest {
    @Test void arcNodesDoNotOverwriteSnapshotMetadataOrRecoveryScratch(){
        var words=new BitSet();
        for(int node=0;node<ColumnGpuLayout.NODES;node++){
            int offset=ColumnGpuLayout.node(node);
            assertFalse(words.get(offset));assertTrue(offset<ColumnGpuLayout.RECOVER_POS);
            assertFalse(offset>=ColumnGpuLayout.ORIGIN&&offset<48);
            words.set(offset);
        }
        assertEquals(65,words.cardinality());assertEquals(79,ColumnGpuLayout.node(64));
        assertEquals(128,ColumnGpuLayout.SAMPLES);assertEquals(96,ColumnGpuLayout.WORDS);
        assertEquals(ColumnGpuLayout.RECOVER_POS+3,ColumnGpuLayout.RECOVER_VEL);
        assertEquals(ColumnGpuLayout.RECOVER_VEL+3,ColumnGpuLayout.RECOVER_GENERATION);
        assertTrue(ColumnGpuLayout.RECOVER_GENERATION<ColumnGpuLayout.WORDS);
        assertThrows(IndexOutOfBoundsException.class,()->ColumnGpuLayout.node(65));
    }
}
