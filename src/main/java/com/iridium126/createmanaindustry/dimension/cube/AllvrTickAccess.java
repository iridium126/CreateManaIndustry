package com.iridium126.createmanaindustry.dimension.cube;

import net.minecraft.server.level.ServerLevel;

public interface AllvrTickAccess {
    ServerLevel allvr$tickLevel();
    void allvr$bindTicks(ServerLevel level, boolean fluids);
}
