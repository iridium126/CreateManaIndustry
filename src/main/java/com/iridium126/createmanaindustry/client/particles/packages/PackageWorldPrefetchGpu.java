package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Asynchronous look-ahead requests for world sections needed by free-package sweeps. */
public final class PackageWorldPrefetchGpu implements AutoCloseable {
    private static final String[] UNIFORMS={"uWorldReady","uWorldOriginSection","uWorldTableMask","uWorldSlotWords","uWorldShapeCapacity",
            "uCount","uLookahead","uSafetyLookahead","uRequestCapacity","uTouchWordCount"};
    private static final int OUTPUT_BINDING=13;
    private static final int USAGE_BINDING=14;
    private final int program,requests;
    private final int[] locations=new int[UNIFORMS.length];
    private final PackageCollisionRequests decoder=new PackageCollisionRequests();
    private final PackageReadbackRing ring;
    private final long epoch;
    private long sequence,submitted,completed,skipped,overflow,lastLatency;
    private boolean closed;

    public PackageWorldPrefetchGpu(long epoch,Function<String,String> source) {
        if(epoch<=0)throw new IllegalArgumentException("Collision prefetch epoch");this.epoch=epoch;
        int shader=0,linked=0,buffer=0;PackageReadbackRing nextRing=null;
        try {
            String text=source.apply("packages/world_prefetch.comp");if(text==null||text.isBlank())throw new IllegalArgumentException("Missing world prefetch shader");
            shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,"#version 450 core\n"+text);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            linked=GL20.glCreateProgram();GL20.glAttachShader(linked,shader);GL20.glLinkProgram(linked);
            if(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
            for(int i=0;i<UNIFORMS.length;i++)locations[i]=GL20.glGetUniformLocation(linked,UNIFORMS[i]);
            buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,PackageCollisionRequests.BYTES,GL15.GL_DYNAMIC_DRAW);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            try(var stack=MemoryStack.stackPush()) {
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,PackageCollisionRequests.HEADER_BYTES,
                        GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,PackageCollisionRequests.TOUCH_OFFSET,
                        PackageCollisionRequests.TOUCH_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
            nextRing=new PackageReadbackRing(PackageCollisionRequests.BYTES);
        } catch(RuntimeException failure) {
            if(nextRing!=null)nextRing.close();if(buffer!=0)GL15.glDeleteBuffers(buffer);if(linked!=0)GL20.glDeleteProgram(linked);throw failure;
        } finally {if(shader!=0)GL20.glDeleteShader(shader);}
        program=linked;requests=buffer;ring=nextRing;
    }

    /** Called once before a free-body physical step. Full readback rings skip this prefetch pass. */
    public boolean capture(int bodyBuffer,int count,float lookahead,PackageCollisionGpu.View world) {
        return capture(bodyBuffer,count,lookahead,Math.min(.05f,lookahead),world);
    }
    public boolean capture(int bodyBuffer,int count,float lookahead,float safetyLookahead,PackageCollisionGpu.View world) {
        open();if(count<0||count>PackageCollisionRequests.MAX_BODIES||!Float.isFinite(lookahead)||lookahead<0||lookahead>2
                ||!Float.isFinite(safetyLookahead)||safetyLookahead<0||safetyLookahead>lookahead)
            throw new IllegalArgumentException("Collision prefetch range");
        if(count==0)return false;
        if(ring.pending()==PackageReadbackRing.SLOTS){skipped++;return false;}
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,requests);
        try(var stack=MemoryStack.stackPush()) {
            GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,PackageCollisionRequests.HEADER_BYTES,
                    GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,PackageCollisionRequests.TOUCH_OFFSET,
                    PackageCollisionRequests.TOUCH_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
        }
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodyBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,OUTPUT_BINDING,requests);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,USAGE_BINDING,requests,PackageCollisionRequests.TOUCH_OFFSET,
                PackageCollisionRequests.TOUCH_BYTES);
        GL20.glUseProgram(program);GL30.glUniform1ui(locations[5],count);GL20.glUniform1f(locations[6],lookahead);
        GL20.glUniform1f(locations[7],safetyLookahead);
        GL30.glUniform1ui(locations[8],PackageCollisionRequests.MAX_REQUESTS);
        GL30.glUniform1ui(locations[9],PackageCollisionRequests.MAX_TOUCH_WORDS);
        boolean ready=world.bind(locations,0,true);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,requests);
        try(var stack=MemoryStack.stackPush()) {
            long tableVersion=ready?world.version():Long.MIN_VALUE;
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,8,stack.ints((int)tableVersion,(int)(tableVersion>>>32)));
        }
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL43.glDispatchCompute((count+63)/64,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        if(!ring.submit(requests,epoch,sequence)) {skipped++;return false;}
        sequence++;submitted+=PackageCollisionRequests.BYTES;return true;
    }
    /** Zero-timeout consume. The callback may queue numeric requests only, never access world state. */
    public void poll(Consumer<PackageCollisionCache.Section> consumer) {
        poll(consumer,null);
    }
    public void poll(Consumer<PackageCollisionCache.Section> consumer,PackageCollisionRequests.Usage usage) {
        open();ring.poll(epoch,snapshot->{int total=decoder.consume(snapshot.bytes(),consumer,usage);
            if(total>PackageCollisionRequests.MAX_REQUESTS)overflow++;completed++;
            lastLatency=System.nanoTime()-snapshot.submittedNanos();});
    }
    public String stats(){return "world prefetch submitted="+submitted+" B, completed="+completed+", pending="+ring.pending()
            +", skipped="+skipped+", overflow="+overflow+", latency="+String.format(java.util.Locale.ROOT,"%.3f",lastLatency/1e6)+" ms";}
    private void open(){if(closed)throw new IllegalStateException("World prefetch closed");}
    @Override public void close(){if(closed)return;closed=true;ring.close();GL15.glDeleteBuffers(requests);GL20.glDeleteProgram(program);}
}
