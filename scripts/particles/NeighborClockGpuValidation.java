import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Detached production-chain localization; timestamps and correctness gates are deliberately separate. */
public final class NeighborClockGpuValidation extends ShaderEfficiencyUpdateGpuValidation {
    static final class Terrain implements AutoCloseable{
        final int table=buffer(bytes(65536*16)),tableTexture=GL11.glGenTextures(),atlas=GL11.glGenTextures();
        Terrain(){
            ByteBuffer entries=bytes(65536*16);for(int i=0;i<65536;i++)entries.putInt(i*16+12,-1);
            for(int area=0;area<2;area++)for(int x=area==0?-4:91;x<=(area==0?4:97);x++)for(int z=area==0?-4:-147;z<=(area==0?4:-140);z++)for(int y=0;y<=9;y++){
                int h=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f;h^=h>>>16;int row=h&65535,probe=0;while(entries.getInt(row*16+12)!=-1){row=(row+1)&65535;check(++probe<8,"terrain hash fixture must remain reachable");}
                entries.putInt(row*16,x).putInt(row*16+4,y).putInt(row*16+8,z).putInt(row*16+12,0);
            }
            put(table,entries);GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,tableTexture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32I,table);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,atlas);GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_R32UI,4,4,8,0,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,bytes(512));
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        void configure(Programs p){for(int program:new int[]{p.update(),p.recovery(),p.contacts(),p.depen(),p.path()}){bool(program,"uStormCollisionEnabled",true);bool(program,"uStormCollisionOn",true);ui(program,"uStormCollisionMask",65535);}}
        public void close(){GL11.glDeleteTextures(tableTexture);GL11.glDeleteTextures(atlas);GL15.glDeleteBuffers(table);}
    }
    public static void main(String[] args)throws Exception{
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);
        long window=GLFW.glfwCreateWindow(32,32,"detached shader clocks",0,0);check(window!=0,"GL context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();check(GL.getCapabilities().GL_ARB_shader_clock,"ARB_shader_clock required");
        Path output=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(output);
        check(!Files.exists(output.resolve("clock-invocations.csv")),"Never overwrite an earlier clock run");
        int n=Integer.getInteger("cmi.clock.count",65536),storage=buffer(bytes(Math.max(1,n)*64)),texture=GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32UI,storage);
        try(var programs=Programs.load(System.getProperty("cmi.clock.source"));var raw=Files.newBufferedWriter(output.resolve("clock-invocations.csv"))){
            raw.write("scene,sample,invocation,category,clock_ticks,calls,work\n");
            for(String scene:System.getProperty("cmi.clock.scenes","ordinary,starfall,helix,dome_gather,dome_attack,corpse,crowded,missing_collision,column,recovery").split(",")){
                try(var frame=new Frame(n,1,scene.equals("terrain")?"ordinary":scene,programs,programs);var terrain=scene.equals("terrain")?new Terrain():null){
                    if(terrain!=null){terrain.configure(programs);ByteBuffer headers=read(frame.d.q.emit,Frame.size(frame.d.q.emit));headers.putFloat((2*com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.VEC4_PER_EMITTER+7)*16+4,1);put(frame.d.q.emit,headers);}
                    for(int sample=0;sample<4;sample++){
                        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storage);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);GL42.glBindImageTexture(7,texture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);
                        frame.run(programs,true);ByteBuffer values=read(storage,Math.max(1,n)*64);
                        for(int i=0;i<n;i++)for(int category=0;category<4;category++){
                            int at=i*64+category*16;long calls=Integer.toUnsignedLong(values.getInt(at+8));if(calls==0)continue;
                            long ticks=Integer.toUnsignedLong(values.getInt(at))+(Integer.toUnsignedLong(values.getInt(at+4))<<32);
                            raw.write(scene+","+sample+","+i+","+category+","+Long.toUnsignedString(ticks)+","+calls+","+Integer.toUnsignedLong(values.getInt(at+12))+"\n");
                        }
                    }raw.flush();System.out.println("Clock localization: "+scene);
                }
            }
            Files.writeString(output.resolve("instrumented-only.txt"),"No FPS or millisecond inference. Local invocation clocks overlap; categories are nested.\n");
        }finally{GL11.glDeleteTextures(texture);GL15.glDeleteBuffers(storage);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
