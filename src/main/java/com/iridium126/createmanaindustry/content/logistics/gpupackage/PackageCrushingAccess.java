package com.iridium126.createmanaindustry.content.logistics.gpupackage;

public interface PackageCrushingAccess {
    boolean cmi$startPackage(PackageLease.Identity identity);
    boolean cmi$hasPackage();
    boolean cmi$ownsPackage(PackageLease.Identity identity);
}
