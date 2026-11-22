package com.iridium126.createmanaindustry.client.xessfg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XessFgAdmissionTest {
    @Test
    void nativeSnapshotUsesQueueFlagsAndRetirementProgressIndependently() {
        var admission = new XessFgAdmission();
        assertEquals(XessFgAdmission.NONE, admission.update(80L << 3));
        assertEquals(XessFgAdmission.STALE_PRESENT, admission.update((80L << 3) | 2));
        for (long retired = 81; retired < 88; ++retired)
            assertEquals(XessFgAdmission.STALE_PRESENT, admission.update(retired << 3));
        assertEquals(XessFgAdmission.NONE, admission.update(88L << 3));
    }

    @Test
    void requiresSustainedTransferPressure() {
        var admission = new XessFgAdmission();
        assertEquals(XessFgAdmission.NONE, admission.update(true, false, false, 1));
        assertEquals(XessFgAdmission.TRANSFER_PRESSURE, admission.update(true, false, false, 2));
    }

    @Test
    void reportsStalePresentationAndRepeatedPresentRejection() {
        var stale = new XessFgAdmission();
        assertEquals(XessFgAdmission.STALE_PRESENT, stale.update(false, true, false, 1));

        var rejected = new XessFgAdmission();
        assertEquals(XessFgAdmission.NONE, rejected.update(false, false, true, 1));
        assertEquals(XessFgAdmission.PRESENT_PRESSURE, rejected.update(false, false, true, 2));
    }

    @Test
    void resumesAfterEightAdvancingFrames() {
        var admission = new XessFgAdmission();
        admission.update(true, false, false, 0);
        admission.update(true, false, false, 0);
        assertTrue(admission.pending());

        for (long progress = 1; progress < 8; ++progress)
            assertEquals(XessFgAdmission.TRANSFER_PRESSURE, admission.update(false, false, false, progress));
        assertEquals(XessFgAdmission.NONE, admission.update(false, false, false, 8));
        assertFalse(admission.pending());
    }

    @Test
    void repeatedProgressDoesNotRestoreGeneration() {
        var admission = new XessFgAdmission();
        admission.update(true, false, false, 0);
        admission.update(true, false, false, 0);

        for (int i = 0; i < 20; ++i)
            assertEquals(XessFgAdmission.TRANSFER_PRESSURE, admission.update(false, false, false, 1));
        assertTrue(admission.pending());
    }

    @Test
    void renewedPressureResetsRecoveryProgress() {
        var admission = new XessFgAdmission();
        admission.update(true, false, false, 0);
        admission.update(true, false, false, 0);
        for (long progress = 1; progress <= 4; ++progress)
            admission.update(false, false, false, progress);

        assertEquals(XessFgAdmission.TRANSFER_PRESSURE, admission.update(false, false, true, 5));
        for (long progress = 6; progress < 13; ++progress)
            assertEquals(XessFgAdmission.TRANSFER_PRESSURE, admission.update(false, false, false, progress));
        assertEquals(XessFgAdmission.NONE, admission.update(false, false, false, 13));
    }

    @Test
    void ineligibleFrameClearsAdmission() {
        var admission = new XessFgAdmission();
        admission.update(true, false, false, 0);
        admission.update(true, false, false, 0);
        assertEquals(XessFgAdmission.NONE, admission.reset());
        assertFalse(admission.pending());
    }
}
