package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialNative;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialNativeContractTest {
    private static final String RECEIPT="""
        {"abi":12,"packet_header_bytes":128,"input_stride":64,"output_stride":64,
         "probes":[2,4],"horizon":0.1,"primitive":"opaque-triangle-box",
         "precompiled":true,"cpu_envelope_readback":false}
        """;
    @Test void acceptsCompleteContractWithoutLoadingCuda(){assertDoesNotThrow(()->SpatialNative.verifyCapabilities(RECEIPT));}
    @Test void rejectsOldOrPartialContracts(){
        for(String receipt:new String[]{"{}",RECEIPT.replace("\"abi\":12","\"abi\":10"),RECEIPT.replace("\"input_stride\":64","\"input_stride\":32"),RECEIPT.replace("[2,4]","[2]"),RECEIPT.replace("\"precompiled\":true","\"precompiled\":false"),RECEIPT.replace("readback\":false","readback\":true")})assertThrows(IllegalStateException.class,()->SpatialNative.verifyCapabilities(receipt));
    }
}
