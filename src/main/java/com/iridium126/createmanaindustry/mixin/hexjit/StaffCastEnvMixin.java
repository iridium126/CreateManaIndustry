package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.TickPostExecutionAccess;
import org.spongepowered.asm.mixin.injection.At;

/** Omits the overcast advancement lookup only when HexOP's pool covers the complete request. */
@Mixin(value = StaffCastEnv.class, remap = false)
public abstract class StaffCastEnvMixin implements TickPostExecutionAccess {
    @Shadow private int soundsPlayed;
    @Override @Unique public boolean cmi$canCollapseQuotedCallbacks(EvalSound sound) {
        if (!cmi$hasPureRangeAttributes()) return false;
        if (soundsPlayed >= 100 || sound.sound() == null) return true;
        var scope = com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.current();
        var pos = cmi$getTickCaster().position();
        return scope != null && scope.canSkipStaffSound(((CastingEnvironment) (Object) this).getWorld(), null,
                sound.sound(), SoundSource.PLAYERS, pos.x, pos.y, pos.z, 1f, 1f);
    }

    @Override @Unique public void cmi$recordCollapsedQuotedCallbacks(EvalSound sound, int count) {
        if (sound.sound() == null) return;
        int skipped = Math.min(count, Math.max(0, 100 - soundsPlayed));
        soundsPlayed += skipped;
        var scope = com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.current();
        if (scope != null) scope.recordSkippedSounds(skipped);
    }

    @Override @Unique public void cmi$postSuccessfulTick(HexPattern pattern, EvalSound evalSound) {
        cmi$refreshTickRangeAttributes();
        if (pattern != null) cmi$recordTickPattern(pattern);
        var sound = evalSound.sound();
        if (soundsPlayed < 100 && sound != null) {
            var pos = cmi$getTickCaster().position();
            var world = ((CastingEnvironment) (Object) this).getWorld();
            var scope = ExecutionScope.current();
            if (scope != null && scope.canSkipStaffSound(world, null, sound, SoundSource.PLAYERS,
                    pos.x, pos.y, pos.z, 1f, 1f)) scope.recordSkippedSounds(1);
            else {
                world.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, 1f, 1f);
                if (scope != null) scope.rememberStaffSound(world, null, sound, SoundSource.PLAYERS,
                        pos.x, pos.y, pos.z, 1f, 1f);
            }
            soundsPlayed++;
        }
    }
    @WrapOperation(method = "postExecution", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void cmi$coalesceStaffSound(ServerLevel world, Player excluded, double x, double y, double z,
                                        SoundEvent sound, SoundSource source, float volume, float pitch,
                                        Operation<Void> original) {
        var scope = ExecutionScope.current();
        if (scope != null && scope.canSkipStaffSound(world, excluded, sound, source, x, y, z, volume, pitch)) {
            scope.recordSkippedSounds(1);
            return;
        }
        original.call(world, excluded, x, y, z, sound, source, volume, pitch);
        if (scope != null) scope.rememberStaffSound(world, excluded, sound, source, x, y, z, volume, pitch);
    }
    @WrapOperation(method = "extractMediaEnvironment(JZ)J", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/env/StaffCastEnv;canOvercast()Z"))
    private boolean cmi$skipOvercastCheckWhenCovered(StaffCastEnv env, Operation<Boolean> original,
                                                     @Local(argsOnly = true) long cost) {
        return FastHexOPMediaPool.skipOvercastCheckWhenCovered((CastingEnvironment) env, cost)
                || original.call(env);
    }
}
