package com.iridium126.createmanaindustry.client.gpu.shader;

import java.util.function.ToIntBiFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20;

/** Render-thread, resource-owner scoped cache. Retire entries before deleting/relinking a program. */
public final class GpuUniformCache {
    private final Int2ObjectOpenHashMap<Object2IntOpenHashMap<String>> programs = new Int2ObjectOpenHashMap<>();
    private final ToIntBiFunction<Integer, String> lookup;
    private final float[] matrix = new float[16];

    public GpuUniformCache() { this(GL20::glGetUniformLocation); }

    public GpuUniformCache(ToIntBiFunction<Integer, String> lookup) {
        this.lookup = java.util.Objects.requireNonNull(lookup);
    }

    public int location(int program, String name) {
        if (program <= 0) throw new IllegalArgumentException("Unlinked GPU program");
        var locations = programs.get(program);
        if(locations==null){locations=new Object2IntOpenHashMap<>();locations.defaultReturnValue(Integer.MIN_VALUE);programs.put(program,locations);}
        int location=locations.getInt(name);
        if(location==Integer.MIN_VALUE){location=lookup.applyAsInt(program,name);locations.put(name,location);}
        return location;
    }

    public void matrix(int program, String name, Matrix4fc value) {
        GL20.glUniformMatrix4fv(location(program, name), false, value.get(matrix));
    }

    public void retire(int program) { programs.remove(program); }
    public void clear() { programs.clear(); }
}
