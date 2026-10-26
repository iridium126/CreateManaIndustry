package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(SubLevelTrackingSystem.class)
public interface RiftSableTrackingAccessor {@Invoker("sendFullSync") void cmi$sync(ServerPlayer player,ServerSubLevel sub,CustomPacketPayload extra);}
