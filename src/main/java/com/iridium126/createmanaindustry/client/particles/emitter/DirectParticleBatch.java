package com.iridium126.createmanaindustry.client.particles.emitter;

import java.nio.FloatBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;

/** Bounded render-thread input. It is retired only after its owner publishes the frame. */
public final class DirectParticleBatch {
    public static final int LIMIT = 16384, WORDS = ParticleLayout.COMMAND_WORDS;
    @FunctionalInterface public interface Replay {
        void accept(double x, double y, double z, double vx, double vy, double vz, int color);
    }
    private double[] inputs = new double[0];
    private int[] colors = new int[0];
    private int[] born = new int[0];
    private FloatBuffer staging = BufferUtils.createFloatBuffer(0);
    private int count, ready;
    private final boolean tickGated;
    public DirectParticleBatch(){this(false);}
    public DirectParticleBatch(boolean tickGated){this.tickGated=tickGated;}

    public boolean offer(double x, double y, double z, double vx, double vy, double vz, int color) {
        return offer(x,y,z,vx,vy,vz,color,0);
    }
    public boolean offer(double x, double y, double z, double vx, double vy, double vz, int color,int bornTick) {
        if (count == LIMIT || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite((float)x) || !Float.isFinite((float)y) || !Float.isFinite((float)z)
                || !Float.isFinite((float)(vx*20)) || !Float.isFinite((float)(vy*20)) || !Float.isFinite((float)(vz*20))) return false;
        if (count == colors.length) {
            int capacity = Math.min(LIMIT, Math.max(64, colors.length * 2));
            inputs = Arrays.copyOf(inputs, capacity * 6);
            colors = Arrays.copyOf(colors, capacity);
            born = Arrays.copyOf(born, capacity);
            staging = BufferUtils.createFloatBuffer(capacity * WORDS);
        }
        int b = count * 6;
        inputs[b] = x; inputs[b+1] = y; inputs[b+2] = z;
        inputs[b+3] = vx; inputs[b+4] = vy; inputs[b+5] = vz;
        colors[count] = color;born[count++]=bornTick;
        if(!tickGated)ready=count;
        return true;
    }

    public int count() { return count; }
    public int ready() { return ready; }
    public void tick(){ready=count;}

    public FloatBuffer encode(int emitter, int frameSeed) {
        staging.clear();
        for (int i=0; i<ready; i++) {
            int b=i*6;
            boolean gravity=inputs[b+3]!=0 && inputs[b+4]!=0 && inputs[b+5]!=0;
            int packed=(colors[i]&0xffffff)|(gravity?0x80000000:0);
            float seed=(((long)frameSeed*2654435761L+i*73856093L)>>>8)%1_000_000*.000001f;
            staging.put((float)inputs[b]).put((float)inputs[b+1]).put((float)inputs[b+2]).put(1);
            staging.put(emitter).put(seed).put(i).put(Float.intBitsToFloat(born[i]));
            staging.put((float)(inputs[b+3]*20)).put((float)(inputs[b+4]*20))
                    .put((float)(inputs[b+5]*20)).put(Float.intBitsToFloat(packed));
        }
        return staging.flip();
    }

    public void replay(Replay consumer) {
        int pending=count;
        count=ready=0;
        for (int i=0; i<pending; i++) {
            int b=i*6;
            consumer.accept(inputs[b],inputs[b+1],inputs[b+2],inputs[b+3],inputs[b+4],inputs[b+5],colors[i]);
        }
    }

    public void commit(){
        int remaining=count-ready;
        System.arraycopy(inputs,ready*6,inputs,0,remaining*6);
        System.arraycopy(colors,ready,colors,0,remaining);
        System.arraycopy(born,ready,born,0,remaining);
        count=remaining;ready=0;
    }
    public void clear() { count=ready=0; }
}
