package com.iridium126.createmanaindustry.compat.ysm.render;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Face;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector;
import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore.PreviewData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Server-safe CPU orthographic rasterizer for fixed-size, static YSM previews. */
public final class YsmGeometryThumbnail {
    public static final int SIZE = 40;
    public static final int MAX_PNG_BYTES = 16 * 1024;
    private static final double AZIMUTH = Math.toRadians(35), ELEVATION = Math.toRadians(24);
    private static final int[] BRIGHTNESS = {190, 215, 165, 180, 255, 135};
    private record Work(Group group, Matrix parent) {}
    private record Point(double x, double y, double z) {}
    private record Vertex(double x, double y, double z, double u, double v) {}
    private record Bounds(double minX, double maxX, double minY, double maxY) {}
    private record Texture(int width, int height, int[] pixels) {}
    private interface CubeConsumer { void accept(Cube cube, Matrix transform); }

    public static byte[] renderPng(PreviewData payload) {
        int[] pixels = renderPixels(payload);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder is unavailable");
            byte[] png = output.toByteArray();
            if (png.length > MAX_PNG_BYTES) throw new IllegalStateException("YSM preview PNG exceeds 16 KiB");
            return png;
        } catch (IOException failure) { throw new IllegalStateException("Unable to encode YSM preview PNG", failure); }
    }

    private static int[] renderPixels(PreviewData payload) {
        Texture texture = decodeTexture(payload.texture());
        Bounds bounds = bounds(payload.group(), payload.cube());
        int[] output = new int[SIZE * SIZE];
        if (bounds == null) return output;
        float[] depth = new float[output.length];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        double extent = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY);
        if (!(extent > 1.0e-9)) return output;
        double scale = (SIZE - 6.0) / extent;
        double offsetX = (SIZE - (bounds.maxX - bounds.minX) * scale) * .5 - bounds.minX * scale;
        double offsetY = (SIZE - (bounds.maxY - bounds.minY) * scale) * .5 + bounds.maxY * scale;
        int textureWidth = payload.textureWidth(), textureHeight = payload.textureHeight();
        forEachCube(payload.group(), payload.cube(), (cube, transform) -> rasterCube(cube, transform, texture,
                textureWidth, textureHeight, output, depth, scale, offsetX, offsetY));
        return output;
    }

    private static void forEachCube(Group root, Cube alone, CubeConsumer consumer) {
        if (alone != null && alone.visible()) consumer.accept(alone, cubeMatrix(Matrix.IDENTITY, alone));
        if (root == null) return;
        var pending = new ArrayDeque<Work>();
        pending.push(new Work(root, Matrix.IDENTITY));
        while (!pending.isEmpty()) {
            Work work = pending.pop();
            Group group = work.group();
            if (!group.visible()) continue;
            Matrix current = work.parent().multiply(groupMatrix(group));
            for (Cube cube : group.cubes()) if (cube.visible()) consumer.accept(cube, cubeMatrix(current, cube));
            for (int i = group.children().size() - 1; i >= 0; i--)
                pending.push(new Work(group.children().get(i), current));
        }
    }

    private static Bounds bounds(Group root, Cube alone) {
        double[] values = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        forEachCube(root, alone, (cube, transform) -> {
            if (cube.faces().stream().noneMatch(Face::visible)) return;
            double inflate = cube.inflate();
            double minX = cube.origin().x() - inflate, minY = cube.origin().y() - inflate, minZ = cube.origin().z() - inflate;
            double maxX = cube.origin().x() + cube.size().x() + inflate;
            double maxY = cube.origin().y() + cube.size().y() + inflate;
            double maxZ = cube.origin().z() + cube.size().z() + inflate;
            for (int mask = 0; mask < 8; mask++) {
                Point point = project(transform.point((mask & 1) == 0 ? minX : maxX,
                        (mask & 2) == 0 ? minY : maxY, (mask & 4) == 0 ? minZ : maxZ));
                values[0] = Math.min(values[0], point.x()); values[1] = Math.max(values[1], point.x());
                values[2] = Math.min(values[2], point.y()); values[3] = Math.max(values[3], point.y());
            }
        });
        return Double.isFinite(values[0]) ? new Bounds(values[0], values[1], values[2], values[3]) : null;
    }

    private static void rasterCube(Cube cube, Matrix transform, Texture texture, int textureWidth, int textureHeight,
            int[] output, float[] depth, double scale, double offsetX, double offsetY) {
        double inflate = cube.inflate();
        double x0 = cube.origin().x() - inflate, y0 = cube.origin().y() - inflate, z0 = cube.origin().z() - inflate;
        double x1 = cube.origin().x() + cube.size().x() + inflate;
        double y1 = cube.origin().y() + cube.size().y() + inflate;
        double z1 = cube.origin().z() + cube.size().z() + inflate;
        double[][] points = new double[][] {
                {x0,y0,z0}, {x0,y1,z0}, {x1,y1,z0}, {x1,y0,z0},
                {x1,y0,z1}, {x1,y1,z1}, {x0,y1,z1}, {x0,y0,z1},
                {x1,y0,z0}, {x1,y1,z0}, {x1,y1,z1}, {x1,y0,z1},
                {x0,y0,z1}, {x0,y1,z1}, {x0,y1,z0}, {x0,y0,z0},
                {x0,y1,z0}, {x0,y1,z1}, {x1,y1,z1}, {x1,y1,z0},
                {x0,y0,z1}, {x0,y0,z0}, {x1,y0,z0}, {x1,y0,z1}
        };
        for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
            Face face = cube.faces().get(faceIndex);
            if (!face.visible()) continue;
            int base = faceIndex * 4;
            double[][] uv = faceUv(face);
            Vertex[] vertices = new Vertex[4];
            for (int i = 0; i < 4; i++) {
                Point projected = project(transform.point(points[base + i][0], points[base + i][1], points[base + i][2]));
                vertices[i] = new Vertex(projected.x() * scale + offsetX, -projected.y() * scale + offsetY,
                        projected.z(), uv[i][0], uv[i][1]);
            }
            int color = texture == null ? neutral(faceIndex) : 0xffffffff;
            triangle(vertices[0], vertices[1], vertices[2], color, faceIndex, texture, textureWidth, textureHeight, output, depth);
            triangle(vertices[0], vertices[2], vertices[3], color, faceIndex, texture, textureWidth, textureHeight, output, depth);
        }
    }

    private static double[][] faceUv(Face face) {
        double[][] uv = {{face.u(), face.v() + face.height()}, {face.u(), face.v()},
                {face.u() + face.width(), face.v()}, {face.u() + face.width(), face.v() + face.height()}};
        if (face.rotation() == 0) return uv;
        double centerU = face.u() + face.width() * .5, centerV = face.v() + face.height() * .5;
        double radians = Math.toRadians(face.rotation()), cosine = Math.cos(radians), sine = Math.sin(radians);
        for (double[] point : uv) {
            double du = point[0] - centerU, dv = point[1] - centerV;
            point[0] = centerU + du * cosine - dv * sine;
            point[1] = centerV + du * sine + dv * cosine;
        }
        return uv;
    }

    private static void triangle(Vertex a, Vertex b, Vertex c, int baseColor, int face, Texture texture,
            int modelTextureWidth, int modelTextureHeight, int[] pixels, float[] depth) {
        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
        if (Math.abs(area) < 1.0e-9) return;
        int minX = clamp((int)Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))), 0, SIZE - 1);
        int maxX = clamp((int)Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))), 0, SIZE - 1);
        int minY = clamp((int)Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))), 0, SIZE - 1);
        int maxY = clamp((int)Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))), 0, SIZE - 1);
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double px = x + .5, py = y + .5;
            double wa = edge(b.x(), b.y(), c.x(), c.y(), px, py) / area;
            double wb = edge(c.x(), c.y(), a.x(), a.y(), px, py) / area;
            double wc = 1 - wa - wb;
            if (wa < -1.0e-7 || wb < -1.0e-7 || wc < -1.0e-7) continue;
            double z = wa * a.z() + wb * b.z() + wc * c.z();
            int offset = y * SIZE + x;
            if (z <= depth[offset]) continue;
            int color = baseColor;
            if (texture != null) {
                double u = (wa * a.u() + wb * b.u() + wc * c.u()) / modelTextureWidth;
                double v = (wa * a.v() + wb * b.v() + wc * c.v()) / modelTextureHeight;
                color = sample(texture, u, v);
                color = shade(color, BRIGHTNESS[face]);
            }
            if ((color >>> 24) == 0) continue;
            depth[offset] = (float)z;
            pixels[offset] = color;
        }
    }

    private static int sample(Texture texture, double u, double v) {
        int x = clamp((int)Math.floor(u * texture.width()), 0, texture.width() - 1);
        int y = clamp((int)Math.floor(v * texture.height()), 0, texture.height() - 1);
        return texture.pixels()[y * texture.width() + x];
    }
    private static int shade(int argb, int light) {
        int r = (((argb >>> 16) & 255) * light) >> 8;
        int g = (((argb >>> 8) & 255) * light) >> 8;
        int b = ((argb & 255) * light) >> 8;
        return (argb & 0xff000000) | (r << 16) | (g << 8) | b;
    }
    private static int neutral(int face) {
        int light = BRIGHTNESS[face];
        int r = 205 * light >> 8, g = 190 * light >> 8, b = 165 * light >> 8;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
    private static Texture decodeTexture(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) return null;
            return new Texture(image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                    null, 0, image.getWidth()));
        } catch (IOException ignored) { return null; }
    }
    private static Point project(Point point) {
        double ca = Math.cos(AZIMUTH), sa = Math.sin(AZIMUTH), ce = Math.cos(ELEVATION), se = Math.sin(ELEVATION);
        double x = point.x() * ca + point.z() * sa;
        double y = -point.x() * sa * se + point.y() * ce + point.z() * ca * se;
        double z = point.x() * sa * ce + point.y() * se - point.z() * ca * ce;
        return new Point(x, y, z);
    }
    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static Matrix groupMatrix(Group group) {
        return around(group.pivot(), group.rotation(), group.scale());
    }
    private static Matrix cubeMatrix(Matrix parent, Cube cube) {
        return parent.multiply(around(cube.pivot(), cube.rotation(), cube.scale()));
    }
    private static Matrix around(Vector pivot, Vector rotation, Vector scale) {
        return Matrix.translation(pivot.x(), pivot.y(), pivot.z())
                .multiply(Matrix.rotation(rotation))
                .multiply(Matrix.scale(scale.x(), scale.y(), scale.z()))
                .multiply(Matrix.translation(-pivot.x(), -pivot.y(), -pivot.z()));
    }

    private record Matrix(double[] m) {
        static final Matrix IDENTITY = new Matrix(new double[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
        static Matrix translation(double x, double y, double z) {
            return new Matrix(new double[] {1,0,0,x, 0,1,0,y, 0,0,1,z, 0,0,0,1});
        }
        static Matrix scale(double x, double y, double z) {
            return new Matrix(new double[] {x,0,0,0, 0,y,0,0, 0,0,z,0, 0,0,0,1});
        }
        static Matrix rotation(Vector degrees) {
            double x = Math.toRadians(degrees.x()), y = Math.toRadians(degrees.y()), z = Math.toRadians(degrees.z());
            double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
            Matrix rx = new Matrix(new double[] {1,0,0,0, 0,cx,-sx,0, 0,sx,cx,0, 0,0,0,1});
            Matrix ry = new Matrix(new double[] {cy,0,sy,0, 0,1,0,0, -sy,0,cy,0, 0,0,0,1});
            Matrix rz = new Matrix(new double[] {cz,-sz,0,0, sz,cz,0,0, 0,0,1,0, 0,0,0,1});
            return rz.multiply(ry).multiply(rx);
        }
        Matrix multiply(Matrix other) {
            double[] result = new double[16];
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
                for (int k = 0; k < 4; k++) result[row * 4 + column] += m[row * 4 + k] * other.m[k * 4 + column];
            return new Matrix(result);
        }
        Point point(double x, double y, double z) {
            return new Point(m[0]*x + m[1]*y + m[2]*z + m[3],
                    m[4]*x + m[5]*y + m[6]*z + m[7], m[8]*x + m[9]*y + m[10]*z + m[11]);
        }
    }

    private YsmGeometryThumbnail() {}
}
