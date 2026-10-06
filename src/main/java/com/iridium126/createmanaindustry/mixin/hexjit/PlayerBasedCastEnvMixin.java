package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import at.petrak.hexcasting.common.lib.HexAttributes;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.TickPostExecutionAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.LivingEntity;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.*;
import org.spongepowered.asm.mixin.injection.At;

/** Skips inventory discovery only when HexOP's first-priority pool alone covers this extraction. */
@Mixin(value = PlayerBasedCastEnv.class, remap = false)
public abstract class PlayerBasedCastEnvMixin implements TickPostExecutionAccess {
    @Shadow private double ambitRadius;
    @Shadow private double sentinelRadius;
    @Shadow @Final protected ServerPlayer caster;
    @Unique private static Holder<Attribute> cmi$ambitAttribute;
    @Unique private static Holder<Attribute> cmi$sentinelAttribute;
    @Unique private int cmi$rangeMapSize;
    @Unique private AttributeInstance cmi$ambitInstance;
    @Unique private AttributeInstance cmi$sentinelInstance;
    @Unique private boolean cmi$rangeReaderChecked;
    @Unique private boolean cmi$directRangeReader;
    @Unique private java.util.Map<Holder<Attribute>, AttributeInstance> cmi$rangeInstances;
    @Unique private java.util.Map<Holder<Attribute>, AttributeInstance> cmi$rangeDefaults;
    @Unique private static final ClassValue<Boolean> cmi$standardAttributeReader = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getAttributeValue", Holder.class).getDeclaringClass() == LivingEntity.class
                        && type.getMethod("getAttributes").getDeclaringClass() == LivingEntity.class;
            } catch (ReflectiveOperationException ignored) { return false; }
        }
    };

    @Override @Unique public ServerPlayer cmi$getTickCaster() { return caster; }
    @Override @Unique public boolean cmi$hasPureRangeAttributes() {
        cmi$resolveAttributeHolders();
        if (!cmi$rangeReaderChecked) cmi$initializeRangeReader();
        if (!cmi$directRangeReader || !JitCompatibility.rangeAttributesReady()) return false;
        if (cmi$rangeMapSize != cmi$rangeInstances.size()) cmi$resolveRangeInstances();
        return cmi$ambitInstance != null && cmi$sentinelInstance != null
                && cmi$ambitInstance.getClass() == AttributeInstance.class && cmi$sentinelInstance.getClass() == AttributeInstance.class
                && !((RangeAttributeInstanceAccess) cmi$ambitInstance).cmi$isRangeAttributeDirty()
                && !((RangeAttributeInstanceAccess) cmi$sentinelInstance).cmi$isRangeAttributeDirty()
                && ambitRadius == cmi$ambitInstance.getValue() && sentinelRadius == cmi$sentinelInstance.getValue();
    }

    @Unique private static void cmi$resolveAttributeHolders() {
        if (cmi$ambitAttribute == null) {
            cmi$ambitAttribute = HexAttributes.AMBIT_RADIUS.getDelegate();
            cmi$sentinelAttribute = HexAttributes.SENTINEL_RADIUS.getDelegate();
        }
    }

    @Override @Unique public void cmi$refreshTickRangeAttributes() {
        if (caster != null) {
            // Resolve the registry holders once; DeferredHolder hashes/equals delegate on every lookup.
            cmi$resolveAttributeHolders();
            double ambit;
            double sentinel;
            if (!cmi$rangeReaderChecked) cmi$initializeRangeReader();
            boolean direct = cmi$directRangeReader && JitCompatibility.rangeAttributesReady();
            if (direct) {
                // Verified AttributeMap only adds entries via computeIfAbsent; existing instances
                // are mutated in place. Size therefore detects creation of a formerly default attribute.
                if (cmi$rangeMapSize != cmi$rangeInstances.size()) cmi$resolveRangeInstances();
            }
            ambit = direct && cmi$ambitInstance != null ? cmi$ambitInstance.getValue() : caster.getAttributeValue(cmi$ambitAttribute);
            sentinel = direct && cmi$sentinelInstance != null ? cmi$sentinelInstance.getValue() : caster.getAttributeValue(cmi$sentinelAttribute);
            if (ambitRadius != ambit || sentinelRadius != sentinel) {
                var scope = com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.currentOnServerThread();
                if (scope != null) scope.invalidateTickRangeCheck();
                ambitRadius = ambit;
                sentinelRadius = sentinel;
            }
        }
    }

    @Unique private void cmi$initializeRangeReader() {
        cmi$rangeReaderChecked = true;
        AttributeMap attributes = caster.getAttributes();
        cmi$directRangeReader = JitCompatibility.rangeAttributesReady() && cmi$standardAttributeReader.get(caster.getClass())
                && attributes.getClass() == AttributeMap.class && attributes instanceof RangeAttributeMapAccess access
                && access.cmi$getRangeAttributeSupplier().getClass() == AttributeSupplier.class;
        if (cmi$directRangeReader) {
            var access = (RangeAttributeMapAccess) attributes;
            cmi$rangeInstances = access.cmi$getRangeAttributeInstances();
            cmi$rangeDefaults = ((RangeAttributeSupplierAccess) access.cmi$getRangeAttributeSupplier())
                    .cmi$getDefaultRangeAttributeInstances();
            cmi$resolveRangeInstances();
        }
    }

    @Unique private void cmi$resolveRangeInstances() {
        cmi$rangeMapSize = cmi$rangeInstances.size();
        cmi$ambitInstance = cmi$rangeInstances.get(cmi$ambitAttribute);
        if (cmi$ambitInstance == null) cmi$ambitInstance = cmi$rangeDefaults.get(cmi$ambitAttribute);
        cmi$sentinelInstance = cmi$rangeInstances.get(cmi$sentinelAttribute);
        if (cmi$sentinelInstance == null) cmi$sentinelInstance = cmi$rangeDefaults.get(cmi$sentinelAttribute);
    }
    @WrapMethod(method = "extractMediaFromInventory(JZZ)J")
    private long cmi$personalMediaPoolOnly(long costLeft, boolean allowOvercast, boolean simulate,
                                           Operation<Long> original) throws Throwable {
        CastingEnvironment env = (CastingEnvironment) (Object) this;
        long directRemaining = FastHexOPMediaPool.extractPreparedPersonalPool(env, costLeft, simulate);
        if (directRemaining != Long.MIN_VALUE) {
            FastHexOPMediaPool.finishDirectExtraction(env, costLeft, directRemaining);
            return directRemaining;
        }
        FastHexOPMediaPool.beginExtraction(env, costLeft, simulate);
        long remaining;
        try {
            remaining = original.call(costLeft, allowOvercast, simulate);
        } catch (Throwable failure) {
            FastHexOPMediaPool.endExtractionFailure(env);
            throw failure;
        }
        FastHexOPMediaPool.endExtraction(env, costLeft, simulate, remaining);
        return remaining;
    }

    @WrapOperation(method = "extractMediaFromInventory(JZZ)J", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/utils/MediaHelper;scanPlayerForMediaStuff(" +
                    "Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/List;"))
    private List<ADMediaHolder> cmi$reusePoolOnlyScan(ServerPlayer player,
                                                       Operation<List<ADMediaHolder>> original) {
        List<ADMediaHolder> forced = FastHexOPMediaPool.forcedSources(
                (CastingEnvironment) (Object) this, player);
        if (forced != null) return forced;
        List<ADMediaHolder> scanned = original.call(player);
        if (ServerConfig.hexJitReuseTickMediaScan)
            FastHexOPMediaPool.rememberScannedSources((CastingEnvironment) (Object) this, player, scanned);
        return scanned;
    }
}
