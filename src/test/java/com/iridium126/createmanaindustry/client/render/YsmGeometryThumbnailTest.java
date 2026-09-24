package com.iridium126.createmanaindustry.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore.PreviewData;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Face;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class YsmGeometryThumbnailTest {
    @Test
    void rasterizesVisibleTreeAndAppliesAncestorVisibility() throws Exception {
        Cube cube = cube(8, true, allFaces(true));
        Group child = new Group("child", new Vector(3, 0, 0), new Vector(0, 0, 25), Vector.ONE,
                true, List.of(cube), List.of(), null, "{}");
        Group visible = new Group("root", Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), List.of(child), null, "{}");
        BufferedImage image = decode(render(visible, null, null));
        assertTrue(opaquePixels(image) > 20, "visible cube faces should produce a thumbnail");

        Group hidden = new Group("hidden", Vector.ZERO, Vector.ZERO, Vector.ONE, false,
                List.of(), List.of(child), null, "{}");
        BufferedImage hiddenImage = decode(render(hidden, null, null));
        assertEquals(0, opaquePixels(hiddenImage), "hidden ancestors must suppress every descendant cube");
    }

    @Test
    void usesSourceTextureAndKeepsNeutralMaterialWithoutOne() throws Exception {
        byte[] texture = solidTexture(16, 16, 0xff10d020);
        Group model = new Group("root", Vector.ZERO, Vector.ZERO, Vector.ONE, true,
                List.of(cube(8, true, allFaces(true))), List.of(), null, "{}");
        BufferedImage textured = decode(render(model, texture, null));
        assertTrue(hasDominantGreenPixel(textured), "valid source texture should color visible faces");

        BufferedImage neutral = decode(render(model, null, null));
        assertTrue(opaquePixels(neutral) > 20, "geometry without source resources should use the neutral material");
    }

    @Test
    void samplesPerFaceUvFromTheSourceTexture() throws Exception {
        byte[] texture = halfTexture(64, 64);
        Group red = new Group("root", Vector.ZERO, Vector.ZERO, Vector.ONE, true,
                List.of(cube(8, true, allFaces(0))), List.of(), null, "{}");
        Group blue = new Group("root", Vector.ZERO, Vector.ZERO, Vector.ONE, true,
                List.of(cube(8, true, allFaces(32))), List.of(), null, "{}");
        assertTrue(hasDominantRedPixel(decode(render(red, texture, null))), "first UV region should sample red texels");
        assertTrue(hasDominantBluePixel(decode(render(blue, texture, null))), "shifted UV region should sample blue texels");
    }

    @Test
    void rendersCubeReferencesAndDoesNotTruncateLargeGroups() throws Exception {
        byte[] cubePng = YsmGeometryThumbnail.renderPng(new PreviewData(null, cube(8, true, allFaces(true)), 64, 64, null));
        assertTrue(opaquePixels(decode(cubePng)) > 20, "standalone cube should render");

        var cubes = new java.util.ArrayList<Cube>();
        for (int i = 0; i < 2048; i++)
            cubes.add(cube(1, true, allFaces(true), new Vector(i % 64, i / 64, 0)));
        Group group = new Group("many", Vector.ZERO, Vector.ZERO, Vector.ONE, true, cubes, List.of(), null, "{}");
        byte[] png = render(group, null, null);
        assertTrue(png.length <= YsmGeometryThumbnail.MAX_PNG_BYTES);
        assertTrue(opaquePixels(decode(png)) > 0, "large geometry should still be fully traversed and rendered");
    }

    private static byte[] render(Group group, byte[] texture, Cube cube) {
        return YsmGeometryThumbnail.renderPng(new PreviewData(group, cube, 64, 64, texture));
    }
    private static BufferedImage decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(YsmGeometryThumbnail.SIZE, image.getWidth());
        assertEquals(YsmGeometryThumbnail.SIZE, image.getHeight());
        return image;
    }
    private static long opaquePixels(BufferedImage image) {
        return java.util.Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .filter(pixel -> (pixel >>> 24) != 0).count();
    }
    private static boolean hasDominantGreenPixel(BufferedImage image) {
        return java.util.Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .anyMatch(pixel -> ((pixel >>> 8) & 255) > ((pixel >>> 16) & 255) * 2);
    }
    private static boolean hasDominantRedPixel(BufferedImage image) {
        return java.util.Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .anyMatch(pixel -> ((pixel >>> 16) & 255) > ((pixel >>> 8) & 255) * 2);
    }
    private static boolean hasDominantBluePixel(BufferedImage image) {
        return java.util.Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .anyMatch(pixel -> (pixel & 255) > ((pixel >>> 8) & 255) * 2);
    }
    private static Cube cube(double size, boolean visible, List<Face> faces) {
        return cube(size, visible, faces, new Vector(-size / 2, -size / 2, -size / 2));
    }
    private static Cube cube(double size, boolean visible, List<Face> faces, Vector origin) {
        return new Cube(origin, new Vector(size, size, size), Vector.ZERO, Vector.ZERO, Vector.ONE,
                0, visible, faces, "{}");
    }
    private static List<Face> allFaces(boolean visible) {
        return List.of(new Face(0, 0, 8, 8, 0, visible), new Face(0, 0, 8, 8, 0, visible),
                new Face(0, 0, 8, 8, 0, visible), new Face(0, 0, 8, 8, 0, visible),
                new Face(0, 0, 8, 8, 0, visible), new Face(0, 0, 8, 8, 0, visible));
    }
    private static List<Face> allFaces(double u) {
        return List.of(new Face(u, 0, 8, 8, 0, true), new Face(u, 0, 8, 8, 0, true),
                new Face(u, 0, 8, 8, 0, true), new Face(u, 0, 8, 8, 0, true),
                new Face(u, 0, 8, 8, 0, true), new Face(u, 0, 8, 8, 0, true));
    }
    private static byte[] solidTexture(int width, int height, int color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
    private static byte[] halfTexture(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            pixels[y * width + x] = x < width / 2 ? 0xffe02020 : 0xff2020e0;
        image.setRGB(0, 0, width, height, pixels, 0, width);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
