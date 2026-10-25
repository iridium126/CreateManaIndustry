package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileReportFormatTest {
    @Test void sizesUseConsistentHumanReadableUnits(){
        assertEquals("0 B",ProfileReportFormat.bytes(0));assertEquals("1023 B",ProfileReportFormat.bytes(1023));
        assertEquals("1.0 KiB",ProfileReportFormat.bytes(1024));assertEquals("1.5 MiB",ProfileReportFormat.bytes(1572864));
    }
    @Test void summariesKeepTheSlowestStagesAndDetailsWrapWithoutDroppingStages(){
        var stages=new LinkedHashMap<String,Double>();
        stages.put("upload",.1);stages.put("sort",.4);stages.put("draw",.3);stages.put("emit",.2);stages.put("update",1.0);
        assertEquals("update=1.000, sort=0.400 (+3 stages)",ProfileReportFormat.slowest(stages,2));
        assertEquals("update=1.000, sort=0.400, draw=0.300, emit=0.200\n  upload=0.100",ProfileReportFormat.timings(stages));
        assertEquals("pending",ProfileReportFormat.timings(Map.of()));assertEquals("pending",ProfileReportFormat.slowest(Map.of(),3));
    }
    @Test void namedDiagnosticsHideZerosWithoutDumpingArrays(){
        assertEquals("corrected=2.00, distance=1.50",ProfileReportFormat.namedValues(new String[]{"embedded","corrected","distance"},new float[]{0,2,1.5f}));
        assertEquals("",ProfileReportFormat.namedValues(new String[]{"zero"},new float[]{0}));
        assertThrows(IllegalArgumentException.class,()->ProfileReportFormat.namedValues(new String[0],new float[1]));
    }
}
