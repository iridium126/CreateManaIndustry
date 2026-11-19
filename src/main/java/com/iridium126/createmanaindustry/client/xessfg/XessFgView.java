package com.iridium126.createmanaindustry.client.xessfg;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

record XessFgView(Matrix4fc modelView, Matrix4fc projection, Vec3 camera, XessFgCoordinates coordinates) {
    XessFgView(Matrix4fc modelView, Matrix4fc projection, Vec3 camera) {
        this(new Matrix4f(modelView), new Matrix4f(projection), camera, XessFgCoordinates.VANILLA);
    }

    Matrix4f reproject(XessFgView previous, boolean useTranslation) {
        var transform = new Matrix4f(previous.projection).mul(previous.modelView);
        if (useTranslation) {
            var delta = camera.subtract(previous.camera);
            transform.translate((float) delta.x, (float) delta.y, (float) delta.z);
        }
        return transform.mul(new Matrix4f(projection).mul(modelView).invert());
    }

    Matrix4f sdkView() {
        return new Matrix4f(modelView).translate(-(float) camera.x, -(float) camera.y, -(float) camera.z);
    }
}
