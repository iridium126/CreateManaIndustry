package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;

/** Typed internal Create bridge. The server may freeze simulation during the bounded final
 * baseline handshake only after PREPARED confirms hidden pool admission. A client may hide
 * simulation/rendering only after exact ACTIVE and visible committed admission both match. */
public interface PackageChainAccess {
    boolean cmi$acquirePackage(ChainConveyorPackage box,BlockPos connection,PackageOwnershipList.Owner<ChainConveyorPackage> owner);
    boolean cmi$restorePackage(ChainConveyorPackage box);
    boolean cmi$ownsPackage(ChainConveyorPackage box);
    void cmi$materializePackage(ChainConveyorPackage box);
    void cmi$materializePackages();
    void cmi$restorePackages();
}
