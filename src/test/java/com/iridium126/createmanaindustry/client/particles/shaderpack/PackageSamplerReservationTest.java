package com.iridium126.createmanaindustry.client.particles.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageSamplerReservationTest {
    @Test void nestedCompileAndFailureRestoreNativeSamplerBudget() {
        assertFalse(PackageSamplerReservation.active());
        try(var outer=PackageSamplerReservation.open()) {
            assertTrue(PackageSamplerReservation.active());
            assertThrows(IllegalStateException.class,()->{
                try(var inner=PackageSamplerReservation.open()) {
                    assertTrue(PackageSamplerReservation.active());throw new IllegalStateException("shader failed");
                }
            });
            assertTrue(PackageSamplerReservation.active());
        }
        assertFalse(PackageSamplerReservation.active());
    }
    @Test void nativeCompileOnAnotherThreadDoesNotInheritReservation() throws Exception {
        try(var reserved=PackageSamplerReservation.open()) {
            var result=new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread worker=new Thread(()->result.set(PackageSamplerReservation.active()));worker.start();worker.join();
            assertFalse(result.get());assertTrue(PackageSamplerReservation.active());
        }
        assertFalse(PackageSamplerReservation.active());
    }
}
