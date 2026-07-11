package com.iridium126.createmanaindustry.client.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

public final class KineticsSpellCoreRenderer {
    private static final ResourceLocation TEXTURE = CreateManaIndustry.modLoc("textures/block/kinetics_spell_core.png");
    private static final int COLOR = 0xFFFFFFFF;
    private static final Cuboid[] CUBOIDS = {
            cuboid(4f, 4f, 4f, 12f, 12f, 12f,
                    face(Direction.NORTH, 0f, 8.5f, 4f, 12.5f),
                    face(Direction.EAST, 0f, 8.5f, 4f, 12.5f),
                    face(Direction.SOUTH, 0f, 8.5f, 4f, 12.5f),
                    face(Direction.WEST, 0f, 8.5f, 4f, 12.5f),
                    face(Direction.UP, 0f, 4.5f, 4f, 8.5f),
                    face(Direction.DOWN, 0f, 4.5f, 4f, 8.5f)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, 0f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, 60f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, 120f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, -180f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, -120f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(-1f, 5f, 6.5f, 8f, 11f, 9.5f, rotation(8f, 8f, 8f, 15f, -60f, 0f),
                    face(Direction.NORTH, 0f, 0f, 4.5f, 3f),
                    face(Direction.SOUTH, 0f, 0f, 4.5f, 3f, 180),
                    face(Direction.WEST, 4.5f, 0f, 6f, 3f),
                    face(Direction.UP, 0f, 3f, 4.5f, 4.5f, 180),
                    face(Direction.DOWN, 0f, 3f, 4.5f, 4.5f, 180)),
            cuboid(3f, 5f, 3f, 13f, 11f, 13f,
                    face(Direction.NORTH, 4f, 9.5f, 9f, 12.5f),
                    face(Direction.EAST, 4f, 9.5f, 9f, 12.5f),
                    face(Direction.SOUTH, 4f, 9.5f, 9f, 12.5f),
                    face(Direction.WEST, 4f, 9.5f, 9f, 12.5f),
                    face(Direction.UP, 4f, 4.5f, 9f, 9.5f),
                    face(Direction.DOWN, 4f, 4.5f, 9f, 9.5f))
    };

    private KineticsSpellCoreRenderer() {}

    public static void render(PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        for (Cuboid cuboid : CUBOIDS)
            cuboid.render(pose, consumer, light, overlay);
    }

    private static Cuboid cuboid(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, Face... faces) {
        return new Cuboid(new Vector3f(fromX / 16f, fromY / 16f, fromZ / 16f),
                new Vector3f(toX / 16f, toY / 16f, toZ / 16f), Rotation.NONE, faces);
    }

    private static Cuboid cuboid(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, Rotation rotation,
            Face... faces) {
        return new Cuboid(new Vector3f(fromX / 16f, fromY / 16f, fromZ / 16f),
                new Vector3f(toX / 16f, toY / 16f, toZ / 16f), rotation, faces);
    }

    private static Rotation rotation(float originX, float originY, float originZ, float x, float y, float z) {
        return new Rotation(new Vector3f(originX / 16f, originY / 16f, originZ / 16f), x, y, z);
    }

    private static Face face(Direction direction, float u0, float v0, float u1, float v1) {
        return face(direction, u0, v0, u1, v1, 0);
    }

    private static Face face(Direction direction, float u0, float v0, float u1, float v1, int rotation) {
        return new Face(direction, u0 / 16f, v0 / 16f, u1 / 16f, v1 / 16f, rotation);
    }

    private record Cuboid(Vector3f from, Vector3f to, Rotation rotation, Face[] faces) {
        void render(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
            for (Face face : faces)
                renderFace(face, pose, consumer, light, overlay);
        }

        private void renderFace(Face face, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
            Vector3f[] positions = face.vertexPositions(from, to);
            for (int i = 0; i < positions.length; i++) {
                if (rotation != Rotation.NONE)
                    rotation.transformPosition(positions[i]);
                pose.pose().transformPosition(positions[i]);
            }

            Vector3f normal = face.normal();
            if (rotation != Rotation.NONE)
                rotation.transformNormal(normal);
            pose.normal().transform(normal);
            normal.normalize();

            float[][] uvs = face.uvs();
            for (int i = 0; i < 4; i++) {
                Vector3f position = positions[i];
                float[] uv = uvs[i];
                consumer.addVertex(position.x, position.y, position.z, COLOR, uv[0], uv[1], overlay, light,
                        normal.x, normal.y, normal.z);
            }
        }
    }

    private record Rotation(Vector3f origin, float x, float y, float z) {
        private static final Rotation NONE = new Rotation(new Vector3f(), 0f, 0f, 0f);

        void transformPosition(Vector3f position) {
            if (this == NONE)
                return;

            Matrix4f matrix = new Matrix4f()
                    .translate(origin)
                    .rotateYXZ((float) Math.toRadians(y), (float) Math.toRadians(x), (float) Math.toRadians(z))
                    .translate(-origin.x, -origin.y, -origin.z);
            matrix.transformPosition(position);
        }

        void transformNormal(Vector3f normal) {
            if (this == NONE)
                return;

            Matrix3f matrix = new Matrix3f()
                    .rotateYXZ((float) Math.toRadians(y), (float) Math.toRadians(x), (float) Math.toRadians(z));
            matrix.transform(normal);
        }
    }

    private record Face(Direction direction, float u0, float v0, float u1, float v1, int rotation) {
        Vector3f normal() {
            return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }

        Vector3f[] vertexPositions(Vector3f from, Vector3f to) {
            float x1 = from.x;
            float y1 = from.y;
            float z1 = from.z;
            float x2 = to.x;
            float y2 = to.y;
            float z2 = to.z;
            return switch (direction) {
                case DOWN -> new Vector3f[] {
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x1, y1, z1),
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x2, y1, z2)
                };
                case UP -> new Vector3f[] {
                        new Vector3f(x1, y2, z1),
                        new Vector3f(x1, y2, z2),
                        new Vector3f(x2, y2, z2),
                        new Vector3f(x2, y2, z1)
                };
                case NORTH -> new Vector3f[] {
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x1, y1, z1),
                        new Vector3f(x1, y2, z1),
                        new Vector3f(x2, y2, z1)
                };
                case SOUTH -> new Vector3f[] {
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x2, y1, z2),
                        new Vector3f(x2, y2, z2),
                        new Vector3f(x1, y2, z2)
                };
                case WEST -> new Vector3f[] {
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x1, y2, z2),
                        new Vector3f(x1, y2, z1),
                        new Vector3f(x1, y1, z1)
                };
                case EAST -> new Vector3f[] {
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x2, y2, z1),
                        new Vector3f(x2, y2, z2),
                        new Vector3f(x2, y1, z2)
                };
            };
        }

        float[][] uvs() {
            BlockFaceUV faceUv = new BlockFaceUV(
                    new float[] {u0, v0, u1, v1},
                    rotation
            );

            float[][] result = new float[4][2];

            int[] order = switch (direction) {
                case NORTH -> new int[] {1, 2, 3, 0};
                case SOUTH -> new int[] {0, 3, 2, 1};
                default -> new int[] {0, 1, 2, 3};
            };

            for (int i = 0; i < 4; i++) {
                result[i][0] = faceUv.getU(order[i]);
                result[i][1] = faceUv.getV(order[i]);
            }

            return result;
        }
    }
}
