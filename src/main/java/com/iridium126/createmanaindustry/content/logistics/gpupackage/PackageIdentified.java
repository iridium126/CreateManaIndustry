package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Internal storage bridge for Create chain packages; unrelated to Create's transient netId. */
public interface PackageIdentified {
    long cmi$packageId();
    long cmi$packageGeneration();
    void cmi$packageIdentity(long id,long generation);
}
