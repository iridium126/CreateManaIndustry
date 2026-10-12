package com.iridium126.createmanaindustry.compat.hexcasting;

/** Internal preflight failure, translated into the casting engine's own accident. */
public final class ResourceConversionException extends RuntimeException {
    private final String reason;

    public ResourceConversionException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
