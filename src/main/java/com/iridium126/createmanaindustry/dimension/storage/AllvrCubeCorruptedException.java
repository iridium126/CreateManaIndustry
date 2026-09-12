package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;

/**
 * Thrown when a persisted cube record exists but cannot be trusted (CRC
 * mismatch, schema corruption, coordinate mismatch, unknown format version).
 * <p>
 * Every consumer must fail closed: the cube stays unloaded and the
 * deterministic generator is <b>never</b> allowed to overwrite the record
 * (plan §6/§8.8). Repair is manual — an admin removes or replaces the
 * region record.
 */
public class AllvrCubeCorruptedException extends IOException {

    public AllvrCubeCorruptedException(String message) {
        super(message);
    }

    public AllvrCubeCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
