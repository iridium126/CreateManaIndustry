package com.iridium126.createtricks.content.kinetics.cogwheel_chain.render;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

public final class CogwheelChainRenderGeometryBuilder {

	private static final float CHAIN_RENDER_WIDTH = 3;
	private static final float CHAIN_RENDER_HEIGHT = 3;

	private CogwheelChainRenderGeometryBuilder() {}

	public static List<Vec3> getPointsInClosestOrder(List<Vec3> destinationPoints, List<Vec3> sourcePoints) {
		if (destinationPoints.size() != 4 || sourcePoints.size() != 4)
			return new ArrayList<>(destinationPoints);

		double bestScore = Double.POSITIVE_INFINITY;
		List<Vec3> best = new ArrayList<>(destinationPoints);

		for (int reversed = 0; reversed <= 1; reversed++) {
			for (int shift = 0; shift < 4; shift++) {
				ArrayList<Vec3> candidate = new ArrayList<>(4);
				double pointScore = 0;

				for (int i = 0; i < 4; i++) {
					int j = reversed == 0 ? ((i + shift) & 3) : ((shift - i + 4) & 3);
					Vec3 point = destinationPoints.get(j);
					candidate.add(point);
					pointScore += sourcePoints.get(i).distanceToSqr(point);
				}

				double edgeScore = 0;
				for (int i = 0; i < 4; i++) {
					Vec3 sourceEdge = sourcePoints.get((i + 1) & 3).subtract(sourcePoints.get(i)).normalize();
					Vec3 destinationEdge = candidate.get((i + 1) & 3).subtract(candidate.get(i)).normalize();
					edgeScore += 1 - sourceEdge.dot(destinationEdge);
				}

				double score = pointScore + edgeScore * 0.25 + (reversed == 1 ? 1e-4 : 0);
				if (score < bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
		}

		return best;
	}

	public static List<Vec3> getEndPointsForChainJoint(Vec3 before, Vec3 point, Vec3 after) {
		float radius = (float) (Math.sqrt(2f) / 2f * 1f / 16f);
		Vec3 dirToBefore = point.subtract(before).normalize();
		Vec3 dirToAfter = after.subtract(point).normalize();
		Vec3 averagedDir = dirToBefore.add(dirToAfter).normalize();
		if (averagedDir.lengthSqr() < 1e-6)
			averagedDir = dirToAfter.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : dirToAfter;

		Matrix3f transform = new Quaternionf()
			.rotationTo(0, 1, 0, (float) averagedDir.x, (float) averagedDir.y, (float) averagedDir.z)
			.get(new Matrix3f());

		Vector3f localAxis1Joml = transform.transform(1f, 0f, 0f, new Vector3f());
		Vec3 localAxis1 = new Vec3(localAxis1Joml.x, localAxis1Joml.y, localAxis1Joml.z)
			.normalize()
			.scale(CHAIN_RENDER_HEIGHT / 2f);
		Vector3f localAxis2Joml = transform.transform(0f, 0f, 1f, new Vector3f());
		Vec3 localAxis2 = new Vec3(localAxis2Joml.x, localAxis2Joml.y, localAxis2Joml.z)
			.normalize()
			.scale(CHAIN_RENDER_WIDTH / 2f);

		return Stream.of(
				point.add(localAxis1.add(localAxis2).scale(radius)),
				point.add(localAxis1.subtract(localAxis2).scale(radius)),
				point.add(localAxis2.scale(-1).subtract(localAxis1).scale(radius)),
				point.add(localAxis2.subtract(localAxis1).scale(radius))
			)
			.collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
	}

	public record ChainSegment(Vec3 preFrom, Vec3 from, Vec3 to, Vec3 postTo, double uvStart, double distance) {}
}
