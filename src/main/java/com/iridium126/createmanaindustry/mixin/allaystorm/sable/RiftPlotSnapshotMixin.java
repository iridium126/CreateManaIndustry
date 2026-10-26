package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftPlotSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.ryanhcode.sable.sublevel.plot.*;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
@Mixin(ServerLevelPlot.class)
public abstract class RiftPlotSnapshotMixin {
    @WrapOperation(method="save",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/sublevel/plot/ServerLevelPlot;getLoadedChunks()Ljava/util/Collection;"))
    private Collection<PlotChunkHolder> cmi$oneChunk(ServerLevelPlot plot,Operation<Collection<PlotChunkHolder>> original){return RiftPlotSnapshot.chunks(original.call(plot));}
    @WrapOperation(method="save",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getGameTime()J"))
    private long cmi$pausedClock(ServerLevel level,Operation<Long> original,@Local PlotChunkHolder chunk){return RiftSableFreeze.serializationTime(((ServerLevelPlot)(Object)this).getSubLevel(),chunk.getPos(),original.call(level));}
}
