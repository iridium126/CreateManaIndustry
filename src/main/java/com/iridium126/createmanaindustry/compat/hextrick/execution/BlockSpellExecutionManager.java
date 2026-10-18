package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.exception.TricksterEngineException;
import dev.enjarai.trickster.spell.execution.SpellExecutionManager;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-thread-only queue for spells handed off by a block-backed Hex cast.
 * Each job retains its own source (position, inventory and mana pool). The five
 * slots per block match Trickster's default player capacity and do not occupy
 * or replace a construct's native executor. Pending jobs are transient; unload,
 * removal and server shutdown cancel them, and completion discards the result.
 */
public final class BlockSpellExecutionManager implements SpellExecutionManager {
    private static final int CAPACITY = 5;
    private static final Map<ServerLevel, Map<BlockEntity, Map<Integer, Job>>> WORLDS = new IdentityHashMap<>();
    private static boolean registered;
    private final BlockEntity owner;
    private final SpellSource source;

    private record Job(BlockEntity owner, int slot, SpellExecutor executor, SpellSource source, long queuedAt) {}

    public BlockSpellExecutionManager(BlockEntity owner, SpellSource source) {
        this.owner = owner;
        this.source = source;
    }

    public static void register() {
        if (registered) return;
        NeoForge.EVENT_BUS.addListener(BlockSpellExecutionManager::tick);
        NeoForge.EVENT_BUS.addListener(BlockSpellExecutionManager::unload);
        NeoForge.EVENT_BUS.addListener(BlockSpellExecutionManager::stopped);
        registered = true;
    }

    @Override
    public Optional<Integer> queue(SpellExecutor executor) {
        if (!valid(owner, source.getWorld())) return Optional.empty();
        Map<Integer, Job> slots = jobs(source.getWorld()).computeIfAbsent(owner, key -> new HashMap<>());
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (!slots.containsKey(slot)) {
                slots.put(slot, new Job(owner, slot, executor, source, source.getWorld().getGameTime()));
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    @Override public Optional<SpellExecutor> getSpellExecutor(int index) {
        Map<Integer, Job> slots = jobs(source.getWorld()).get(owner);
        Job job = slots == null ? null : slots.get(index);
        return job == null ? Optional.empty() : Optional.of(job.executor());
    }
    @Override public Optional<SpellPart> getSpell(int index) { return getSpellExecutor(index).map(SpellExecutor::spell); }
    @Override public boolean kill(int index) {
        Map<Integer, Job> slots = jobs(source.getWorld()).get(owner);
        if (slots == null) return false;
        Job removed = slots.remove(index);
        if (removed == null) return false;
        release(removed);
        if (slots.isEmpty()) jobs(source.getWorld()).remove(owner);
        return true;
    }
    @Override public void killAll() { remove(source.getWorld(), owner); }

    // Keep the BlockPos overload: Allvr resolves loaded cubes using all three coordinates.
    @SuppressWarnings("deprecation")
    private static boolean valid(BlockEntity block, ServerLevel level) {
        return !block.isRemoved() && block.getLevel() == level && level.hasChunkAt(block.getBlockPos())
                && level.getBlockEntity(block.getBlockPos()) == block;
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Spells may queue/kill other spells while running. Only step jobs that
        // were present at the start, and never step a reused slot's replacement.
        var pending = new ArrayList<Job>();
        var worldJobs = WORLDS.get(level);
        if (worldJobs == null) return;
        worldJobs.values().forEach(slots -> pending.addAll(slots.values()));
        for (Job job : pending) {
            Map<Integer, Job> slots = worldJobs.get(job.owner());
            if (slots == null || slots.get(job.slot()) != job) continue;
            if (!valid(job.owner(), level)) {
                remove(level, job.owner());
                continue;
            }
            // The synchronous run already used this tick's instruction budget.
            if (level.getGameTime() <= job.queuedAt()) continue;
            try {
                if (job.executor().run(job.source(), new TickData().withSlot(job.slot())).isPresent()) {
                    slots.remove(job.slot(), job);
                    release(job);
                }
            } catch (Throwable failure) {
                if (failure instanceof TricksterEngineException blunder)
                    job.source().showMessage(blunder.createMessage());
                else CreateManaIndustry.LOGGER.warn("Failed to continue Trickster spell at {}", job.owner().getBlockPos(), failure);
                slots.remove(job.slot(), job);
                release(job);
            }
            if (slots.isEmpty()) worldJobs.remove(job.owner(), slots);
        }
    }

    private static Map<BlockEntity, Map<Integer, Job>> jobs(ServerLevel world) {
        return WORLDS.computeIfAbsent(world, key -> new IdentityHashMap<>());
    }

    private static void release(Job job) {
        if (job.executor() instanceof ExecutorChainAccess access)
            access.cmi$releaseExecution();
    }

    private static void remove(ServerLevel world, BlockEntity owner) {
        var slots = jobs(world).remove(owner);
        if (slots != null) slots.values().forEach(BlockSpellExecutionManager::release);
    }

    private static void unload(LevelEvent.Unload event) {
        var jobs = WORLDS.remove(event.getLevel());
        if (jobs != null) jobs.values().forEach(slots -> slots.values().forEach(BlockSpellExecutionManager::release));
    }

    private static void stopped(ServerStoppedEvent event) {
        var worlds = WORLDS.entrySet().iterator();
        while (worlds.hasNext()) {
            var entry = worlds.next();
            if (entry.getKey().getServer() != event.getServer()) continue;
            entry.getValue().values().forEach(slots -> slots.values().forEach(BlockSpellExecutionManager::release));
            worlds.remove();
        }
    }
}
