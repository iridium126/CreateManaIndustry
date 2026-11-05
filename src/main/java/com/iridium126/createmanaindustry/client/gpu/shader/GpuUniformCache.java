package com.iridium126.createmanaindustry.client.gpu.shader;

import java.util.function.ToIntBiFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20;

/** Render-thread, resource-owner scoped cache. Retire entries before deleting/relinking a program. */
public final class GpuUniformCache {
    private final Int2ObjectOpenHashMap<Object2IntOpenHashMap<String>> programs = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap> samplerUnits = new Int2ObjectOpenHashMap<>();
    private static final class Value {int count,a,b,c,d;boolean known;}
    private final Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<Value>> values=new Int2ObjectOpenHashMap<>();
    private long submissions,avoided;
    public long submissions(){return submissions;}
    public long avoided(){return avoided;}
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

    public boolean samplerChanged(int program,int location,int unit) {
        if(location<0)return false;
        var units=samplerUnits.get(program);
        if(units==null){units=new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();units.defaultReturnValue(Integer.MIN_VALUE);samplerUnits.put(program,units);}
        if(units.get(location)==unit)return false;units.put(location,unit);return true;
    }
    /** All engine scalar/vector writes use this same cache, including DSA writes. */
    public boolean changed(int program,int location,int count,int a,int b,int c,int d){
        if(location<0)return false;
        var uniforms=values.get(program);if(uniforms==null){uniforms=new Int2ObjectOpenHashMap<>();values.put(program,uniforms);}
        var prior=uniforms.get(location);if(prior==null){prior=new Value();uniforms.put(location,prior);}
        if(prior.known&&prior.count==count&&prior.a==a&&prior.b==b&&prior.c==c&&prior.d==d){avoided++;return false;}
        prior.known=true;prior.count=count;prior.a=a;prior.b=b;prior.c=c;prior.d=d;submissions++;return true;
    }
    public void forgetValue(int program,int location){var uniforms=values.get(program);if(uniforms!=null)uniforms.remove(location);}
    public void invalidateValues(){values.clear();}
    public void invalidateValues(int program){samplerUnits.remove(program);values.remove(program);}
    public void retire(int program) { programs.remove(program);invalidateValues(program); }
    public void clear() { programs.clear();samplerUnits.clear();values.clear(); }
}
