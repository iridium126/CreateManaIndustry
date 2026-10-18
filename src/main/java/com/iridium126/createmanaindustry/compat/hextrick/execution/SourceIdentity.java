package com.iridium126.createmanaindustry.compat.hextrick.execution;

import java.util.UUID;

/** Persisted identity of a source block, independent of its replaceable knot. */
public interface SourceIdentity {
    UUID cmi$sourceIdentity();
    void cmi$newSourceIdentity();
}
