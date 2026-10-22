package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.fluids.FluidStack;

/** Coordinates loaded world membership and atomic group commits. Simulation lives in FuelTankFluidState. */
public final class FuelTankConnectivity {
    private static final Direction[] DIRECTIONS = Direction.values();

	private FuelTankConnectivity() {
	}

	// ============================ group BFS ============================

	/** Whether the block at {@code pos} is a fuel tank. */
	public static boolean isFuelTankAt(Level level, BlockPos pos) {
		return level != null && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof FuelTankBlock;
	}

	@Nullable
	public static FuelTankBlockEntity partAt(BlockEntityType<?> type, BlockGetter level, BlockPos pos) {
		if (level instanceof Level world && !world.isLoaded(pos)) return null;
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof FuelTankBlockEntity tank && be.getType() == type && !be.isRemoved())
			return tank;
		return null;
	}

	/** Whether two fuel tank blocks belong to the same connected group. */
	public static boolean sameGroup(FuelTankBlockEntity a, FuelTankBlockEntity b) {
		if (a == null || b == null)
			return false;
		BlockPos ca = a.getController();
		BlockPos cb = b.getController();
		return ca != null && ca.equals(cb);
	}

	/** Whether the blocks at {@code a} and {@code b} are fuel tanks of the same group. */
	public static boolean isSameGroup(BlockGetter level, BlockPos a, BlockPos b) {
		if (level instanceof Level world && (!world.isLoaded(a) || !world.isLoaded(b))) return false;
		BlockState state = level.getBlockState(a);
		if (!(state.getBlock() instanceof FuelTankBlock tank))
			return false;
		FuelTankBlockEntity beA = partAt(tank.getBlockEntityType(), level, a);
		FuelTankBlockEntity beB = partAt(tank.getBlockEntityType(), level, b);
		return sameGroup(beA, beB);
	}

	private static Set<BlockPos> findGroup(Level level, BlockPos start, BlockEntityType<?> type, int maxBlocks) {
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		visited.add(start);
		queue.add(start);
		while (!queue.isEmpty() && visited.size() < maxBlocks) {
			BlockPos cur = queue.poll();
			for (Direction d : DIRECTIONS) {
				BlockPos next = cur.relative(d);
				if (visited.contains(next))
					continue;
				if (!isFuelTankAt(level, next))
					continue;
				if (partAt(type, level, next) == null)
					continue;
				visited.add(next);
				queue.add(next);
				if (visited.size() >= maxBlocks)
					break;
			}
		}
		return visited;
	}

	/**
	 * Whether the BFS in {@link #findGroup} was cut off by the block cap: some
	 * face-neighbour of the visited set is another loaded fuel tank, so the
	 * component extends beyond {@code fuelTankMaxBlocks}. Merging such a set would
	 * silently split the component (see {@link #updateConnectivity}).
	 */
	private static boolean isTruncated(Level level, BlockEntityType<?> type, Set<BlockPos> group) {
		for (BlockPos p : group) {
			for (Direction d : DIRECTIONS) {
				BlockPos n = p.relative(d);
				if (group.contains(n) || !isFuelTankAt(level, n))
					continue;
				if (partAt(type, level, n) != null)
					return true;
			}
		}
		return false;
	}

	static BlockPos pickController(Set<BlockPos> group) {
		BlockPos best = null;
		for (BlockPos p : group)
			if (best == null || comparePos(p, best) < 0)
				best = p;
		return best;
	}

	private static int comparePos(BlockPos a, BlockPos b) {
		int c = a.getY() - b.getY();
		if (c != 0)
			return c;
		c = a.getX() - b.getX();
		if (c != 0)
			return c;
		return a.getZ() - b.getZ();
	}

	// ============================ connectivity ============================

	/**
	 * Forms or merges the connected group containing {@code be} (a freshly placed
	 * block). Assigns one controller, merges fluid, recomputes basins and updates
	 * the top/bottom blockstate of every part.
	 */
	public static void updateConnectivity(FuelTankBlockEntity be) {
		Level level = be.getLevel();
		if (level == null || level.isClientSide)
			return;
		BlockEntityType<?> type = be.getType();
		Set<BlockPos> group = findGroup(level, be.getBlockPos(), type, ServerConfig.fuelTankMaxBlocks);
		if (group.isEmpty())
			return;

        if (FuelTankContraptionBridge.isPlacing(level)) return;
        boolean unknown = canGroupStillGrow(level, group);
        for (BlockPos p : group) {
            FuelTankBlockEntity part = partAt(type, level, p);
            if (part == null) continue;
            if ((!part.isController() && part.getControllerBE() == null)
                || (unknown && part.count > group.size()) || (part.needsDeferredSplitHeal)) {
                be.retryConnectivity = true;
                return;
            }
        }

		// The connected component exceeds fuelTankMaxBlocks: the BFS was truncated,
		// so merging this set would split the component in two — fluid would stay on
		// the old controller while the new group reads an empty tank, and the parts
		// would keep an unculled interior wall between them. Keep the newly placed
		// block as its own group instead and say so once.
		if (isTruncated(level, type, group)) {
			CreateManaIndustry.LOGGER.warn(
					"Fuel tank @ {} not merged: connected component exceeds {} blocks (fuelTankMaxBlocks); extra blocks stay separate",
					be.getBlockPos(), ServerConfig.fuelTankMaxBlocks);
			return;
		}

		// Existing controllers within the group and their fluids.
		Map<BlockPos, FluidStack> controllerFluids = new LinkedHashMap<>();
		for (BlockPos p : group) {
			FuelTankBlockEntity part = partAt(type, level, p);
			if (part != null && part.isController())
				controllerFluids.put(p.immutable(), part.tankInventory.getFluid());
		}

		// Distinct non-empty fluids — incompatible groups must not merge. Variants
		// are compared by fluid AND data components: a tank holds a single stack,
		// so two component variants of one fluid must not share a group (merging
		// them would make assignGroup discard the second variant's contents).
		List<FluidStack> variants = new ArrayList<>();
		for (FluidStack f : controllerFluids.values()) {
			if (f.isEmpty())
				continue;
			boolean known = false;
			for (FluidStack v : variants) {
				if (FluidStack.isSameFluidSameComponents(v, f)) {
					known = true;
					break;
				}
			}
			if (!known)
				variants.add(f);
		}
		if (variants.size() > 1) {
			// Incompatible fluids in one connected component. Component-level
			// assignment: every fluid-bearing group stays intact and untouched;
			// every empty block (solo block or member of an empty group) joins the
			// first fluid group its face-connected piece meets, scanning neighbours
			// in Direction order. Replaces the old "first neighbour" logic, which
			// assumed a single empty new block: on contraption disassembly next to
			// an incompatible tank it destroyed the incoming fluid (assignGroup
			// clears absorbed parts' tanks) and fragmented empty parts into
			// multiple groups (each part re-ran the bridge once and never
			// converged).
			Map<BlockPos, Set<BlockPos>> fluidGroups = new LinkedHashMap<>();
			for (Map.Entry<BlockPos, FluidStack> e : controllerFluids.entrySet()) {
				if (e.getValue().isEmpty())
					continue;
				BlockPos ctrl = e.getKey();
				if (fluidGroups.containsKey(ctrl))
					continue;
				Set<BlockPos> cells = new HashSet<>();
				findGroupSameController(level, ctrl, ctrl, type, cells);
				if (!cells.isEmpty())
					fluidGroups.put(ctrl, cells);
			}
			Set<BlockPos> fluidCells = new HashSet<>();
			for (Set<BlockPos> cells : fluidGroups.values())
				fluidCells.addAll(cells);
			Set<BlockPos> free = new HashSet<>(group);
			free.removeAll(fluidCells);

			// Resolve each face-connected piece of free cells to one fluid group.
			// Free is scanned in sorted order so each piece's BFS start really is its
			// lexicographically smallest cell, and the first fluid neighbour in
			// Direction order wins for the whole piece (deterministic). The BFS
			// always collects the COMPLETE piece: exiting on the first fluid hit
			// would strand cells already queued (marked seen but never polled into
			// any piece and skipped as starts), leaving them outside every group.
			List<BlockPos> orderedFree = new ArrayList<>(free);
			orderedFree.sort((a, b) -> comparePos(a, b));
			Map<BlockPos, List<BlockPos>> freeByTarget = new HashMap<>();
			Set<BlockPos> seen = new HashSet<>();
			for (BlockPos start : orderedFree) {
				if (!seen.add(start))
					continue;
				Deque<BlockPos> queue = new ArrayDeque<>();
				queue.add(start);
				List<BlockPos> piece = new ArrayList<>();
				BlockPos target = null;
				while (!queue.isEmpty()) {
					BlockPos cur = queue.poll();
					piece.add(cur);
					for (Direction d : DIRECTIONS) {
						BlockPos n = cur.relative(d);
						if (target == null && fluidCells.contains(n)) {
							FuelTankBlockEntity nPart = partAt(type, level, n);
							if (nPart != null)
								target = nPart.getController();
						}
						if (free.contains(n) && seen.add(n))
							queue.add(n);
					}
				}
				if (target == null)
					target = start; // unreachable in a connected component; safe fallback
				freeByTarget.computeIfAbsent(target, k -> new ArrayList<>()).addAll(piece);
			}

			for (Map.Entry<BlockPos, Set<BlockPos>> e : fluidGroups.entrySet()) {
				Set<BlockPos> sub = new HashSet<>(e.getValue());
				List<BlockPos> joined = freeByTarget.get(e.getKey());
				if (joined != null)
					sub.addAll(joined);
				assignGroup(level, type, sub);
			}
			for (Map.Entry<BlockPos, List<BlockPos>> e : freeByTarget.entrySet()) {
				if (fluidGroups.containsKey(e.getKey()))
					continue; // already assigned above
				assignGroup(level, type, new HashSet<>(e.getValue()));
			}
			return;
		}

		// Compatible (or all empty) — merge the whole component.
		assignGroup(level, type, group);
	}

	private static void findGroupSameController(Level level, BlockPos start, BlockPos controllerPos,
			BlockEntityType<?> type, Set<BlockPos> out) {
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		// Include and validate the seed itself; previously the seed was never added, so
		// split() left it pointing at the removed controller.
		if (out.add(start)) {
			FuelTankBlockEntity part = partAt(type, level, start);
			if (part == null || !controllerPos.equals(part.getController())) {
				out.remove(start);
				return;
			}
		}
		while (!queue.isEmpty()) {
			BlockPos cur = queue.poll();
			for (Direction d : DIRECTIONS) {
				BlockPos next = cur.relative(d);
				if (!out.add(next))
					continue;
				FuelTankBlockEntity part = partAt(type, level, next);
				if (part == null || !controllerPos.equals(part.getController())) {
					out.remove(next);
					continue;
				}
				queue.add(next);
			}
		}
	}

    /** Snapshot all source controllers before mutating any inventory or membership. */
    private static void assignGroup(Level level, BlockEntityType<?> type, Set<BlockPos> group) {
        FuelTankBlockEntity owner = partAt(type, level, pickController(group));
        if (owner == null) return;
        FluidStack merged = FluidStack.EMPTY;
        Map<BlockPos, Long> shares = new HashMap<>();
        boolean exact = true;
        boolean settle = false;
        for (BlockPos pos : group) {
            FuelTankBlockEntity part = partAt(type, level, pos);
            if (part != null) settle |= part.settleAfterMerge;
            if (part == null || !part.isController()) continue;
            FluidStack source = part.tankInventory.getFluid();
            if (source.isEmpty()) continue;
            if (merged.isEmpty()) merged = source.copy();
            else if (FluidStack.isSameFluidSameComponents(merged, source)) merged.grow(source.getAmount());
            else throw new IllegalStateException("Incompatible fuel groups reached merge commit");
            FuelTankFluidState state = part.basins != null ? part.basins : part.savedBasins;
            if (state != null && state.total() == source.getAmount())
                state.cellAmounts().forEach((cell, amount) -> shares.merge(cell, amount, Long::sum));
            else if (part.count == 1 && source.getAmount() <= FuelTankBlockEntity.getCapacityPerBlock())
                shares.merge(pos, (long) source.getAmount(), Long::sum);
            else exact = false;
        }
        FuelTankGeometry geometry = owner.basins != null
            && owner.basins.geometry().perBlock == FuelTankBlockEntity.getCapacityPerBlock()
            && owner.basins.geometry().basinByCell.keySet().equals(group)
                ? owner.basins.geometry() : FuelTankGeometry.build(group, FuelTankBlockEntity.getCapacityPerBlock());
        FuelTankFluidState state;
        if (merged.getAmount() > geometry.capacity()) {
            merged = merged.copyWithAmount(Math.toIntExact(geometry.capacity()));
            exact = false; // A configuration capacity reduction intentionally clamps once.
        }
        if (exact && !settle) state = FuelTankFluidState.remap(geometry, shares);
        else { state = new FuelTankFluidState(geometry); state.settle(merged.getAmount()); }
        // Cells removed while their controller was unloaded lose precisely their mapped share.
        commitGroup(level, type, group, merged.copyWithAmount(Math.toIntExact(state.total())), state);
    }

    private static void commitGroup(Level level, BlockEntityType<?> type, Set<BlockPos> group,
            FluidStack fluid, FuelTankFluidState state) {
        BlockPos controller = pickController(group);
        FuelTankBlockEntity owner = partAt(type, level, controller);
        if (owner == null) return;
        if (fluid.getAmount() != state.total()) throw new IllegalArgumentException("Inconsistent fuel group commit");
        // All fields are final before callbacks/capability invalidation can observe them.
        for (BlockPos pos : group) {
            FuelTankBlockEntity part = partAt(type, level, pos);
            if (part == null) continue;
            part.controller = pos.equals(controller) ? null : controller;
            part.count = group.size();
            part.basins = part == owner ? state : null;
            part.clearSavedGroupState();
            part.needsDeferredSplitHeal = false;
            part.deferredController = null;
            part.retryConnectivity = false;
            part.settleAfterMerge = false;
            part.preventConnectivityUpdate();
            part.setInventoryQuietly(part == owner ? group.size() * FuelTankBlockEntity.getCapacityPerBlock()
                : FuelTankBlockEntity.getCapacityPerBlock(), part == owner ? fluid : FluidStack.EMPTY);
        }
        for (BlockPos pos : group) {
            FuelTankBlockEntity part = partAt(type, level, pos);
            if (part == null) continue;
            part.refreshCapability();
            part.markBasinsDirty();
            part.setChanged();
            part.sendDataImmediately();
        }
        owner.finishFluidChange(true);
    }

    public static void split(FuelTankBlockEntity removed) {
        Level level = removed.getLevel();
        if (level == null || level.isClientSide) return;
        FuelTankBlockEntity owner = removed.getControllerBE();
        if (owner == null) return;
        FuelTankFluidState state = owner.basins != null ? owner.basins : owner.savedBasins;
        if (state == null) return; // Pending loads retain their saved state until observable.
        Map<BlockPos, Long> shares = state.cellAmounts();
        shares.remove(removed.getBlockPos());
        Set<BlockPos> survivors = new HashSet<>(state.geometry().basinByCell.keySet());
        survivors.remove(removed.getBlockPos());
        if (survivors.isEmpty()) return;
        if (canGroupStillGrow(level, survivors)) {
            // A dying controller hands the entire remaining distribution to a loaded member;
            // unloaded members keep the former controller alias until the deferred split heals.
            FuelTankBlockEntity carrier = owner == removed ? survivors.stream()
                .filter(level::isLoaded).map(p -> partAt(removed.getType(), level, p))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null) : owner;
            if (carrier == null) return;
            FuelTankFluidState remaining = FuelTankFluidState.remap(
                FuelTankGeometry.build(survivors, state.geometry().perBlock), shares);
            carrier.controller = null;
            carrier.basins = remaining;
            carrier.count = survivors.size();
            carrier.needsDeferredSplitHeal = true;
            carrier.deferredController = owner.getBlockPos();
            carrier.setInventoryQuietly(Math.toIntExact(remaining.geometry().capacity()),
                owner.tankInventory.getFluid().copyWithAmount(Math.toIntExact(remaining.total())));
            for (BlockPos pos : survivors) {
                FuelTankBlockEntity part = partAt(removed.getType(), level, pos);
                if (part == null || part == carrier) continue;
                part.controller = carrier.getBlockPos(); part.needsDeferredSplitHeal = true;
                part.setInventoryQuietly(FuelTankBlockEntity.getCapacityPerBlock(), FluidStack.EMPTY);
                part.refreshCapability(); part.setChanged(); part.sendDataImmediately();
            }
            carrier.refreshCapability(); carrier.markBasinsDirty(); carrier.setChanged(); carrier.sendDataImmediately();
            return;
        }
        distribute(level, removed.getType(), survivors, owner.tankInventory.getFluid().copy(), shares, owner.getBlockPos(), null);
    }

    public static void healDeferredSplit(FuelTankBlockEntity owner) {
        Level level = owner.getLevel();
        FuelTankFluidState state = owner.basins != null ? owner.basins : owner.savedBasins;
        if (level == null || state == null || canGroupStillGrow(level, state.geometry().basinByCell.keySet())) return;
        distribute(level, owner.getType(), state.geometry().basinByCell.keySet(), owner.tankInventory.getFluid().copy(),
            state.cellAmounts(), owner.getBlockPos(), owner.deferredController);
    }

    private static void distribute(Level level, BlockEntityType<?> type, Set<BlockPos> former, FluidStack fluid,
            Map<BlockPos, Long> shares, BlockPos owner, BlockPos alias) {
        Set<BlockPos> available = new HashSet<>();
        for (BlockPos pos : former) {
            FuelTankBlockEntity part = partAt(type, level, pos);
            if (part != null && (owner.equals(part.getController()) || java.util.Objects.equals(alias, part.getController())))
                available.add(pos);
        }
        while (!available.isEmpty()) {
            BlockPos start = pickController(available);
            Set<BlockPos> component = new HashSet<>(); Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start); available.remove(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.remove(); component.add(pos);
                for (Direction direction : DIRECTIONS) {
                    BlockPos next = pos.relative(direction);
                    if (available.remove(next)) queue.add(next);
                }
            }
            FuelTankFluidState next = FuelTankFluidState.remap(
                FuelTankGeometry.build(component, FuelTankBlockEntity.getCapacityPerBlock()), shares);
            commitGroup(level, type, component, fluid.copyWithAmount(Math.toIntExact(next.total())), next);
        }
    }

    static boolean canGroupStillGrow(Level level, Set<BlockPos> group) {
        for (BlockPos pos : group) {
            if (!level.isLoaded(pos)) return true;
            for (Direction direction : DIRECTIONS) if (!level.isLoaded(pos.relative(direction))) return true;
        }
        return false;
    }

}
