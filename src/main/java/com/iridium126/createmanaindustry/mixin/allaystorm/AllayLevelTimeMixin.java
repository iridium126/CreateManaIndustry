package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.rift.AllayLevelData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.RandomSequences;
import java.util.List;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public abstract class AllayLevelTimeMixin {
    @WrapOperation(method="createLevels",at=@At(value="NEW",target="net/minecraft/server/level/ServerLevel"))
    private ServerLevel cmi$clock(MinecraftServer server,Executor executor,LevelStorageSource.LevelStorageAccess storage,
            ServerLevelData data,ResourceKey<Level> dimension,LevelStem stem,ChunkProgressListener listener,
            boolean debug,long seed,List<CustomSpawner> spawners,boolean tickTime,RandomSequences sequences,Operation<ServerLevel> original) {
        return original.call(server,executor,storage,dimension==AllvrDimensions.ALLAY_LEVEL?new AllayLevelData(server.getWorldData(),data):data,
                dimension,stem,listener,debug,seed,spawners,tickTime,sequences);
    }
}
