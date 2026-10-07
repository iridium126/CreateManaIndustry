package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CastingEnvironmentObserverAccess;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.SkippablePostExecutionObserver;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class CastingEnvironmentMixin implements CastingEnvironmentObserverAccess {
    private static final ClassValue<Boolean> STANDARD_ACTION_PRECHECK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> costModifierOwner = declaringClass(type, "getCostModifier", ResourceLocation.class);
                return type.getMethod("precheckAction", PatternShapeMatch.class).getDeclaringClass()
                                == CastingEnvironment.class
                        && (costModifierOwner == CastingEnvironment.class
                                || costModifierOwner == PlayerBasedCastEnv.class)
                        && declaringClass(type, "actionKey", PatternShapeMatch.class) == CastingEnvironment.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };
    @Shadow private double costModifier;

    private static Class<?> declaringClass(Class<?> type, String name, Class<?> parameter) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameter).getDeclaringClass();
            } catch (NoSuchMethodException ignored) {
                // Check the next superclass for the inherited implementation.
            }
        }
        return null;
    }

    @WrapMethod(method = "maxOpCount()I")
    private int cmi$cacheMaxOpCount(Operation<Integer> original) throws Throwable {
        if (ServerConfig.hexJitCacheMaxOpCount
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.onServerThread() && HexJitRuntime.enabled()) {
            ExecutionScope scope = ExecutionScope.current();
            if (scope != null) {
                if (scope.hasCachedMaxOpCount()) return scope.cachedMaxOpCount();
                int value = original.call();
                scope.rememberMaxOpCount(value);
                return value;
            }
        }
        return original.call();
    }

    @Override
    @Accessor("postExecutions")
    public abstract java.util.List<CastingEnvironmentComponent.PostExecution> cmi$getPostExecutions();

    @Override
    @Accessor("preMediaExtract")
    public abstract java.util.List<?> cmi$getPreMediaExtract();

    @Override
    @Accessor("postMediaExtract")
    public abstract java.util.List<?> cmi$getPostMediaExtract();

    @Override
    @Accessor("isVecInRanges")
    public abstract java.util.List<?> cmi$getIsVecInRanges();

    @Override
    public double cmi$getCostModifier() { return costModifier; }

    @Override
    public boolean cmi$applyCachedLoopTickPrecheck(PatternShapeMatch.PerWorld match, ExecutionScope scope) {
        if (scope == null || !scope.loopSpecializationEnabled() || !scope.inMetacastingFrame()
                || !ServerConfig.hexJitLoopTickDispatch
                || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO
                || !HexJitRuntime.enabled() || !HexJitRuntime.onServerThread()
                || !JitCompatibility.actionPrechecksReady()
                || !((Object) this instanceof StaffCastEnv)
                || !STANDARD_ACTION_PRECHECK.get(getClass())) return false;
        double cached = scope.cachedLoopTickActionCostModifier(match.key);
        if (Double.isNaN(cached)) return false;
        costModifier = cached;
        return true;
    }

    @WrapMethod(method = "precheckAction")
    private void cmi$cacheActionPrecheck(PatternShapeMatch match, Operation<Void> original) throws Throwable {
        CastingEnvironment env = (CastingEnvironment) (Object) this;
        ResourceKey<ActionRegistryEntry> key = match instanceof PatternShapeMatch.Normal normal
                ? normal.key : match instanceof PatternShapeMatch.PerWorld perWorld ? perWorld.key : null;
        ExecutionScope scope = ExecutionScope.current();
        boolean loopTickPrecheck = key != null && OpTick.ACTION_ID.equals(key.location())
                && ServerConfig.hexJitLoopSpecialization
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && scope != null && scope.inMetacastingFrame();
        if (key != null && (loopTickPrecheck || ServerConfig.hexJitCacheActionPrechecks)
                && env instanceof StaffCastEnv && STANDARD_ACTION_PRECHECK.get(env.getClass())
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.enabled()
                && HexJitRuntime.onServerThread() && JitCompatibility.actionPrechecksReady() && scope != null) {
            double cached = loopTickPrecheck
                    ? scope.cachedLoopTickActionCostModifier(key) : scope.cachedActionCostModifier(key);
            if (!Double.isNaN(cached)) {
                costModifier = cached;
                return;
            }
            original.call(match);
            if (loopTickPrecheck) scope.rememberLoopTickActionCostModifier(key, costModifier);
            else scope.rememberActionCostModifier(key, costModifier);
            return;
        }
        original.call(match);
    }

    @WrapOperation(method = "postExecution", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironmentComponent$PostExecution;" +
                    "onPostExecution(Lat/petrak/hexcasting/api/casting/eval/CastResult;)V"))
    private void cmi$observe(CastingEnvironmentComponent.PostExecution observer, CastResult result,
                             Operation<Void> original) {
        if (ServerConfig.hexJitSkipObservers && observer instanceof SkippablePostExecutionObserver
                && ExecutionScope.maySkip()) return;
        original.call(observer, result);
    }
}
