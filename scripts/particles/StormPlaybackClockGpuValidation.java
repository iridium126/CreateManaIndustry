import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.particles.engine.NativeStormDisplayClock;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Exercises production history publication with vanilla-style world clock corrections. */
public class StormPlaybackClockGpuValidation extends StormIndexDisplayGpuValidation {
    static void correctedClock(int fps)throws Exception{
        var type=Class.forName("com.iridium126.createmanaindustry.client.particles.engine.StormProjectionGpu");
        var constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);
        Object projection=constructor.newInstance(source("storm_projection.comp"));
        var receive=type.getDeclaredMethod("receive",long.class,NativeStormSimulation.FrozenDisplay.class);receive.setAccessible(true);
        var view=type.getDeclaredMethod("playback",double.class,boolean.class);view.setAccessible(true);
        int size=STORM_DISPLAY_HEADER_BYTES+STORM_DISPLAY_RECORD_BYTES+STORM_COLUMN_SLOTS*STORM_COLUMN_MEMBERS*STORM_DISPLAY_COLUMN_BYTES;
        int shared=buffer(bytes(size));long accepted=0,world=0;
        try{
            for(int frame=0;frame<fps*12;frame++){
                double logical=20.0*frame/fps;
                while(accepted+1+.1+.65*(1+Math.sin((accepted+1)*.37))*.5<=logical){
                    long seq=++accepted;
                    // Time packets may repeat, rewind or skip world ticks; simulation still advances once.
                    world+=seq%60==0?-1:seq%40==0?0:seq%20==0?3:1;
                    long gameTime=world;
                    var data=bytes(size);data.putInt(0,STORM_DISPLAY_MAGIC).putInt(4,ABI).putInt(8,1).putInt(12,1)
                            .putLong(16,seq).putLong(24,seq).putLong(32,1).putInt(40,1);
                    int row=STORM_DISPLAY_HEADER_BYTES;
                    data.putFloat(row,seq).putFloat(row+16,seq-1).putFloat(row+48,1).putInt(row+64,0);
                    put(shared,data);
                    receive.invoke(projection,1L,new NativeStormSimulation.FrozenDisplay(){
                        public int buffer(){return shared;}public long sequence(){return seq;}public long tick(){return seq;}
                        public long gameTime(){return gameTime;}public long identity(){return 1;}public void close(){}
                    });
                }
                if(accepted==0)continue;
                var display=(NativeStormDisplayClock.View)view.invoke(projection,logical,false);
                if(logical<4)continue;
                int bank=(int)field(projection,"playbackBank");int[] history=(int[])field(projection,"playback");
                var row=read(history[bank],64);var w=display.weights();
                double position=row.getFloat(0)*w.oldest()+row.getFloat(16)*w.previous()+row.getFloat(32)*w.current()+row.getFloat(48)*w.anchor();
                check(Math.abs(position-(logical-2))<.00005,"corrected world clock preserves 100ms motion at "+fps+" FPS, tick "+logical+": "+position);
                check(w.anchor()==0,"world time correction must not start display recovery");
            }
        }finally{((AutoCloseable)projection).close();GL15.glDeleteBuffers(shared);}
    }
    public static void main(String[] args)throws Exception{
        Path out=Path.of(ParticleFixtureOptions.parse(args).getOrDefault("output","build/particle-engine/rt-display-clock"));Files.createDirectories(out);
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"Storm playback clock",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        var report=new LinkedHashMap<String,Object>();report.put("passed",false);
        try{for(int fps:new int[]{30,60,144,240})correctedClock(fps);check(GL11.glGetError()==0,"GL state");report.put("passed",true);}
        finally{report.put("checks",checks);report.put("gpu",GL11.glGetString(GL11.GL_RENDERER));report.put("scope","production GPU history and logical playback; periodic duplicate/backward/forward world timestamps; 30/60/144/240 FPS; variable completion phase");Files.writeString(out.resolve("playback-clock.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));GpuValidationSupport.close(window);}
        System.out.println("Storm playback clock GPU: "+checks+" checks passed");
    }
}
