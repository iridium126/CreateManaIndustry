package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.infrastructure.config.XessFgGpu;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XessFgInputsTest {
    private static final String RTX = "PNP-" + "a".repeat(64);
    private static final String ARC = "PNP-" + "b".repeat(64);

    @Test
    void choosesA750ForAutoAndKeepsAnExplicitAdapter() {
        var rtx = new XessFgAdapters.Adapter(new XessFgGpu(RTX, "RTX 4070 Laptop"), 100, 0x10de, 0x2820);
        var arc = new XessFgAdapters.Adapter(new XessFgGpu(ARC, "Arc A750"), 200, 0x8086, 0x56a1);
        assertEquals(arc, XessFgAdapters.select(List.of(rtx, arc), XessFgGpu.parse("auto")));
        assertEquals(rtx, XessFgAdapters.select(List.of(arc, rtx), XessFgGpu.parse(RTX + "|old label")));
        assertThrows(IllegalStateException.class, () -> XessFgAdapters.select(List.of(rtx), XessFgGpu.parse(ARC)));
        assertFalse(XessFgGpu.valid("minecraft"));
    }

    @Test
    void reprojectsFromCurrentCameraToPreviousCamera() {
        var projection = new Matrix4f().perspective((float) Math.PI / 2, 1, .1f, 100);
        var current = new XessFgView(new Matrix4f(), projection, new Vec3(10.25, 0, 0));
        var previous = new XessFgView(new Matrix4f(), projection, new Vec3(10, 0, 0));
        var clip = projection.transform(new Vector4f(0, 0, -5, 1));
        var mapped = current.reproject(previous, true).transform(new Vector4f(clip));
        assertEquals(.05f, mapped.x / mapped.w, .00001);
        var sky = current.reproject(previous, false).transform(new Vector4f(clip));
        assertEquals(0, sky.x / sky.w, .00001);
    }

    @Test
    void mapsGlDepthRangeToDxgi() {
        var projection = XessFgCapture.dxProjection(new Matrix4f().perspective((float) Math.PI / 2, 1, .1f, 100));
        var near = projection.transform(new Vector4f(0, 0, -.1f, 1));
        var far = projection.transform(new Vector4f(0, 0, -100, 1));
        assertEquals(0, near.z / near.w, .00001);
        assertEquals(1, far.z / far.w, .00001);
    }

    @Test
    void evaluatesRecognizedHandDeformationsInClipSpace() {
        var options = new XessFgPack.Options() {
            @Override public boolean flag(String key, boolean fallback) { return key.equals("WORLD_CURVATURE") || fallback; }
            @Override public float number(String key, float fallback) { return key.equals("HAND_SWAYING") ? 2 : fallback; }
        };
        var identity = new Matrix4f();
        var point = new Vector4f(2, 0, 3, 1);
        XessFgPack.BSL.handDeformation(options, new Matrix4f().m22(-1), identity, identity, 0).accept(point);
        assertEquals(-13f / 256, point.y, 1e-6f);
        assertEquals(-3, point.z, 1e-6f);
        point.set(0, 0, 0, 1);
        XessFgPack.COMPLEMENTARY.handDeformation(options, identity, identity, identity, 0).accept(point);
        assertEquals(1f / 64, point.y, 1e-6f);
        point.set(0, 0, 0, 1);
        XessFgPack.COMPLEMENTARY.handDeformation(options, identity, identity, identity,
                (float) (Math.PI / 1.5)).accept(point);
        assertEquals(-1f / 64, point.y, 1e-6f);
    }
}
