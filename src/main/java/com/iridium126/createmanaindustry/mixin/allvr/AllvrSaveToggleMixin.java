package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.commands.SaveOnCommand;
import net.minecraft.server.commands.SaveOffCommand;
import net.minecraft.server.level.ServerLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({SaveOnCommand.class, SaveOffCommand.class})
public abstract class AllvrSaveToggleMixin {
    @WrapOperation(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;noSave:Z", opcode = Opcodes.PUTFIELD))
    private static void allvr$saveBarrier(ServerLevel level, boolean noSave, Operation<Void> original) {
        original.call(level, noSave);
        var map = ((AllvrServerLevelDuck) level).allvr$peekCubeMap();
        if (map != null) map.syncSavingState();
    }
}
