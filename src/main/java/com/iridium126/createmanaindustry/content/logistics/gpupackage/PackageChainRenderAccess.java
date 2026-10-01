package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Typed rendering bridge. Publish on the world thread, read immutable membership on renderer
 * or Flywheel worker threads. A null snapshot means the unmodified native lists are in use. */
public interface PackageChainRenderAccess {
    PackageChainContainers.RenderSnapshot cmi$renderPackages();
    void cmi$publishRenderPackages();
}
