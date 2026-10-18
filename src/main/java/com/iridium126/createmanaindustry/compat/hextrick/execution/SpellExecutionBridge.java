package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;
import com.iridium126.createmanaindustry.compat.trickster.construct.ConstructCastEnv;
import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.spell.CrowMind;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.exception.TricksterEngineException;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Executes a Trickster {@link SpellPart} from a Hexcasting iota, mirroring
 * the author's HexTricks bridge but with direct API calls (CMI compiles
 * against the Mojang-remapped Trickster jar).
 * <p>
 * Runs synchronously up to the per-tick instruction limit; if the spell is
 * not done, it is queued into the source's execution manager (HANDED_OFF) and
 * continues ticking in the background — the final result is discarded, like
 * HexTricks. In a hex circle, mana is drawn from live slate knots after
 * resolving the saved source identity. Constructs use their native
 * inventory-backed source; block sources continue through CMI's tick queue.
 */
public final class SpellExecutionBridge {

    public enum SpellExecutionStatus {
        COMPLETED, HANDED_OFF, FAILED
    }

    public record SpellExecutionResult(SpellExecutionStatus status, @Nullable Iota result, @Nullable String failureReason) {

        static SpellExecutionResult completed(@Nullable Iota result) {
            return new SpellExecutionResult(SpellExecutionStatus.COMPLETED, result, null);
        }

        static SpellExecutionResult handedOff() {
            return new SpellExecutionResult(SpellExecutionStatus.HANDED_OFF, null, null);
        }

        static SpellExecutionResult failed(CastingEnvironment env, String reason) {
            env.printMessage(Component.translatable("createmanaindustry.bridge.failed", reason));
            return new SpellExecutionResult(SpellExecutionStatus.FAILED, null, reason);
        }
    }

    private SpellExecutionBridge() {
    }

    public static SpellExecutionResult tryExecute(SpellPart spellPart, List<Iota> arguments, CastingEnvironment env) {
        var enclosing = BridgeExecutionContext.current();
        var context = enclosing == null ? new BridgeExecutionContext(env.getWorld().getServer(), null) : enclosing;
        try (var scope = context.enter(); var crossing = context.crossing()) {
            SpellSource source;
            if (env instanceof CircleCastEnv circleEnv) {
                var impetus = circleEnv.getImpetus();
                if (impetus == null) return SpellExecutionResult.failed(env, "missing_impetus");
                ServerLevel level = env.getWorld();
                var origin = circleEnv instanceof AnchoredCircleCastEnv anchored
                        ? anchored.origin() : CastingOrigin.capture(circleEnv);
                context = context.withOrigin(origin);
                source = new CircleSpellSource(new AnchoredCircleCastEnv(level, origin), origin);
            } else if (env instanceof ConstructCastEnv constructEnv) {
                BlockEntity construct = constructEnv.construct();
                if (construct instanceof SpellConstructBlockEntity simple) {
                    source = constructSource(env.getWorld(), simple);
                } else if (construct instanceof ModularSpellConstructBlockEntity modular) {
                    source = constructSource(env.getWorld(), modular);
                } else {
                    return SpellExecutionResult.failed(env, "unsupported_construct");
                }
            } else if (env.getCastingEntity() instanceof ServerPlayer player) {
                source = new PlayerSpellSource(player);
            } else {
                return SpellExecutionResult.failed(env, "unsupported_source");
            }

            List<Fragment> fragments = new ArrayList<>();
            for (Iota arg : arguments) {
                Fragment fragment = FragmentConverter.iotaToFragment(arg);
                if (fragment == null) {
                    return SpellExecutionResult.failed(env, "argument_conversion");
                }
                fragments.add(fragment);
            }

            DefaultSpellExecutor nativeExecutor = new DefaultSpellExecutor(spellPart, fragments);
            var executor = new BridgeSpellExecutor(nativeExecutor, context);

            Optional<Fragment> result = executor.run(source);
            if (result.isPresent()) {
                return SpellExecutionResult.completed(FragmentConverter.fragmentToIota(result.get(),env.getWorld()));
            }

            Optional<SpellExecutionManager> manager = source.getExecutionManager();
            boolean queued = false;
            try {
                if (manager.isPresent() && manager.get().queue(executor).isPresent()) {
                    queued = true;
                    return SpellExecutionResult.handedOff();
                }
                return SpellExecutionResult.failed(env, "queue_full");
            } finally {
                if (!queued) executor.cmi$releaseExecution();
            }
        } catch (BridgeExecutionContext.BridgeFailure failure) {
            return SpellExecutionResult.failed(env, failure.reason());
        } catch (TricksterEngineException failure) {
            env.printMessage(failure.createMessage());
            return new SpellExecutionResult(SpellExecutionStatus.FAILED, null, "trickster_blunder");
        } catch (RuntimeException | LinkageError failure) {
            CreateManaIndustry.LOGGER.warn("Trickster bridge failed unexpectedly", failure);
            return SpellExecutionResult.failed(env, "internal_error");
        }
    }

    private static <T extends BlockEntity & Container & CrowMind> SpellSource constructSource(ServerLevel level, T construct) {
        return new BlockSpellSource<>(level, construct.getBlockPos(), construct) {
            @Override
            public Optional<SpellExecutionManager> getExecutionManager() {
                return Optional.of(new BlockSpellExecutionManager(construct, this));
            }
        };
    }
}
