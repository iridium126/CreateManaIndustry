import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.*;

/** Fixed-input production update chain, independent baseline/candidate programs and raw evidence.
 * World capture/network delivery are deliberately outside this standalone fixture.
 */
public class ShaderEfficiencyUpdateGpuValidation extends HelixGpuValidation {
    record Programs(int update,int recovery,int contacts,int prepare,int schedule,int depen,int path,int feedback) implements AutoCloseable {
        static Programs load(String root) {
            System.setProperty("cmi.efficiency.sourceRoot",root);
            return new Programs(compile("update.comp"),compile("column_restore.comp"),compile("dome_contacts.comp"),
                    compile("storm_depenetration_prepare.comp"),compile("storm_depenetration_schedule.comp"),
                    compile("storm_depenetration.comp"),compile("dome_path.comp"),compile("wavecontact.comp"));
        }
        public void close(){for(int p:new int[]{update,recovery,contacts,prepare,schedule,depen,path,feedback})deleteProgram(p);}
    }
    record Result(double gpu,double cpu) {}
    static final java.lang.reflect.Field READ;
    static {try{READ=StarfallGpuBuffers.class.getDeclaredField("read");READ.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static class Frame implements AutoCloseable {
        final UpdateDomainGpuValidation.DomainFixture d;
        final StarfallGpuBuffers stars=new StarfallGpuBuffers();
        final ByteBuffer gridSeed,stateSeed;
        final int readBank,stateBuffer,feedback,stamps,restore;
        final String scene;float time;final float dt;
        boolean stateful;int inputGeneration=7;
        boolean measureGridPreparation;
        // Offline R6 boundaries. Setup rewind is excluded; callbacks are inside the full-chain timer.
        Runnable resetExtra=()->{};
        java.util.function.Consumer<String> checkpoint=label->{};
        java.util.function.Consumer<Programs> beforeUpdate=p->{},beforeContacts=p->{},afterContacts=p->{};
        // Experimental continuation driver; default retains the production two dispatches.
        java.util.function.Consumer<Programs> updateDispatch=this::dispatchDefault;
        private void dispatchDefault(Programs p){
            ui(p.update,"uModelPass",1);dispatch(p.update,Math.max(1,(d.n+63)/64));
            ui(p.update,"uModelPass",2);dispatch(p.update,(d.models+63)/64);
        }
        final int timer=GL15.glGenQueries();
        Frame(int n,int mode,String scene,Programs before,Programs after)throws Exception {
            this.scene=scene;time=scene.equals("wrap")?104857.59f:scene.equals("boundary")?10:12;
            dt=scene.equals("irregular")?.113f:1/60f;
            d=new UpdateDomainGpuValidation.DomainFixture(n,mode,new int[]{before.update,after.update,after.update});
            feedback=buffer(bytes(4+16384*24));stamps=buffer(bytes(131072*16));restore=buffer(bytes(4*64*30*4));
            for(Programs p:List.of(before,after))for(int program:new int[]{p.update,p.recovery,p.feedback}){
                ui(program,"uCapacity",n);ui(program,"uKillEmit",-1);f(program,"uTimeSec",time);f(program,"uDt",dt);
            }
            if(!scene.equals("ordinary")) {
                ByteBuffer data=read(d.q.pool,Math.max(1,n)*64);
                for(int i=0;i<n;i++){
                    int b=i*64;boolean crowded=scene.equals("crowded");
                    if(!scene.equals("recovery"))data.putFloat(b,crowded?(i%16)*.01f:(i%256-128)*.3f).putFloat(b+4,40+(i/256%128)*.1f)
                            .putFloat(b+8,crowded?(i/16%16)*.01f:(i/256%256-128)*.3f);
                    if(scene.equals("corpse")&&data.getInt(b+60)==2)data.putFloat(b+52,-.5f);
                }
                put(d.q.pool,data);d.bindings();
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
                dispatch(d.gridProgram,Math.max(1,(n+63)/64));
                var packet=scene.startsWith("dome")?dome(12288,7,scene.equals("dome_attack")?2:0,scene.equals("dome_attack")?4:1)
                        :scene.equals("helix")?helix(4096,7,2,4,false):starBenchmarkPacket(Math.min(131072,Math.max(1,n)));
                stars.rebuild(scene.equals("recovery")||scene.equals("column")?StarfallColumnGpuValidation.columns():new com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket[]{packet,null,null,null});
                if(scene.equals("wrap"))stars.clock(104857.59);
                if(scene.startsWith("dome"))warmDome(stars,before.update);
            }
            if(scene.equals("recovery")){
                stars.prepare(6);stars.bind(before.update,true);d.frame(false);stars.commit();
                ByteBuffer moving=read(d.output,Math.max(1,n)*64);d.q.build(moving,n);put(d.q.previous,bytes(16).putInt(0,n));
                int snapshot=compile("column_snapshot.comp");
                try{
                    d.bindings();stars.bind(snapshot,false);bind(22,restore);
                    GL41.glProgramUniform1i(snapshot,GL20.glGetUniformLocation(snapshot,"uColumnCount"),1);
                    GL41.glProgramUniform4iv(snapshot,GL20.glGetUniformLocation(snapshot,"uColumnSlots"),new int[]{4,-1,-1,-1});dispatch(snapshot,Math.max(1,(n+63)/64));
                    float captured=read(restore,4).getFloat(0);check(d.models==0?captured==0:captured>=1,"Recovery fixture must contain real MODEL column state, and no state for a generic-only pool");
                }finally{deleteProgram(snapshot);}
                stars.columnRestoreToken(4,StarfallColumnGpuValidation.column(0).id(),1);
                for(Programs p:List.of(before,after)){
                    GL41.glProgramUniform1i(p.recovery,GL20.glGetUniformLocation(p.recovery,"uColumnRestoreCount"),1);
                    GL41.glProgramUniform4iv(p.recovery,GL20.glGetUniformLocation(p.recovery,"uColumnRestoreSlots"),new int[]{4,-1,-1,-1});
                    GL41.glProgramUniform4iv(p.recovery,GL20.glGetUniformLocation(p.recovery,"uColumnRestoreTokens"),new int[]{1,0,0,0});
                    GL41.glProgramUniform4fv(p.recovery,GL20.glGetUniformLocation(p.recovery,"uColumnRestoreTimes"),new float[]{time,0,0,0});
                }
                d.bindings();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(d.gridProgram,Math.max(1,(n+63)/64));
            }
                        if(System.getProperty("cmi.r7.layout","").equals("paired-islands")){
                var islands=read(d.q.pool,Math.max(1,n)*64);for(int i=0;i<n;i++){int pair=i/2,at=i*64;islands.putFloat(at,(scene.equals("ordinary")?1500:0)+(pair%128)*3f+(i%2)*.5f).putFloat(at+4,(scene.equals("ordinary")?128:40)+(pair/16384)*3f).putFloat(at+8,(scene.equals("ordinary")?-2300:0)+(pair/128%128)*3f);}d.q.build(islands,n);d.bindings();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(d.gridProgram,Math.max(1,(n+63)/64));
            }
            if(System.getProperty("cmi.r7.layout","").equals("coincident")){var coincident=read(d.q.pool,Math.max(1,n)*64);for(int i=0;i<n;i++)coincident.putFloat(i*64,0).putFloat(i*64+4,128).putFloat(i*64+8,0);d.q.build(coincident,n);d.bindings();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(d.gridProgram,Math.max(1,(n+63)/64));}
            stars.prepare(7);readBank=READ.getInt(stars);stateBuffer=stars.candidateStateBuffer();
            stateSeed=stateBuffer==0?bytes(0):read(stateBuffer,size(stateBuffer));
            gridSeed=read(d.grid,size(d.grid));
            // Both versions consume exactly the same input hash, including chain order.
            for(Programs p:List.of(before,after)){
                for(int program:new int[]{p.update,p.recovery,p.feedback}){
                    ui(program,"uCapacity",n);ui(program,"uKillEmit",-1);f(program,"uTimeSec",time);f(program,"uDt",dt);
                }
                ui(p.feedback,"uLocalWaveMask",15);
            }
        }
        static int size(int buffer){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);return GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE);}
        Result run(Programs p,boolean full)throws Exception {
            resetExtra.run();
            READ.setInt(stars,readBank);stars.abort();
            if(stateBuffer!=0)put(stateBuffer,stateSeed);
            put(d.grid,gridSeed);put(d.q.counter,bytes(16));put(feedback,bytes(4));
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,stamps);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
            d.bindings();
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long began=System.nanoTime();try{
            if(measureGridPreparation){
                // Source/state rewinds above are fixture setup. Runtime per-frame grid preparation is measured.
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);
                GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);
                dispatch(d.gridProgram,Math.max(1,(d.n+63)/64));
            }
            if(stateful){stars.clock(time);for(int part:new int[]{p.update,p.recovery,p.feedback}){f(part,"uTimeSec",time);gen(part,"uPopulationGeneration",inputGeneration);}}
            stars.prepare(inputGeneration);stars.bind(p.update,true);
            bool(d.q.prepare,"uPopulationReadEnabled",true);gen(d.q.prepare,"uPopulationGeneration",inputGeneration);ui(d.q.prepare,"uPhase",0);
            dispatch(d.q.prepare,1);
            d.bindings();stars.bind(p.update,true);
            if(full&&scene.equals("recovery")){bind(22,restore);stars.bind(p.recovery,true);dispatch(p.recovery,Math.max(1,(d.n+63)/64));stars.bind(p.update,true);}
            beforeUpdate.accept(p);d.bindings();stars.bind(p.update,true);
            checkpoint.accept("prepared");
            updateDispatch.accept(p);
            checkpoint.accept("updated");
            if(full){
                beforeContacts.accept(p);
                DomeBodyContacts.run(d.gridProgram,p.contacts,d.q.pool,d.q.previous,d.output,d.q.counter,d.grid,time,dt,0,stars);afterContacts.accept(p);checkpoint.accept("contacts");
                bind(0,d.q.pool);bind(1,d.output);bind(14,d.q.previous);
                StormDepenetration.run(p.prepare,p.schedule,p.depen,time,stars);checkpoint.accept("depenetrated");
                if(stars.navigationReady())DomeGatherNavigation.run(p.path,time,stars,d.q.args);
                checkpoint.accept("navigated");
                d.bindings();bind(1,d.output);bind(22,feedback);bind(30,stamps);stars.bind(p.feedback,true);
                dispatch(p.feedback,Math.max(1,(d.n+63)/64));
                stars.commit();checkpoint.accept("published");
            }
            }finally{GL15.glEndQuery(GL33.GL_TIME_ELAPSED);}
            double cpu=(System.nanoTime()-began)/1e6;
            return new Result(GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6,cpu);
        }
        Snapshot snapshot(){
            var states=stateBuffer==0?new int[0]:words(read(stateBuffer,stateSeed.capacity()));
            var events=read(feedback,4+16384*24);int count=events.getInt(0);check(count<=16384,"feedback overflow");
            int[] eventWords=words(events);var eventRows=new ArrayList<String>();for(int i=0;i<count;i++)eventRows.add(Arrays.toString(Arrays.copyOfRange(eventWords,1+i*6,1+(i+1)*6)));
            Collections.sort(eventRows);Map<Integer,int[]> particles;
            if(stateful){int live=read(d.q.counter,16).getInt(0);check(live>=0&&live<=d.n,"stateful live bound");ByteBuffer data=read(d.output,Math.max(1,live)*64);particles=new TreeMap<>();for(int i=0;i<live;i++){int[] row=new int[16];for(int k=0;k<16;k++)row[k]=data.getInt(i*64+k*4);check(particles.put(row[14],row)==null,"stateful stable identity");}}else particles=d.snapshot();
            return new Snapshot(particles,states,eventRows);
        }
        /** Canonical compaction of one committed input for both compared paths;
         * preserves identity/animation association and the actual prior state. */
        void advance(Snapshot current)throws Exception{
            check(stateful,"explicit stateful fixture");int live=current.particles.size();var data=bytes(Math.max(1,live)*64);int at=0;for(var row:new TreeMap<>(current.particles).values())for(int word:row)data.putInt(at++*4,word);
            put(d.q.previous,bytes(16).putInt(0,live));d.q.build(data,live);inputGeneration++;var meta=read(d.q.pop,32);meta.putInt(16,inputGeneration).putInt(20,0);put(d.q.pop,meta);
            var roster=read(d.q.pop,32+d.q.modelCap*8);for(int rank=0;rank<roster.getInt(0);rank++){int slot=roster.getInt(32+rank*8),seed=data.getInt(slot*64+56);roster.putInt(36+rank*8,seed-0x3f000000);}put(d.q.pop,roster);
            if(stateBuffer!=0){for(int i=0;i<current.state.length;i++)stateSeed.putInt(i*4,current.state[i]);var field=StarfallGpuBuffers.class.getDeclaredField("state");field.setAccessible(true);for(int buffer:(int[])field.get(stars))put(buffer,stateSeed);}
            d.bindings();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,d.grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);dispatch(d.gridProgram,Math.max(1,(live+63)/64));var next=read(d.grid,gridSeed.capacity());gridSeed.clear();gridSeed.put(next).clear();time+=dt;if(scene.equals("wrap")&&time>=104857.6f)time-=104857.6f;
        }
        public void close(){d.close();stars.close();GL15.glDeleteBuffers(feedback);GL15.glDeleteBuffers(stamps);GL15.glDeleteBuffers(restore);GL15.glDeleteQueries(timer);}
    }
    record Snapshot(Map<Integer,int[]> particles,int[] state,List<String> events) {}
    static int[] words(ByteBuffer data){int[] result=new int[data.capacity()/4];data.asIntBuffer().get(result);return result;}
    static void equal(Snapshot a,Snapshot b,String cell){
        if(Boolean.getBoolean("cmi.efficiency.rules")){
            check(a.particles.keySet().equals(b.particles.keySet()),cell+" identity set");
            for(var entry:b.particles.entrySet()){
                int[] row=entry.getValue(),original=a.particles.get(entry.getKey());
                for(int word:new int[]{0,1,2,4,5,6})check(Float.isFinite(Float.intBitsToFloat(row[word])),cell+" finite position/velocity");
                check(row[15]==original[15],cell+" material/type preserved");
                check(row[3]==original[3],cell+" attack member identity preserved");
                check(row[13]==original[13],cell+" fixture health/death state preserved");
            }
            check(new HashSet<>(b.events).size()==b.events.size(),cell+" no duplicate feedback rows");
            return; // Dedicated gameplay regressions remain necessary before adopting a timed candidate.
        }
        check(a.particles.keySet().equals(b.particles.keySet()),cell+" identity set");
        for(int key:a.particles.keySet())if(!Arrays.equals(a.particles.get(key),b.particles.get(key))){
            int word=Arrays.mismatch(a.particles.get(key),b.particles.get(key));
            throw new AssertionError(cell+" particle "+key+" word "+word+": "+Integer.toHexString(a.particles.get(key)[word])+" / "+Integer.toHexString(b.particles.get(key)[word]));
        }
        if(!Arrays.equals(a.state,b.state)){int i=Arrays.mismatch(a.state,b.state);throw new AssertionError(cell+" state word "+i+": "+Integer.toHexString(a.state[i])+" / "+Integer.toHexString(b.state[i]));}
        check(a.events.equals(b.events),cell+" discrete feedback");
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);
        check(!Files.exists(out.resolve("samples.csv")),"Refusing to overwrite an earlier run: "+out);
        check(GLFW.glfwInit(),"GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"CMI exact update candidates",0,0);check(window!=0,"OpenGL context");
        var failures=new ArrayList<String>();
        var rows=new ArrayList<String>();rows.add("count,mode,scene,chain,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            Files.writeString(out.resolve("gpu.txt"),GL11.glGetString(GL11.GL_RENDERER)+"\n"+GL11.glGetString(GL11.GL_VERSION)+"\n");
            long start=System.nanoTime();
            try(var baseline=Programs.load(System.getProperty("cmi.efficiency.baseline"));var candidate=Programs.load(System.getProperty("cmi.efficiency.candidate"))){
                Files.writeString(out.resolve("compile-ms.txt"),Double.toString((System.nanoTime()-start)/1e6));
                boolean full=Boolean.getBoolean("cmi.efficiency.full");
                String scenes=System.getProperty("cmi.efficiency.scenes","ordinary,starfall,helix,dome_gather,dome_attack,corpse,crowded,wrap,boundary,irregular,missing_collision,column,recovery");
                int samples=Integer.getInteger("cmi.efficiency.samples",64);
                for(String scene:scenes.split(","))for(String count:System.getProperty("cmi.efficiency.counts","0,1000,10000,50000,65536,131072").split(","))for(int mode=0;mode<3;mode++){
                    if(!scene.equals("ordinary")&&mode==0)continue;
                    String cell=count+","+mode+","+scene+","+(full?"full":"kernel");
                    try(var frame=new Frame(Integer.parseInt(count),mode,scene,baseline,candidate)){
                        frame.run(baseline,full);var a=frame.snapshot();frame.run(baseline,full);
                        try{equal(a,frame.snapshot(),cell+" A/A");}catch(AssertionError e){failures.add(e.getMessage());System.out.println("VALIDATION FAILED: "+e.getMessage());}
                        frame.run(candidate,full);
                        try{equal(a,frame.snapshot(),cell+" A/B");}catch(AssertionError e){failures.add(e.getMessage());System.out.println("VALIDATION FAILED: "+e.getMessage());}
                        for(int warm=0;warm<16;warm++)frame.run(warm%2==0?baseline:candidate,full);
                        for(int sample=0;sample<samples;sample++){
                            Result b,c;if(sample%2==0){b=frame.run(baseline,full);c=frame.run(candidate,full);}else{c=frame.run(candidate,full);b=frame.run(baseline,full);}
                            rows.add(cell+","+sample+","+b.gpu+","+c.gpu+","+b.cpu+","+c.cpu);
                        }
                        Files.write(out.resolve("samples.csv"),rows);System.out.println((Boolean.getBoolean("cmi.efficiency.rules")?"Rule smoke (not gameplay qualification) + timed ":"Exact + timed ")+cell);
                    }
                }
            }
            Files.writeString(out.resolve("gate-mode.txt"),Boolean.getBoolean("cmi.efficiency.rules")?"rule smoke only: identities, finite kinematics, type, member, health/death, duplicate event rows; dedicated gameplay regressions required before adoption\n":"strict canonical equality\n");
            check(GL11.glGetError()==0,"final GL state");Files.writeString(out.resolve("finished.txt"),"checks="+checks+"\n");
            if(failures.isEmpty())Files.writeString(out.resolve(Boolean.getBoolean("cmi.efficiency.rules")?"rule-smoke-complete.txt":"complete.txt"),"checks="+checks+"\n");
        }finally{
            Files.write(out.resolve("samples.csv"),rows);Files.write(out.resolve("validation-failures.txt"),failures);
            GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
        if(!failures.isEmpty())throw new AssertionError("Strict equivalence failed; evidence retained: "+failures.size());
    }
}
