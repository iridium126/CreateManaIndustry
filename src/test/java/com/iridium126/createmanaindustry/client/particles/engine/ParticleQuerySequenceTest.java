package com.iridium126.createmanaindustry.client.particles.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ParticleQuerySequenceTest {
    @AfterEach void cancel(){ParticleQuerySequence.INSTANCE.invalidate("test cleanup");ClientFrameCapture.INSTANCE.close();NeighborInputCapture.INSTANCE.close();}
    @Test void boundedNamesAndLength(){
        var sequence=ParticleQuerySequence.INSTANCE;
        assertFalse(sequence.request("../outside",16));assertFalse(sequence.request("valid",15));assertFalse(sequence.request("valid",129));assertFalse(sequence.request("x".repeat(65),16));
    }
    @Test void traceCapturesTheActualBoundaryAndRejectsSourceDrift()throws Exception{
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/createmanaindustry/shaders/particles/chunks/storm_motion.glsl"));
        String traced=ParticleQuerySequence.traceSource(source);
        assertTrue(traced.indexOf("vec4(pos,separationRadius)")>traced.indexOf("cmiColumnConsumeRecovery"));
        assertTrue(traced.contains("if(uR7CaptureEnabled&&r7Member<131072u)"));
        assertThrows(IllegalStateException.class,()->ParticleQuerySequence.traceSource(source.replace("vec3 sepW = vec3(0.0);","vec3 sepW;")));
    }
    @Test void sequenceAndFpsRequestsAreMutuallyExclusiveBeforeAnyGpuAllocation(){
        var sequence=ParticleQuerySequence.INSTANCE;
        assertTrue(sequence.request("moving",16));assertTrue(sequence.busy());assertFalse(ClientFrameCapture.INSTANCE.request(30));assertFalse(NeighborInputCapture.INSTANCE.request("still"));assertFalse(sequence.request("another",128));
        sequence.invalidate("cancel pending");assertFalse(sequence.busy());assertTrue(ClientFrameCapture.INSTANCE.request(30));assertFalse(sequence.request("moving",16));assertFalse(NeighborInputCapture.INSTANCE.request("still"));
    }
}
