package com.iridium126.createtricks.content.kinetics.bnb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.RenderedChainPathNode;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public final class BnBChainRenderContext {
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
	private static final float VELOCITY_EXPIRY_SECONDS = 2.0f;
	private static final Map<BlockPos, CachedVelocity> CHAIN_ANGULAR_VELOCITIES = new ConcurrentHashMap<>();
	private static final double SCALE = BnBKineticsCoreNodes.KINETICS_CORE_RADIUS
			/ BnBKineticsCoreNodes.BNB_SMALL_COGWHEEL_RADIUS;
	private static final double R = BnBKineticsCoreNodes.KINETICS_CORE_RADIUS;
	private static final int ARC_SEGMENTS = 3;

	private BnBChainRenderContext() {}

	public static void begin(BlockEntity be) {
		if (be != null && be.getLevel() != null)
			CURRENT.set(new Context(be.getLevel(), be.getBlockPos()));
		else
			CURRENT.remove();
	}

	public static void end() { CURRENT.remove(); }
	public static boolean hasContext() { return CURRENT.get() != null; }

	public static BlockPos controllerPos() {
		Context ctx = CURRENT.get();
		return ctx != null ? ctx.origin : BlockPos.ZERO;
	}

	public static Level clientLevel() {
		Context ctx = CURRENT.get();
		return ctx != null ? ctx.level : null;
	}

	// ========================================================================
	// Expansion entry point
	// ========================================================================

	public static List<RenderedChainPathNode> expandSpellConstructNodes(
			List<RenderedChainPathNode> original) {
		Context ctx = CURRENT.get();
		if (ctx == null) return original;

		int n = original.size();
		if (n < 2) return original;

		boolean[] visited = new boolean[n];
		List<Group> groups = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (visited[i]) continue;
			BlockPos rel = original.get(i).relativePos();
			if (!isSpellConstructAt(ctx, rel)) continue;

			Group g = new Group(rel, original.get(i).sourceCogwheelAxis());
			int j = i;
			while (j < n && original.get(j).relativePos().equals(rel)) {
				g.indices.add(j);
				visited[j] = true;
				j++;
			}
			g.prevIdx = (i - 1 + n) % n;
			g.nextIdx = j % n;
			groups.add(g);
		}
		if (groups.isEmpty()) return original;

		List<RenderedChainPathNode> expanded = new ArrayList<>();
		int cursor = 0;
		for (Group g : groups) {
			while (cursor < g.indices.get(0))
				expanded.add(original.get(cursor++));

			BlockPos worldPos = ctx.origin.offset(g.relativePos);
			List<Vec3> cores = getCoreWorldCenters(ctx.level, worldPos);
			expanded.addAll(buildCoreNodes(g, cores, original, ctx));
			cursor = g.indices.get(g.indices.size() - 1) + 1;
		}
		while (cursor < n)
			expanded.add(original.get(cursor++));

		return expanded;
	}

	// ========================================================================
	// Per-group node construction
	// ========================================================================

	private static boolean isSpellConstructAt(Context ctx, BlockPos rel) {
		return BnBKineticsCoreNodes.isModularSpellConstructBlock(
				ctx.level.getBlockState(ctx.origin.offset(rel)).getBlock());
	}

	private static List<RenderedChainPathNode> buildCoreNodes(
			Group g, List<Vec3> coreWorldCenters,
			List<RenderedChainPathNode> original, Context ctx) {

		if (coreWorldCenters.isEmpty())
			return copyGroup(original, g.indices);

		Vec3 originLower = Vec3.atLowerCornerOf(ctx.origin);
		Vec3 blockCenter = Vec3.atCenterOf(g.relativePos);

		// --- single core: scale existing wrapped geometry toward core ---
		if (coreWorldCenters.size() == 1) {
			Vec3 coreWorld = coreWorldCenters.get(0);
			List<RenderedChainPathNode> scaled = new ArrayList<>();
			for (int idx : g.indices) {
				RenderedChainPathNode node = original.get(idx);
				Vec3 old = originLower.add(node.getPosition());
				Vec3 nw = coreWorld.add(old.subtract(coreWorld).scale(SCALE));
				scaled.add(new RenderedChainPathNode(g.relativePos,
						nw.subtract(originLower).subtract(blockCenter),
						node.sourceCogwheelAxis()));
			}
			return scaled;
		}

		// --- multiple cores: tangent points on core surface + arc wraps ---
		Vec3 axisVec = g.axis.normalize();
		if (axisVec.lengthSqr() < 1e-6) axisVec = new Vec3(0, 1, 0);

		RenderedChainPathNode prevNode = original.get(g.prevIdx);
		RenderedChainPathNode nextNode = original.get(g.nextIdx);
		Vec3 prevWorld = originLower.add(prevNode.getPosition());
		Vec3 nextWorld = originLower.add(nextNode.getPosition());

		Vec3 dir = nextWorld.subtract(prevWorld);
		final Vec3 fwd = dir.lengthSqr() < 1e-6
				? new Vec3(1, 0, 0) : dir.normalize();

		List<Vec3> sorted = new ArrayList<>(coreWorldCenters);
		sorted.sort((a, b) -> Double.compare(a.dot(fwd), b.dot(fwd)));

		List<RenderedChainPathNode> result = new ArrayList<>();
		Vec3 inWorld = prevWorld;
		int side = 1;
		for (int ci = 0; ci < sorted.size(); ci++) {
			Vec3 coreWorld = sorted.get(ci);
			Vec3 outWorld = (ci + 1 < sorted.size())
					? sorted.get(ci + 1) : nextWorld;
			Vec3 coreRel = coreWorld.subtract(originLower);

			Vec3 inDir = coreWorld.subtract(inWorld);
			if (inDir.lengthSqr() < 1e-6) inDir = fwd;
			inDir = inDir.normalize();
			Vec3 outDir = outWorld.subtract(coreWorld);
			if (outDir.lengthSqr() < 1e-6) outDir = fwd;
			outDir = outDir.normalize();

			double s = R * side;
			Vec3 inPerp = axisVec.cross(inDir).normalize();
			Vec3 outPerp = axisVec.cross(outDir).normalize();
			inPerp = new Vec3(inPerp.x * s, inPerp.y * s, inPerp.z * s);
			outPerp = new Vec3(outPerp.x * s, outPerp.y * s, outPerp.z * s);

			// Entry tangent node
			result.add(makeNode(g, coreRel, inPerp, blockCenter, axisVec));

			// Arc nodes wrapping around the core
			Vec3 ip = inPerp.normalize();
			Vec3 op = outPerp.normalize();
			double cross = axisVec.dot(ip.cross(op));
			double dot = clampDot(ip.dot(op));
			double angle = Math.atan2(cross, dot);
			if (Math.abs(angle) > 1e-6) {
				for (int as = 1; as <= ARC_SEGMENTS; as++) {
					double t = (double) as / (ARC_SEGMENTS + 1);
					Vec3 arc = rotateAroundAxis(inPerp, axisVec, angle * t);
					result.add(makeNode(g, coreRel, arc, blockCenter, axisVec));
				}
			}

			// Exit tangent node
			result.add(makeNode(g, coreRel, outPerp, blockCenter, axisVec));

			inWorld = coreWorld;
			side = -side;
		}
		return result;
	}

	private static RenderedChainPathNode makeNode(Group g, Vec3 coreRel,
			Vec3 offset, Vec3 blockCenter, Vec3 axis) {
		return new RenderedChainPathNode(g.relativePos,
				coreRel.add(offset).subtract(blockCenter), axis);
	}

	private static Vec3 rotateAroundAxis(Vec3 v, Vec3 axis, double angle) {
		double cos = Math.cos(angle), sin = Math.sin(angle);
		return v.scale(cos)
				.add(axis.cross(v).scale(sin))
				.add(axis.scale(axis.dot(v) * (1 - cos)));
	}

	private static double clampDot(double d) {
		return Math.max(-1.0, Math.min(1.0, d));
	}

	private static List<RenderedChainPathNode> copyGroup(
			List<RenderedChainPathNode> original, List<Integer> indices) {
		List<RenderedChainPathNode> copy = new ArrayList<>();
		for (int idx : indices) copy.add(original.get(idx));
		return copy;
	}

	// ========================================================================
	// Core position helpers
	// ========================================================================

	private static List<Vec3> getCoreWorldCenters(Level level, BlockPos worldPos) {
		List<Vec3> centers = new ArrayList<>();
		BlockEntity be = level.getBlockEntity(worldPos);
		if (!(be instanceof Container container)) return centers;
		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (com.iridium126.createtricks.content.items.KineticsSpellCoreItem
					.is(container.getItem(slot))) {
				int idx = Math.max(0, slot - 1);
				int x = idx % 2;
				int z = idx / 2;
				centers.add(Vec3.atLowerCornerOf(worldPos)
						.add((18.0 / 2.0 * x + 3.5) / 16.0,
								10.5 / 16.0,
								(18.0 / 2.0 * z + 3.5) / 16.0));
			}
		}
		return centers;
	}

	// ========================================================================
	// Angular velocity cache
	// ========================================================================

	public static void putChainAngularVelocity(BlockPos spellPos, float av) {
		CHAIN_ANGULAR_VELOCITIES.put(spellPos.immutable(),
				new CachedVelocity(av, AnimationTickHolder.getRenderTime()));
	}

	public static float getChainAngularVelocity(BlockPos spellPos) {
		CachedVelocity e = CHAIN_ANGULAR_VELOCITIES.get(spellPos);
		if (e == null) return 0f;
		if (AnimationTickHolder.getRenderTime() - e.renderTime
				> VELOCITY_EXPIRY_SECONDS) return 0f;
		return e.angularVelocity;
	}

	// ---- records ----------------------------------------------------------

	private record CachedVelocity(float angularVelocity, float renderTime) {}
	private record Context(Level level, BlockPos origin) {}

	private static final class Group {
		final BlockPos relativePos;
		final List<Integer> indices = new ArrayList<>();
		final Vec3 axis;
		int prevIdx, nextIdx;

		Group(BlockPos relativePos, Vec3 axis) {
			this.relativePos = relativePos;
			this.axis = axis;
		}
	}
}
