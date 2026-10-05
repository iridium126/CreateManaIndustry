package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Allocation-lean equivalent of SpellAction.operate for the project's read-only Tick action. */
public final class FastTickAction {
    private static final ClassValue<Boolean> HAS_READ_ONLY_POST_EXECUTION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> declaringClass = type.getMethod("postExecution", CastResult.class).getDeclaringClass();
                // CastingEnvironment only iterates extensions; StaffCastEnv only reads the sound
                // and plays it. Neither implementation mutates or retains the CastResult image.
                return declaringClass == CastingEnvironment.class || declaringClass == StaffCastEnv.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };

    private FastTickAction() {}

    public static boolean enabled() {
        return ServerConfig.hexJitFastTickAction
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.enabled()
                && JitCompatibility.fastAddMotionReady();
    }

    public static boolean supports(Action action) {
        return action == OpTick.INSTANCE && enabled();
    }

    private static boolean mayMutateUserDataInPlace(CastingEnvironment env) {
        return env instanceof CastingEnvironmentObserverAccess access
                && access.cmi$getPostExecutions().isEmpty()
                && HAS_READ_ONLY_POST_EXECUTION.get(env.getClass());
    }

    public static CastResult operate(CastingEnvironment env, CastingImage image,
                                     SpellContinuation continuation, PatternIota cast) {
        OpTick action = OpTick.INSTANCE;
        TreeList<Iota> stack = image.getStack();
        int argc = action.getArgc();
        if (argc > stack.size()) throw new MishapNotEnoughArgs(argc, stack.size());

        Iota target = stack.getLast();
        if (!(target instanceof Vec3Iota vector))
            throw MishapInvalidIota.Companion.ofType(target, 0, "vector");
        BlockPos pos = BlockPos.containing(vector.getVec3());
        TreeList<Iota> stackWithoutArgs = stack.dropRight(argc);
        boolean mutateUserDataInPlace = mayMutateUserDataInPlace(env);
        // Preserve the input image with one copy. After postExecution has observed this result,
        // TickSpell can update that private copy in place when the callback is known to be read-only.
        CompoundTag userData = mutateUserDataInPlace
                ? FastCompoundTagCopy.copyForTick(
                        image.getUserData(), OpTick.TAG_TIMES_TICKED, HexAPI.RAVENMIND_USERDATA)
                : FastCompoundTagCopy.copy(image.getUserData());
        var result = action.executeForFastPath(pos, env, userData, mutateUserDataInPlace);

        boolean useHexOPMediaPool = result.getCost() > 0 && !image.getSimulateNext()
                && FastHexOPMediaPool.begin(env);
        try {
            if (env.extractMedia(result.getCost(), true) > 0)
                throw new MishapNotEnoughMedia(result.getCost());
        } catch (RuntimeException | Error failure) {
            if (useHexOPMediaPool) FastHexOPMediaPool.end(env);
            throw failure;
        }

        List<ParticleSpray> particles = result.getParticles();
        List<OperatorSideEffect> sideEffects = new ArrayList<>(particles.size() + 2);
        if (result.getCost() > 0) sideEffects.add(new OperatorSideEffect.ConsumeMedia(result.getCost()));
        sideEffects.add(new OperatorSideEffect.AttemptSpell(result.getEffect(),
                action.hasCastingSound(env), action.awardsCastingStat(env)));
        ExecutionScope scope = mutateUserDataInPlace ? ExecutionScope.current() : null;
        for (int index = 0; index < particles.size(); index++) {
            ParticleSpray spray = particles.get(index);
            if (scope != null && scope.isDuplicateParticle(spray, env.getPigment())) continue;
            sideEffects.add(new OperatorSideEffect.Particles(spray));
        }

        CastingImage image2 = image.copy(stackWithoutArgs, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + result.getOpCount(), userData);
        var sound = action.hasCastingSound(env) ? HexEvalSounds.SPELL.get() : HexEvalSounds.MUTE.get();
        return new CastResult(cast, continuation, image2, sideEffects, ResolvedPatternType.EVALUATED, sound);
    }
}
