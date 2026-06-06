package com.iridium126.createtricks.content.kinetics.cogwheel_chain.render;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class ChainQuadBuilder {

	private static final int SUBDIVISION_COUNT = 4;

	@FunctionalInterface
	public interface VertexEmitter {
		void emit(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private ChainQuadBuilder() {}

	public static void buildSegmentFaces(List<Vec3> destinationPoints, List<Vec3> sourcePoints,
			float minV, float maxV, VertexEmitter emitter) {
		for (int faceIndex = 0; faceIndex < 2; faceIndex++) {
			buildCrossShapeFace(destinationPoints, sourcePoints, faceIndex, minV, maxV, emitter);
		}
	}

	private static void buildCrossShapeFace(List<Vec3> destinationPoints, List<Vec3> sourcePoints,
			int faceIndex, float minV, float maxV, VertexEmitter emitter) {
		float uOffset = (faceIndex % 2 == 1) ? 0 : 3 / 16f;
		float uWidth = 3 / 16f;

		Vec3 posTL = destinationPoints.get((faceIndex + 2) % 4);
		Vec3 posBL = sourcePoints.get((faceIndex + 2) % 4);
		Vec3 posBR = sourcePoints.get(faceIndex);
		Vec3 posTR = destinationPoints.get(faceIndex);

		buildSubdividedQuad(posTL, posBL, posBR, posTR,
			uOffset, uOffset + uWidth, minV, maxV, emitter);
	}

	private static void buildSubdividedQuad(Vec3 posTL, Vec3 posBL, Vec3 posBR, Vec3 posTR,
			float uLeft, float uRight, float minV, float maxV, VertexEmitter emitter) {
		for (int s = 0; s < SUBDIVISION_COUNT; s++) {
			float t1 = (float) s / SUBDIVISION_COUNT;
			float t2 = (float) (s + 1) / SUBDIVISION_COUNT;

			float vStart = Mth.lerp(t1, minV, maxV);
			float vEnd = Mth.lerp(t2, minV, maxV);

			Vec3 p1 = posTL.lerp(posBL, t1);
			Vec3 p2 = posTL.lerp(posBL, t2);
			Vec3 p3 = posTR.lerp(posBR, t2);
			Vec3 p4 = posTR.lerp(posBR, t1);

			Vec3 normal = p2.subtract(p1).cross(p4.subtract(p1));
			if (normal.lengthSqr() < 1e-7)
				normal = new Vec3(0, 1, 0);
			else
				normal = normal.normalize();

			float nx = (float) normal.x;
			float ny = (float) normal.y;
			float nz = (float) normal.z;

			emitter.emit((float) p1.x, (float) p1.y, (float) p1.z, uLeft, vStart, nx, ny, nz);
			emitter.emit((float) p2.x, (float) p2.y, (float) p2.z, uLeft, vEnd, nx, ny, nz);
			emitter.emit((float) p3.x, (float) p3.y, (float) p3.z, uRight, vEnd, nx, ny, nz);
			emitter.emit((float) p4.x, (float) p4.y, (float) p4.z, uRight, vStart, nx, ny, nz);

			emitter.emit((float) p4.x, (float) p4.y, (float) p4.z, uRight, vStart, -nx, -ny, -nz);
			emitter.emit((float) p3.x, (float) p3.y, (float) p3.z, uRight, vEnd, -nx, -ny, -nz);
			emitter.emit((float) p2.x, (float) p2.y, (float) p2.z, uLeft, vEnd, -nx, -ny, -nz);
			emitter.emit((float) p1.x, (float) p1.y, (float) p1.z, uLeft, vStart, -nx, -ny, -nz);
		}
	}
}
