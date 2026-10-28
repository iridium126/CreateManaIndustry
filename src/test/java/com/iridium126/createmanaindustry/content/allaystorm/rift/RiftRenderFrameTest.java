package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftRenderFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiftRenderFrameTest {
    @Test void renderFramesOwnCameraCopiesAndInvalidateAcrossSizeAndGeneration(){
        var view=new Matrix4f().rotateY(.1f);var projection=new Matrix4f().perspective(1,1,.1f,1000);
        var a=RiftRenderFrame.begin(5,1920,1080,.1f,Vec3.ZERO,view,projection);
        var saved=new Matrix4f(a.view());view.rotateY(.2f);projection.scale(2);
        assertEquals(saved,a.view());
        var b=RiftRenderFrame.begin(5,1920,1080,.1f,Vec3.ZERO,view,projection);
        assertTrue(b.number()>a.number());assertNotEquals(a.view(),b.view());assertSame(b,RiftRenderFrame.current());
        assertTrue(b.matches(5,1920,1080));assertFalse(b.matches(6,1920,1080));assertFalse(b.matches(5,960,540));
        RiftRenderFrame.clear();assertNull(RiftRenderFrame.current());
    }
}
