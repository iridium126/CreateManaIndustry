package com.iridium126.createmanaindustry.compat.hextrick.conversion;

/** Internal preflight failure, translated into the casting engine's own accident. */
@SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
public final class ResourceConversionException extends RuntimeException {
    private final String reason;

    public ResourceConversionException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
