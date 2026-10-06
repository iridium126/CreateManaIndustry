package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Run Create's fake-item guard before inventory becomes a durable lightweight record. */
public interface PackageInitialEntityAccess {
    boolean cmi$validInitialEntity();
    java.util.UUID cmi$tossedBy();
    void cmi$tossedBy(java.util.UUID uuid);
}
