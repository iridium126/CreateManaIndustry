package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** Reads the poses submitted by Flywheel 1.0.6 after its frame plan and embedding flush. */
final class XessFgFlywheelMotion {
    private static final int MAX_MESH_INDICES = 65_536;
    private final Map<Model, float[]> meshes = new WeakHashMap<>();
    private Access access;
    private boolean failed;

    void capture(XessFgMotion motion, Engine engine, RenderContext context) {
        if (failed) return;
        try {
            if (access == null) access = new Access(engine.getClass().getClassLoader());
            var origin = engine.renderOrigin();
            var camera = context.camera().getPosition();
            var projection = new Matrix4f(context.viewProjection()).translate((float) (origin.getX() - camera.x),
                    (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
            float seconds = (context.renderer().getTicks() + context.partialTick()) / 20;
            Object manager = access.drawManager.get(engine);
            for (var entry : ((Map<?, ?>) access.instancers.get(manager)).entrySet()) {
                Object environment = access.environment.get(entry.getKey());
                Matrix4f embedding = access.embedding.getDeclaringClass().isInstance(environment)
                        ? (Matrix4f) access.embedding.get(environment) : null;
                Object instancer = entry.getValue();
                Model model = (Model) access.model.get(entry.getKey());
                if (access.pages.getDeclaringClass().isInstance(instancer)) {
                    Object[] pages = (Object[]) ((AtomicReference<?>) access.pages.get(instancer)).get();
                    for (Object page : pages) {
                        if (page == null) continue;
                        for (Object instance : (Object[]) access.pageInstances.get(page)) {
                            capture(motion, model, projection, embedding, seconds, instance);
                        }
                    }
                } else if (access.instances.getDeclaringClass().isInstance(instancer)) {
                    for (Object instance : (Iterable<?>) access.instances.get(instancer)) {
                        capture(motion, model, projection, embedding, seconds, instance);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            failed = true;
            CreateManaIndustry.LOGGER.warn("[CMI/XeSS-FG] Flywheel motion inputs unavailable", incompatible);
        }
    }

    private void capture(XessFgMotion motion, Model model, Matrix4f projection, Matrix4f embedding, float seconds, Object candidate) {
        if (!(candidate instanceof Instance instance) || !instance.handle().isVisible()) return;
        Matrix4f pose;
        if (instance instanceof RotatingInstance rotating) {
            pose = new Matrix4f().translation(rotating.x + .5f, rotating.y + .5f, rotating.z + .5f);
            float x = rotating.rotationAxisX / 127f, y = rotating.rotationAxisY / 127f, z = rotating.rotationAxisZ / 127f;
            if (x != 0 || y != 0 || z != 0) {
                float radians = (float) Math.toRadians(rotating.rotationOffset + seconds * rotating.rotationalSpeed);
                pose.rotate(radians, x, y, z);
            }
            pose.rotate(rotating.rotation).translate(-.5f, -.5f, -.5f);
        } else if (instance instanceof TransformedInstance transformed) {
            pose = new Matrix4f(transformed.pose);
        } else {
            return;
        }
        if (embedding != null) embedding.mul(pose, pose);
        float[] positions = meshes.computeIfAbsent(model, XessFgFlywheelMotion::triangles);
        if (positions.length != 0) motion.mesh(instance, model, positions, projection, pose);
    }

    private static float[] triangles(Model model) {
        int count = 0;
        for (var part : model.meshes()) {
            int indices = part.mesh().indexCount();
            if (indices % 3 != 0 || indices > MAX_MESH_INDICES - count) return new float[0];
            count += indices;
        }
        float[] positions = new float[count * 3];
        int offset = 0;
        var view = new FullVertexView();
        for (var part : model.meshes()) {
            var mesh = part.mesh();
            if (mesh.vertexCount() > MAX_MESH_INDICES) return new float[0];
            long vertices = MemoryUtil.nmemAllocChecked(view.stride() * mesh.vertexCount());
            long indices = MemoryUtil.nmemAllocChecked((long) mesh.indexCount() * Integer.BYTES);
            try {
                view.ptr(vertices);
                view.vertexCount(mesh.vertexCount());
                mesh.write(view);
                mesh.indexSequence().fill(indices, mesh.indexCount());
                for (int i = 0; i < mesh.indexCount(); i++) {
                    int index = MemoryUtil.memGetInt(indices + (long) i * Integer.BYTES);
                    if (index < 0 || index >= mesh.vertexCount()) return new float[0];
                    positions[offset++] = view.x(index);
                    positions[offset++] = view.y(index);
                    positions[offset++] = view.z(index);
                }
            } finally {
                MemoryUtil.nmemFree(indices);
                MemoryUtil.nmemFree(vertices);
            }
        }
        return positions;
    }

    private static final class Access {
        private static final String BACKEND = "dev.engine_room.flywheel.backend.engine.";
        final Field drawManager, instancers, model, environment, embedding, instances, pages, pageInstances;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            drawManager = field(loader, "EngineImpl", "drawManager");
            instancers = field(loader, "DrawManager", "instancers");
            model = field(loader, "InstancerKey", "model");
            environment = field(loader, "InstancerKey", "environment");
            embedding = field(loader, "embed.EmbeddedEnvironment", "poseComposed");
            instances = field(loader, "BaseInstancer", "instances");
            pages = field(loader, "indirect.IndirectInstancer", "pages");
            pageInstances = field(loader, "indirect.IndirectInstancer$InstancePage", "instances");
        }

        private static Field field(ClassLoader loader, String type, String name) throws ReflectiveOperationException {
            var field = Class.forName(BACKEND + type, false, loader).getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
