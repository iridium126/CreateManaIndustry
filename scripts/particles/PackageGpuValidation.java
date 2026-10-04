import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePhysicsGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageSimulationClock;
import com.iridium126.createmanaindustry.client.particles.packages.PackageForceScene;
import com.iridium126.createmanaindustry.client.particles.packages.PackageForceGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMixedPhysicsGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainTrackGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainEventChannel;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingGeometry;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingDynamicBoxes;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingCollisionCache;
import com.iridium126.createmanaindustry.client.particles.packages.PackageMovingCollisionGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageReadbackRing;
import com.iridium126.createmanaindustry.client.particles.packages.PackageAdmissionTracker;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePoolGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainFramesGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageFreeUpload;
import com.iridium126.createmanaindustry.client.particles.packages.PackageFreeAcquisitionGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageControlQueue;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainAcquisitionGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainUpload;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePoseQueryGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageObserverGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageFreePickQueue;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainCheckpointGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainUseQueue;
import com.iridium126.createmanaindustry.client.particles.packages.PackageModelCache;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaChannel;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDeltaJournal;
import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionCache;
import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRequests;
import com.iridium126.createmanaindustry.client.particles.packages.PackageWorldPrefetchGpu;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAckRanges;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageBatchDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainEventCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAuthority;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainTrack;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainGpuFrame;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageOutputPose;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageControlBatchCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundChainPackagePacket;
import net.minecraft.resources.ResourceLocation;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

/** Driver validation of package kernels; does not enable gameplay takeover. */
public class PackageGpuValidation {
    static int checks;
    static boolean irisNativeTransform;
    static String source(String name) {
        return ParticleShaderSource.loadParticle(name,path->{
            // Match ResourceManager's namespace-relative lookup in the processed runtime resources.
            try(var stream=PackagePoolGpu.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/"+path)) {
                if(stream==null)throw new IllegalArgumentException("Missing runtime resource: "+path);
                return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
    }
    static void sourceContract() {
        boolean rejected=false;
        try {source("assets/createmanaindustry/shaders/particles/packages/pool_select.comp");}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"filesystem shader paths must not reach ResourceManager");
        check(!source("packages/draw_count.comp").contains("#pragma cmi_include"),"runtime includes were not resolved");
        Map<String,String> cycle=Map.of("shaders/particles/cycle.glsl","#pragma cmi_include cycle.glsl");
        rejected=false;
        try {ParticleShaderSource.loadParticle("cycle.glsl",cycle::get);}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"recursive shader includes must be bounded");
    }
    static int feedbackProgram(String vertex,String... varyings) {
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,vertex);GL20.glCompileShader(shader);
            check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"merged vertex compilation: "+GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);
            GL30.glTransformFeedbackVaryings(program,varyings,GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(program);
            check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"merged feedback link: "+GL20.glGetProgramInfoLog(program));
            return program;
        }catch(RuntimeException|AssertionError failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }
    static int graphicsProgram(String vertex,String control,String evaluation,String fragment) {
        int program=GL20.glCreateProgram();int[] shaders=new int[4];
        String[] sources={vertex,control,evaluation,fragment};
        int[] kinds={GL20.GL_VERTEX_SHADER,GL40.GL_TESS_CONTROL_SHADER,GL40.GL_TESS_EVALUATION_SHADER,GL20.GL_FRAGMENT_SHADER};
        try {
            for(int i=0;i<sources.length;i++)if(sources[i]!=null) {
                shaders[i]=GL20.glCreateShader(kinds[i]);GL20.glShaderSource(shaders[i],sources[i]);GL20.glCompileShader(shaders[i]);
                check(GL20.glGetShaderi(shaders[i],GL20.GL_COMPILE_STATUS)!=0,"graphics stage "+i+": "+GL20.glGetShaderInfoLog(shaders[i]));
                GL20.glAttachShader(program,shaders[i]);
            }
            GL20.glLinkProgram(program);check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"graphics link: "+GL20.glGetProgramInfoLog(program));
            return program;
        }catch(RuntimeException|AssertionError failure){GL20.glDeleteProgram(program);throw failure;}
        finally{for(int shader:shaders)if(shader!=0)GL20.glDeleteShader(shader);}
    }
    static void mergedPackageVertices() {
        var injector=new com.iridium126.createmanaindustry.client.particles.shaderpack.PackageVertexInjector();
        String pack="""
            #version 120
            attribute vec4 at_tangent;
            attribute vec2 mc_midTexCoord;
            attribute vec2 mc_Entity;
            attribute vec3 at_midBlock;
            varying vec4 probeColor,probeNormal,probeTangent,probeMid,probeLight,probeEntity,probeBlock;
            void main() {
                gl_Position=ftransform();
                probeColor=gl_Color;probeNormal=vec4(gl_Normal,0);
                probeTangent=at_tangent;probeMid=vec4(mc_midTexCoord,0,1);
                probeLight=gl_MultiTexCoord1;probeEntity=vec4(mc_Entity,0,0);
                probeBlock=vec4(at_midBlock,0);
            }
            """;
        String patched=injector.patch(pack,PackageGpuValidation::source,"package validation");
        if(irisNativeTransform) {
            var nativeStages=net.irisshaders.iris.pipeline.transform.TransformPatcher.patchVanilla("cmi_package_validation",patched,null,null,null,
                    "#version 120\nvarying vec4 probeColor;void main(){gl_FragColor=probeColor;}",net.irisshaders.iris.gl.blending.AlphaTests.ONE_TENTH_ALPHA,false,true,
                    new net.irisshaders.iris.gl.state.ShaderAttributeInputs(true,true,false,true,true),new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
            patched=nativeStages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.VERTEX);
            check(patched!=null && nativeStages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.FRAGMENT)!=null,"Iris native stage transform");
            int linked=graphicsProgram(patched,null,null,nativeStages.get(net.irisshaders.iris.pipeline.transform.PatchShaderType.FRAGMENT));
            GL20.glDeleteProgram(linked);
        }
        check(!patched.contains("buffer Pool"),"merged package must not collide with pack SSBO bindings");
        check(!patched.contains("ftransform()"),"package ftransform remained native");
        for(String width:new String[]{"float","vec2","vec3","vec4"}) {
            String sum="";for(String attr:new String[]{"at_tangent","mc_midTexCoord","mc_Entity","at_midBlock"})sum+="+vec4("+attr+(width.equals("vec2")?",0,1":width.equals("vec3")?",1":"")+")";
            String input="#version 120\nattribute "+width+" at_tangent;attribute "+width+" mc_midTexCoord;attribute "+width+" mc_Entity;attribute "+width+" at_midBlock;void main(){gl_Position=gl_Vertex"+sum+";}";
            int p=feedbackProgram(injector.patch(input,PackageGpuValidation::source,"attribute widths"),"gl_Position");GL20.glDeleteProgram(p);
        }
        int shared=feedbackProgram(injector.patch("#version 120\nattribute vec4 at_tangent,mc_midTexCoord,other;void main(){gl_Position=gl_Vertex+other+at_tangent+mc_midTexCoord;}",PackageGpuValidation::source,"shared declaration"),"gl_Position");
        GL20.glDeleteProgram(shared);
        boolean failed=false;try{injector.patch("#version 120\nuniform vec3 cmi_CameraPos;void main(){gl_Position=gl_Vertex;}",PackageGpuValidation::source,"collision");}
        catch(IllegalArgumentException expected){failed=true;}check(failed,"merged namespace collision must fail closed");
        int ordinary=feedbackProgram("#version 450 core\n"+source("packages/package.vsh"),"gl_Position","vColor");
        int merged=feedbackProgram(patched,"gl_Position","probeColor","probeNormal","probeTangent","probeMid","probeLight","probeEntity","probeBlock");
        int pool=buffer(bodies(1)),attachment=buffer(BufferUtils.createByteBuffer(PackagePoolGpu.ATTACHMENT_BYTES)),lightBuffer=buffer(BufferUtils.createByteBuffer(4)),output=buffer(BufferUtils.createByteBuffer(128));
        int vao=GL30.glGenVertexArrays(),light=texture(2,16);int[] views=new int[3];GL11.glGenTextures(views);
        GL30.glBindVertexArray(vao);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachment);
        for(int i=0;i<3;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,views[i]);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,i==2?GL30.GL_R32UI:GL30.GL_RGBA32F,i==0?pool:i==1?attachment:lightBuffer);
        }
        float[] identity=new float[16];new Matrix4f().get(identity);
        float[] l0={.16169f,.80845f,-.56594f},l1={-.16169f,.80845f,.56594f};
        for(int p:new int[]{ordinary,merged}) {
            GL20.glUseProgram(p);
            for(String name:new String[]{"ModelViewMat","ProjMat","cmi_ModelView","iris_ProjMat","iris_ModelViewMat"})GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,identity);
            GL20.glUniform3f(GL20.glGetUniformLocation(p,p==ordinary?"uCamPos":"cmi_CameraPos"),.25f,-.75f,1.25f);
            GL20.glUniform3fv(GL20.glGetUniformLocation(p,p==ordinary?"uLight0":"cmi_Light0"),l0);
            GL20.glUniform3fv(GL20.glGetUniformLocation(p,p==ordinary?"uLight1":"cmi_Light1"),l1);
        }
        GL20.glUseProgram(ordinary);GL20.glUniform1i(GL20.glGetUniformLocation(ordinary,"uLightmap"),2);
        GL20.glUseProgram(merged);GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_PackagePool"),10);
        GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_PackageAttachment"),11);GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_PackageLight"),12);
        GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_BlockId"),713);
        GL20.glVertexAttrib3f(0,.2f,.3f,.4f);GL20.glVertexAttrib2f(1,.125f,.875f);GL20.glVertexAttrib4f(3,.7f,.5f,.3f,.8f);
        GL20.glVertexAttrib3f(5,0,1,0);GL20.glVertexAttrib4f(6,1,0,0,-1);GL20.glVertexAttrib2f(7,.4f,.6f);
        Random random=new Random(837);
        try {
            for(int flags:new int[]{0,1,3,9,11})for(int rig=0;rig<2;rig++)for(float pt:new float[]{0,.25f,.5f,1})for(int run=0;run<16;run++) {
                var state=bodies(1);var extra=BufferUtils.createByteBuffer(PackagePoolGpu.ATTACHMENT_BYTES);
                float x=random.nextFloat()*4,y=random.nextFloat()*4,z=random.nextFloat()*4,yaw=random.nextFloat()*360;
                int packed=(flags<<24)|(run<<4)|((15-run)<<20);
                state.putFloat(0,x).putFloat(4,y).putFloat(8,z).putFloat(16,x-.2f).putFloat(20,y+.1f).putFloat(24,z-.3f);
                state.putFloat(32,x+.8f).putFloat(36,y+1).putFloat(40,z+.4f).putInt(44,packed).putFloat(48,yaw+210).putFloat(52,yaw).putFloat(56,23/16f);
                extra.putFloat(0,x+.6f).putFloat(4,y+.9f).putFloat(8,z+.1f).putFloat(16,.001f).putFloat(20,-.002f).putFloat(24,.003f);
                Matrix4f parent=null,reference=null;
                if((flags&8)!=0) {
                    parent=new Matrix4f().translate(7,-5,3).rotateXYZ(run*.13f,run*.21f,run*.07f).scale(2,.5f,3);
                    for(int p=0;p<3;p++) {
                        var local=new Vector3f(state.getFloat(p*16),state.getFloat(p*16+4),state.getFloat(p*16+8));
                        int a=32+(p==0?6:p==1?7:8)*16;
                        extra.putFloat(a,local.x).putFloat(a+4,local.y).putFloat(a+8,local.z);
                        parent.transformPosition(local);
                        state.putFloat(p*16,local.x).putFloat(p*16+4,local.y).putFloat(p*16+8,local.z);
                    }
                    for(int p=0;p<2;p++)for(int row=0;row<3;row++)for(int col=0;col<4;col++)
                        extra.putFloat(32+p*48+row*16+col*4,parent.get(col,row));
                    float angle=yaw-150*pt;
                    var target=new Vector3f(x+.6f+.2f*pt,y+.9f+.1f*pt,z+.1f+.3f*pt);
                    var d=new Vector3f(target).add(0,.5f,0).sub(new Vector3f(x-.2f,y+.1f,z-.3f).lerp(new Vector3f(x,y,z),pt));
                    new Matrix4f().rotateY((float)Math.toRadians(-angle)).transformDirection(d);
                    float zr=(float)Math.toDegrees(Math.atan2(-d.x,d.y)),xr=(float)Math.toDegrees(Math.atan2(d.z,d.y));
                    reference=new Matrix4f(parent).translate(target).translate(0,10/16f,0).rotateY((float)Math.toRadians(angle))
                            .rotateZ((float)Math.toRadians(Math.clamp(zr*.5f,-25,25))).rotateX((float)Math.toRadians(Math.clamp(xr*.5f,-25,25)));
                    if(rig==1&&(flags&2)!=0)reference.rotateY((float)Math.PI);
                    reference.translate(-.5f,-.5f,-.5f).translate(0,-23/16f+7/16f,0);
                }
                putBuffer(pool,state);putBuffer(attachment,extra);var sampled=BufferUtils.createByteBuffer(4).putInt(0,0x80000000|(5<<4)|(9<<20));putBuffer(lightBuffer,sampled);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
                GL20.glVertexAttrib3f(2,0,run%5==0?0:1,0);GL30.glVertexAttribI2ui(4,0,rig);
                ByteBuffer expected=null;
                for(int p:new int[]{ordinary,merged}) {
                    GL20.glUseProgram(p);GL20.glUniform1f(GL20.glGetUniformLocation(p,p==ordinary?"uPartialTick":"cmi_PartialTick"),pt);
                    GL20.glUniform1i(GL20.glGetUniformLocation(p,p==ordinary?"uLightingMode":"cmi_LightingMode"),run%2);
                    GL20.glUniform1i(GL20.glGetUniformLocation(p,p==ordinary?"uConstantAmbient":"cmi_ConstantAmbient"),(run/2)%2);
                    GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
                    GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,1);
                    GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                    var got=readBuffer(output,128);
                    if(reference!=null) {
                        var position=reference.transformPosition(new Vector3f(.2f,.3f,.4f)).sub(.25f,-.75f,1.25f);
                        for(int axis=0;axis<3;axis++)check(Math.abs(got.getFloat(axis*4)-position.get(axis))<3e-5,"framed native Create parent/pendulum vertex reference");
                        if(p==merged) {
                            var n=new org.joml.Matrix3f(reference).invert().transpose().transform(new Vector3f(0,1,0)).normalize();
                            var t=reference.transformDirection(new Vector3f(1,0,0)).normalize();
                            for(int axis=0;axis<3;axis++) {
                                check(Math.abs(got.getFloat(32+axis*4)-n.get(axis))<3e-5,"framed inverse-transpose face normal");
                                check(Math.abs(got.getFloat(48+axis*4)-t.get(axis))<3e-5,"framed forward tangent");
                            }
                        }
                    }
                    if(p==ordinary){expected=got;continue;}
                    for(int j=0;j<4;j++)check(Math.abs(got.getFloat(j*4)-expected.getFloat(j*4))<2e-5,"merged pose parity");
                    for(int j=0;j<4;j++)check(Math.abs(got.getFloat(16+j*4)-new float[]{.7f,.5f,.3f,.8f}[j])<2e-5,"shaderpack tint acquired fake diffuse");
                    float nx=got.getFloat(32),ny=got.getFloat(36),nz=got.getFloat(40),tx=got.getFloat(48),ty=got.getFloat(52),tz=got.getFloat(56);
                    check(Math.abs(nx*nx+ny*ny+nz*nz-1)<2e-5,"merged face normal length");
                    check(Math.abs(nx*tx+ny*ty+nz*tz)<2e-5,"merged tangent orthogonality");check(got.getFloat(60)==-1,"mirrored tangent sign");
                    check(got.getFloat(64)==.4f && got.getFloat(68)==.6f,"merged quad midpoint UV");
                    check(got.getFloat(80)==run*16 && got.getFloat(84)==(15-run)*16,"package native packed light");
                    check(got.getFloat(96)==713 && got.getFloat(100)==-1,"package material id");
                    for(int j=0;j<3;j++) {
                        int value=(byte)(int)((.5f-got.getFloat(j*4))*64f);
                        check(got.getFloat(112+j*4)==value,"Iris immediate SBB midpoint signed byte");
                    }
                }
            }
            GL20.glUseProgram(merged);GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_SampledLighting"),1);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            var got=readBuffer(output,128);check(got.getFloat(80)==80 && got.getFloat(84)==144,"merged GPU sampled light/verification bit");
            try(var fixture=new DrawPassFixture(2,false)) {
                var vertices=BufferUtils.createByteBuffer(9*48);int[] order={0,1,2,2,3,0};float[][] q={{0,0,0,0},{1,0,1,0},{1,1,1,1},{0,1,0,1}};
                for(int v=0;v<9;v++) {
                    var point=q[v<6?order[v]:v-6];int off=v*48;
                    vertices.putFloat(off,point[0]).putFloat(off+4,point[1]).putFloat(off+12,point[2]).putFloat(off+16,point[3]);
                    vertices.putFloat(off+28,1);for(int j=0;j<4;j++)vertices.putFloat(off+32+j*4,1);
                }
                var ranges=BufferUtils.createByteBuffer(32).putInt(4,6).putInt(16,6).putInt(20,3);
                fixture.bridge.uploadMeshes(vertices,ranges,2);fixture.stage(new float[24]);
                fixture.bridge.preparePass(PackagePoolGpu.DrawPass.GBUFFER,fixture.pool,new float[24],16,0,-32);
                check(!fixture.bridge.bindPassTbos(PackagePoolGpu.DrawPass.GBUFFER,10,1),"merged mesh unexpectedly has light atlas");
                GL20.glUseProgram(merged);GL20.glUniform1i(GL20.glGetUniformLocation(merged,"cmi_SampledLighting"),0);
                GL20.glUniform1f(GL20.glGetUniformLocation(merged,"cmi_PartialTick"),1);
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,output);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,15L*128,GL15.GL_DYNAMIC_READ);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);
                fixture.bridge.drawPreparedLayer(PackagePoolGpu.DrawPass.GBUFFER,0);fixture.bridge.drawPreparedLayer(PackagePoolGpu.DrawPass.GBUFFER,1);
                GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                var drawn=readBuffer(output,15*128);
                for(int v=0;v<15;v++) {
                    int off=v*128;boolean rig=v>=12;float u=rig?2/3f:.5f,w=rig?1/3f:.5f;
                    check(Math.abs(drawn.getFloat(off+64)-u)<1e-6 && Math.abs(drawn.getFloat(off+68)-w)<1e-6,"indirect VBO midpoint/baseInstance");
                    float nx=drawn.getFloat(off+32),nz=drawn.getFloat(off+40);
                    check(v<6?Math.abs(nx+1)<1e-6:Math.abs(nz-(rig?-1:1))<1e-6,"indirect VBO face normal/rig reversal");
                    check(drawn.getFloat(off+60)==1,"indirect VBO tangent handedness");
                    check(drawn.getFloat(off+80)==240 && drawn.getFloat(off+84)==240,"indirect TBO native light");
                }
                fixture.bridge.unbindPassTbos(10);
            }
        }finally {
            GL20.glUseProgram(0);GL30.glBindVertexArray(0);GL20.glDeleteProgram(ordinary);GL20.glDeleteProgram(merged);GL30.glDeleteVertexArrays(vao);
            for(int b:new int[]{pool,attachment,lightBuffer,output})GL15.glDeleteBuffers(b);
            GL11.glDeleteTextures(views);GL11.glDeleteTextures(light);GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static ByteBuffer bodies(int n){return BufferUtils.createByteBuffer(n*64);}
    static void body(ByteBuffer b,int i,float x,float y,float z,float mass){
        int p=i*64;b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,mass);
        b.putFloat(p+32,.5f).putFloat(p+36,.5f).putFloat(p+40,.5f);
    }
    static ByteBuffer read(PackagePhysicsGpu gpu){
        ByteBuffer b=bodies(gpu.count());GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,b);return b;
    }
    static void externalForces(){
        for(int n:new int[]{0,1,63,64,65,1025})try(var gpu=new PackagePhysicsGpu(Math.max(1,n),2,PackageGpuValidation::source);var upload=new PackageForceGpu()){
            var b=bodies(n);var fields=new ArrayList<PackageForceScene.Source>();
            for(int i=0;i<n;i++){float x=i*4;body(b,i,x,10,0,i%7==0?0:1);int p=i*64;
                b.putFloat(p+16,.25f).putFloat(p+20,-.5f).putFloat(p+24,1.5f);if(i%11==0)b.putFloat(p+60,PackagePhysicsGpu.PREPARED);
                if(i%2==0)fields.add(new PackageForceScene.Source(1,x-.1,i%6==0?10:9,0,x+.9,11,1,x+.4,9,.5,1,0,0,0,0));
                else fields.add(new PackageForceScene.Source(2,x-1,8,-1,x+1,12,1,x+.5,9.5,.5,4,i%4==1?1:-1,0,0,8));}
            gpu.upload(b,n);var scene=PackageForceScene.bake(20,fields,0,0,0);
            try(var view=upload.view(scene,20)){gpu.applyForces(view,.05f);}var r=read(gpu);
            for(int i=0;i<n;i++){int p=i*64;float vx=.25f,vy=-.5f,vz=1.5f;
                if(i%7!=0&&i%11!=0){if(i%2==0){double dx=b.getFloat(p)-(float)fields.get(i).x(),d=Math.sqrt(Math.max(Math.abs(dx),.5));int pushes=i%6==0?1:2;
                        vx+=(float)(dx/d*Math.min(1,1/d)*pushes);vz+=(float)(-.5/d*Math.min(1,1/d)*pushes);}
                    else {double acceleration=32/Math.sqrt(.5);double flow=i%4==1?1:-1;
                        vx+=(float)(Math.max(-5,Math.min(5,flow*acceleration-vx/20))*2.5);
                        vy+=(float)(Math.max(-5,Math.min(5,-vy/20))*2.5);vz+=(float)(Math.max(-5,Math.min(5,-vz/20))*2.5);}}
                check(Math.abs(r.getFloat(p+16)-vx)<1e-5,"GPU external push/wind x "+i);
                check(Math.abs(r.getFloat(p+20)-vy)<1e-5,"GPU external push/wind y "+i);
                check(Math.abs(r.getFloat(p+24)-vz)<1e-5,"GPU external push/wind z "+i);
                for(int j=0;j<16;j++)if(j<4||j>=7)check(r.getInt(p+j*4)==b.getInt(p+j*4),"force changed pose/lifecycle "+i+"/"+j);
            }
        }
        try(var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source);var upload=new PackageForceGpu()){
            var b=bodies(1);body(b,0,.5f,1,.5f,1);gpu.upload(b,1);
            var scene=PackageForceScene.bake(5,List.of(new PackageForceScene.Source(2,0,0,0,1,2,1,.5,.5,.5,1,0,1,0,4)),0,0,0);
            try(var v=upload.view(scene,5)){gpu.applyForces(v,.05f);}var r=read(gpu);
            check(r.getFloat(20)==12.5f,"fan source coincidence must clamp without NaN");check(r.getFloat(16)==0&&r.getFloat(24)==0,"fan zero flow must not become NaN");
            gpu.upload(b,1);
            var ordered=PackageForceScene.bake(5,List.of(
                    new PackageForceScene.Source(2,-2,0,0,1,2,1,0,.5,.5,.05f,1,0,0,4),
                    new PackageForceScene.Source(2,-3,0,0,2,2,1,0,.5,.5,.05f,-1,0,0,4)),0,0,0);
            try(var v=upload.view(ordered,5)){gpu.applyForces(v,.05f);}r=read(gpu);
            check(Math.abs(r.getFloat(16)+.125f)<1e-6,"BVH must preserve overlapping fan tick order");
            boolean stale=false;try{upload.view(scene,7);}catch(IllegalStateException expected){stale=true;}check(stale,"stale force snapshot accepted");
        }
        for(int n:new int[]{10000,65536,131072})try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var upload=new PackageForceGpu()){
            var b=bodies(n);for(int i=0;i<n;i++)body(b,i,i*.01f,10,0,1);gpu.upload(b,n);
            var scene=PackageForceScene.bake(0,List.of(new PackageForceScene.Source(2,-2,8,-2,n*.02+2,12,2,0,9.5,0,.5f,1,0,0,64)),0,0,0);
            try(var v=upload.view(scene,0)){gpu.applyForces(v,.05f);}var r=read(gpu);
            for(int i=0;i<n;i++){float x=b.getFloat(i*64),expected=(float)(Math.min(5,32/Math.max(1e-20,Math.abs(x)))*2.5);
                check(Math.abs(r.getFloat(i*64+16)-expected)<1e-4,"full active capacity wind "+i);
                check(r.getFloat(i*64+16)>0&&r.getFloat(i*64+60)==0,"full capacity must not fall back or omit a body");}
        }
        final boolean[] held={true};
        try(var upload=new PackageForceGpu(f->held[0]?GL32.GL_TIMEOUT_EXPIRED:GL32.glClientWaitSync(f,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0))){
            var empty=PackageForceScene.bake(0,List.of(),0,0,0);for(int i=0;i<40;i++)try(var v=upload.view(empty,0)){check(v.nodes()==0,"immutable force bank reuse");}
            for(int i=0;i<3;i++)try(var v=upload.view(PackageForceScene.bake(0,List.of(),0,0,0),0)){check(v.nodes()==0,"distinct force scene");}
            boolean busy=false;try{upload.view(PackageForceScene.bake(0,List.of(),0,0,0),0);}catch(IllegalStateException expected){busy=true;}check(busy,"force ring overwrote a different unfinished capture");
            GL11.glFinish();held[0]=false;try(var v=upload.view(empty,1)){check(v.nodes()==0,"force ring did not recover");}
        }
    }
    static void forceBenchmark()throws Exception{
        var rows=new ArrayList<String>();rows.add("packages,scene,sources,run,gpu_force_p50_ms,gpu_force_p95_ms,cpu_upload_submit_p50_ms,cpu_upload_submit_p95_ms,worker_bake_p50_ms,worker_bake_p95_ms,upload_bytes_per_step");
        for(int n:new int[]{10000,65536,131072})for(String scene:new String[]{"wind","entities_wind","overlapping_wind","framed_wind","framed_overlapping_wind"}){
            var fields=new ArrayList<PackageForceScene.Source>();
            if(scene.equals("entities_wind"))for(int i=0;i<4095;i++){double x=(i%64)+.4,z=(i/64)+.4;
                fields.add(new PackageForceScene.Source(1,x-.5,9,z-.5,x+.5,11,z+.5,x,9,z,1,0,0,0,0));}
            int fans=scene.contains("overlapping_wind")?64:1;
            boolean framed=scene.startsWith("framed_");double cosine=Math.cos(.37),sine=Math.sin(.37);
            var frame=new PackageForceScene.Frame(new PackageMovingGeometry.Pose(cosine,0,-sine,0,1,0,sine,0,cosine,16,9.5,32),16,9.5,32);
            for(int i=0;i<fans;i++){var fan=new PackageForceScene.Source(2,framed?-100:-2,8,framed?-100:-2,framed?132:34,12,framed?166:66,-1,9.5,-1,.5f,i%2==0?1:-1,0,0,64);
                fields.add(framed?fan.framed(frame):fan);}
            var snapshot=PackageForceScene.bake(0,fields,0,0,0);
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var upload=new PackageForceGpu()){
                var b=bodies(n);for(int i=0;i<n;i++)body(b,i,(i%256)*.125f,10,(i/256)*.125f,1);gpu.upload(b,n);
                int timer=GL15.glGenQueries();
                try{for(int run=0;run<3;run++){
                    double[] timing=new double[60],cpu=new double[60],bake=new double[60];
                    for(int s=-32;s<60;s++){
                        long bakeStart=System.nanoTime();var rebuilt=PackageForceScene.bake(0,fields,0,0,0);double bakeMs=(System.nanoTime()-bakeStart)/1e6;
                        long started=System.nanoTime();
                        try(var view=upload.view(rebuilt,0)){GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);gpu.applyForces(view,.05f);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);}
                        double cpuMs=(System.nanoTime()-started)/1e6;double elapsed=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
                        if(s>=0){timing[s]=elapsed;cpu[s]=cpuMs;bake[s]=bakeMs;}
                    }
                    String row=n+","+scene+","+fields.size()+","+run+","+percentile(timing,.5)+","+percentile(timing,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                            +","+percentile(bake,.5)+","+percentile(bake,.95)+","+snapshot.data().remaining();rows.add(row);System.out.println(row);
                }}finally{GL15.glDeleteQueries(timer);}
            }
        }
        Files.write(Path.of("build/package-forces-benchmark.csv"),rows);
    }
    static void framedForces(){
        double plotX=20480000.37,plotY=2147483600.25,plotZ=-20480000.22,ox=30000000,oy=80,oz=-30000000;
        Random random=new Random(0x5ab1ef);
        for(int variant=0;variant<12;variant++){
            var rotation=new org.joml.Quaterniond().rotationXYZ(variant<3?0:.13*variant,variant==1?Math.PI/4:.17*variant,variant==2?Math.PI/2:.11*variant);
            var scale=new org.joml.Vector3d(variant<3?1:.5+variant*.13,variant<3?1:1.5,variant<3?1:2.5);
            var parent=new dev.ryanhcode.sable.companion.math.Pose3d(new org.joml.Vector3d(ox+.125,oy+.25,oz-.125),rotation,
                    new org.joml.Vector3d(plotX,plotY,plotZ),scale);
            var axisX=parent.transformNormal(new org.joml.Vector3d(1,0,0),new org.joml.Vector3d());
            var axisY=parent.transformNormal(new org.joml.Vector3d(0,1,0),new org.joml.Vector3d());
            var axisZ=parent.transformNormal(new org.joml.Vector3d(0,0,1),new org.joml.Vector3d());
            var centre=parent.transformPosition(new org.joml.Vector3d(plotX,plotY,plotZ),new org.joml.Vector3d());
            var pose=new PackageMovingGeometry.Pose(axisX.x,axisX.y,axisX.z,axisY.x,axisY.y,axisY.z,axisZ.x,axisZ.y,axisZ.z,centre.x,centre.y,centre.z);
            var frame=new PackageForceScene.Frame(pose,plotX,plotY,plotZ);
            int direction=variant%2==0?1:-1;
            var fan=new PackageForceScene.Source(2,plotX+.5,plotY-.5,plotZ-.5,plotX+8.5,plotY+.5,plotZ+.5,plotX,plotY,plotZ,2,direction,0,0,8).framed(frame);
            var farPose=new PackageMovingGeometry.Pose(pose.xx(),pose.xy(),pose.xz(),pose.yx(),pose.yy(),pose.yz(),pose.zx(),pose.zy(),pose.zz(),pose.tx()+100,pose.ty(),pose.tz());
            var farFan=fan.framed(new PackageForceScene.Frame(farPose,plotX,plotY,plotZ));
            var scene=PackageForceScene.bake(0,List.of(new PackageForceScene.Source(1,ox+200,oy+200,oz+200,ox+201,oy+201,oz+201,ox+200,oy+200,oz+200,1,0,0,0,0),fan,
                    new PackageForceScene.Source(2,ox+300,oy+300,oz+300,ox+301,oy+301,oz+301,ox+300,oy+300,oz+300,1,0,1,0,4),farFan),ox,oy,oz);
            check(scene.frames()==2,"framed source extension count");
            try(var gpu=new PackagePhysicsGpu(66,2,PackageGpuValidation::source);var upload=new PackageForceGpu()){
                var bodies=bodies(66);
                for(int i=0;i<66;i++){
                    var local=new org.joml.Vector3d(1+random.nextDouble()*7,(random.nextDouble()-.5)*.6,i%3==0?2:(random.nextDouble()-.5)*.6);
                    var world=parent.transformPosition(new org.joml.Vector3d(plotX+local.x,plotY+local.y,plotZ+local.z),new org.joml.Vector3d());
                    body(bodies,i,(float)(world.x-ox),(float)(world.y-oy),(float)(world.z-oz),i%13==0?0:1);int p=i*64;
                    bodies.putFloat(p+32,.1f).putFloat(p+36,.1f).putFloat(p+40,.1f).putFloat(p+16,1.1f).putFloat(p+20,-2.2f).putFloat(p+24,3.3f);
                    if(i%11==0)bodies.putFloat(p+60,PackagePhysicsGpu.PREPARED);if(i%17==0)bodies.putFloat(p+60,PackagePhysicsGpu.RETIRED);
                }
                gpu.upload(bodies,66);var prefix=bodies.duplicate().order(ByteOrder.nativeOrder());prefix.limit(65*64);gpu.upload(prefix,65);
                try(var view=upload.view(scene,0)){gpu.applyForces(view,.05f);}var result=bodies(66);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,gpu.stateBuffer());GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);
                for(int i=0;i<65;i++){
                    int p=i*64;double vx=1.1f,vy=-2.2f,vz=3.3f;
                    var world=new org.joml.Vector3d(ox+bodies.getFloat(p),oy+bodies.getFloat(p+4),oz+bodies.getFloat(p+8));
                    var bounds=new net.minecraft.world.phys.AABB(world.x-.1f,world.y-.1f,world.z-.1f,world.x+.1f,world.y+.1f,world.z+.1f);
                    var localBounds=new dev.ryanhcode.sable.companion.math.BoundingBox3d(bounds).transformInverse(parent,new dev.ryanhcode.sable.companion.math.BoundingBox3d()).toMojang();
                    boolean hit=localBounds.intersects(new net.minecraft.world.phys.AABB(fan.x0(),fan.y0(),fan.z0(),fan.x1(),fan.y1(),fan.z1()));
                    if(hit&&bodies.getFloat(p+12)>0&&bodies.getFloat(p+60)>=0){
                        var feet=parent.transformPositionInverse(new org.joml.Vector3d(world.x,world.y-.1f,world.z),new org.joml.Vector3d());
                        double distance=feet.distance(plotX,plotY,plotZ),acceleration=16/Math.max(distance,1e-20);
                        var flow=parent.transformNormal(new org.joml.Vector3d(direction,0,0),new org.joml.Vector3d());
                        vx+=Math.max(-5,Math.min(5,flow.x*acceleration-vx/20))*2.5;vy+=Math.max(-5,Math.min(5,flow.y*acceleration-vy/20))*2.5;vz+=Math.max(-5,Math.min(5,flow.z*acceleration-vz/20))*2.5;
                    }
                    check(Math.abs(result.getFloat(p+16)-vx)<1e-4,"Sable world-axis wind x "+variant+"/"+i);
                    check(Math.abs(result.getFloat(p+20)-vy)<1e-4,"Sable world-axis wind y "+variant+"/"+i);
                    check(Math.abs(result.getFloat(p+24)-vz)<1e-4,"Sable world-axis wind z "+variant+"/"+i);
                    for(int word=0;word<16;word++)if(word<4||word>=7)check(result.getInt(p+word*4)==bodies.getInt(p+word*4),"framed force changed nonvelocity state");
                }
                for(int word=0;word<16;word++)check(result.getInt(65*64+word*4)==bodies.getInt(65*64+word*4),"force tail touched sentinel");
                // A moved source must not keep its old world bounds or extension records.
                gpu.upload(prefix,65);var moved=PackageForceScene.bake(1,List.of(farFan),ox,oy,oz);
                try(var view=upload.view(moved,1)){gpu.applyForces(view,.05f);}result=read(gpu);
                for(int word=0;word<65*16;word++)check(result.getInt(word*4)==bodies.getInt(word*4),"moved fan retained its old force volume");
                gpu.upload(prefix,65);var nativeScene=PackageForceScene.bake(1,List.of(new PackageForceScene.Source(2,ox-100,oy-100,oz-100,ox+100,oy+100,oz+100,ox,oy,oz,1,0,1,0,4)),ox,oy,oz);
                try(var view=upload.view(nativeScene,1)){gpu.applyForces(view,.05f);}result=read(gpu);
                check(Float.isFinite(result.getFloat(64+20))&&result.getFloat(64+20)!=bodies.getFloat(64+20),"native force after framed source used stale extension");
            }
        }
    }
    static void boundaries(){
        for(int count:new int[]{0,1,63,64,65,1023,1024,1025}) {
            try(var gpu=new PackagePhysicsGpu(Math.max(1,count),2,PackageGpuValidation::source)) {
                ByteBuffer b=bodies(count);for(int i=0;i<count;i++)body(b,i,i*3,10,0,1);
                gpu.upload(b,count);gpu.step(.05f);
                if(count==0)continue;
                ByteBuffer result=read(gpu);
                for(int i=0;i<count;i++) {
                    check(Math.abs(result.getFloat(i*64+4)-9.9216f)<1e-5,"gravity/tail "+i);
                    check(result.getFloat(i*64)==i*3,"unrelated bodies must not interact");
                }
            }
        }
    }
    static void contact(){
        try(var gpu=new PackagePhysicsGpu(3,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(3);body(b,0,0,.0f,0,0);body(b,1,0,.9f,0,1);body(b,2,20,2,0,1);
            gpu.upload(b,3);
            for(int i=0;i<200;i++)gpu.step(.05f);
            ByteBuffer r=read(gpu);
            check(r.getFloat(4)==0,"static collider moved");
            check(Math.abs(r.getFloat(68)-1)<.02,"body failed to settle on support");
            check(r.getFloat(64+28)==1,"grounded flag missing");
            for(int i=0;i<3*16;i++)check(Float.isFinite(r.getFloat(i*4)),"non-finite contact result");
            // Coincident boxes have a deterministic antisymmetric tie breaker.
            body(b,0,0,10,0,1);body(b,1,0,10,0,1);body(b,2,20,10,0,0);
            gpu.upload(b,3);gpu.step(.05f);r=read(gpu);
            check(Math.abs(r.getFloat(4)-r.getFloat(68))>.99f,"coincident pair not separated");
        }
    }
    static void chain(){
        try(var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(65), c=bodies(65);
            for(int i=0;i<65;i++) {
                body(b,i,0,-9f/16f,.875f,1);
                int p=i*64;c.putFloat(p+12,.875f).putFloat(p+36,90).putFloat(p+40,1);
            }
            gpu.upload(b,65);gpu.uploadChains(c);
            for(int t=0;t<80;t++)gpu.stepChains(.05f);
            ByteBuffer r=read(gpu);
            for(int i=0;i<65;i++) {
                check(Math.abs(r.getFloat(i*64))<1.5,"chain pendulum escaped tether");
                check(Float.isFinite(r.getFloat(i*64+44)),"chain yaw not finite");
            }
        }
    }
    static void chainReference() {
        int n=16;Random random=new Random(124);
        float[][] pos=new float[n][3],vel=new float[n][3],start=new float[n][3];
        float[] angle=new float[n],speed=new float[n],yaw=new float[n];
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),c=bodies(n);
            for(int i=0;i<n;i++) {
                angle[i]=random.nextFloat()*360;speed[i]=i%2==0?90:-45;yaw[i]=angle[i]-120;
                for(int j=0;j<3;j++){pos[i][j]=random.nextFloat()*8-4;vel[i][j]=random.nextFloat()*.2f;start[i][j]=random.nextFloat()*4;}
                int p=i*64;body(b,i,pos[i][0],pos[i][1],pos[i][2],1);b.putFloat(p+44,yaw[i]);
                for(int j=0;j<3;j++){b.putFloat(p+16+j*4,vel[i][j]);c.putFloat(p+j*4,start[i][j]);}
                c.putFloat(p+12,.875f).putFloat(p+32,angle[i]).putFloat(p+36,speed[i]).putFloat(p+40,1).putFloat(p+44,i%3==0?1:0);
            }
            gpu.upload(b,n);gpu.uploadChains(c);
            for(int step=0;step<80;step++) {
                for(int i=0;i<n;i++) {
                    angle[i]=(angle[i]+speed[i]*.05f)%360;if(angle[i]<0)angle[i]+=360;
                    float[] target={start[i][0]+(float)Math.sin(Math.toRadians(angle[i]))*.875f,start[i][1],start[i][2]+(float)Math.cos(Math.toRadians(angle[i]))*.875f};
                    float dx=pos[i][0]-target[0],dy=pos[i][1]-target[1],dz=pos[i][2]-target[2],length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if(length>1.5f)for(int j=0;j<3;j++)pos[i][j]=target[j]+(pos[i][j]-target[j])*(1.5f/length);
                    for(int j=0;j<3;j++){vel[i][j]=(vel[i][j]+(j==1?-.25f:0))*.75f+(target[j]-pos[i][j])*.25f;pos[i][j]+=vel[i][j];}
                    float dyaw=angle[i]+(i%3==0?180:0)-yaw[i];dyaw=(dyaw+180)%360;if(dyaw<0)dyaw+=360;dyaw-=180;
                    yaw[i]+=dyaw*.25f;
                }
                gpu.stepChains(.05f);ByteBuffer result=read(gpu);
                for(int i=0;i<n;i++) {
                    for(int j=0;j<3;j++)check(Math.abs(result.getFloat(i*64+j*4)-pos[i][j])<1e-4,"Create 20Hz chain recurrence position");
                    check(Math.abs(result.getFloat(i*64+44)-yaw[i])<1e-4,"Create 20Hz chain recurrence yaw");
                }
            }
        }
    }
    static ByteBuffer chainEventRecords(PackageChainTrackGpu.Capture capture) {
        var header=readBuffer(capture.headerBuffer(),16);int n=header.getInt(4);
        check(n==Math.min(header.getInt(0),capture.capacity()) && header.getInt(8)==header.getInt(0)-n
                && header.getInt(12)==0,"chain event counts/padding");
        return readBuffer(capture.recordBuffer(),n*64);
    }
    static ByteBuffer linearTrack(long revision,int first,int nodes) {
        var data=bodies(1);data.putFloat(16,4).putFloat(28,4).putFloat(32,20)
                .putInt(48,first).putInt(52,nodes).putLong(56,revision);return data;
    }
    static void trackedChains() {
        for(int n:new int[]{0,1,63,64,65,1024}) {
            int capacity=Math.max(1,n);
            try(var gpu=new PackagePhysicsGpu(capacity,2,PackageGpuValidation::source);
                var tracks=new PackageChainTrackGpu(capacity,2,2,PackageGpuValidation::source)) {
                var node=BufferUtils.createByteBuffer(16);node.putFloat(0,1.5f).putInt(4,1).putInt(8,700);
                tracks.uploadTables(linearTrack(0x102030405L,0,1),node);
                if(n==1) {
                    boolean rejected=false;
                    try{tracks.rebuild(name->name.endsWith("chain_events_finalize.comp")?"invalid GLSL":source(name));}
                    catch(RuntimeException expected){rejected=true;}
                    check(rejected,"invalid chain rebuild replaced usable programs");
                }
                var meta=BufferUtils.createByteBuffer(n*32);var bodies=bodies(n);var chains=bodies(n);
                for(int i=0;i<n;i++) {
                    body(bodies,i,0,-9f/16f,0,1);chains.putFloat(i*64+40,2);
                    meta.putLong(i*32,i+1L).putLong(i*32+8,99).putInt(i*32+20,1).putInt(i*32+24,1);
                }
                tracks.append(meta);gpu.upload(bodies,n);gpu.uploadChains(chains);gpu.stepChains(.05f,tracks);
                var anticipation=tracks.capture(Math.min(31,n));var older=chainEventRecords(anticipation);
                for(int p=0;p<older.limit();p+=64)check(older.getInt(p+32)==0 && older.getInt(p+36)==1
                        && older.getLong(p+20)==0x102030405L,"Create chain anticipation/long track revision");
                gpu.stepChains(.05f,tracks);var links=readBuffer(gpu.chainBuffer(),n*64);
                for(int i=0;i<n;i++)check(links.getFloat(i*64+32)==2,"anticipation blocked progress");
                // Old anticipation flights cannot be replaced in place by actual crossings.
                var rest=tracks.capture(n);var remaining=chainEventRecords(rest);
                check(remaining.remaining()==Math.max(0,n-31)*64,"chain capture repeated an in-flight identity");
                tracks.acknowledge(anticipation.stamp(),older);tracks.finish(anticipation);
                tracks.cancel(rest.stamp(),remaining);tracks.finish(rest);
                var crossing=tracks.capture(n);var actual=chainEventRecords(crossing);
                check(actual.remaining()==n*64,"ACK of anticipation erased newer actual node candidates");
                BitSet seen=new BitSet();
                for(int p=0;p<actual.limit();p+=64) {
                    int i=actual.getInt(p+16);check(!seen.get(i) && actual.getLong(p)==i+1L && actual.getLong(p+8)==99
                            && actual.getInt(p+32)==1,"chain crossing identity/uniqueness");seen.set(i);
                }
                var empty=new ArrayList<PackageChainTrackGpu.Capture>();
                for(int i=0;i<3;i++){var c=tracks.capture(n);check(chainEventRecords(c).remaining()==0,"chain flight duplicated");empty.add(c);}
                check(tracks.capture(n)==null,"full chain bank overwrote an immutable capture");
                for(int step=0;step<8;step++)gpu.stepChains(.05f,tracks);
                links=readBuffer(gpu.chainBuffer(),n*64);
                for(int i=0;i<n;i++)check(links.getFloat(i*64+32)==2,"unconfirmed crossing moved past its server handoff");
                for(var c:empty)tracks.finish(c);
                // Wrong generation must neither consume the flight nor erase the durable event.
                var wrong=BufferUtils.createByteBuffer(actual.remaining());wrong.put(actual.duplicate()).flip();
                for(int p=0;p<wrong.limit();p+=64)wrong.putLong(p+8,100);
                tracks.acknowledge(crossing.stamp(),wrong);
                var still=tracks.capture(n);check(chainEventRecords(still).remaining()==0,"old identity consumed chain flight");tracks.finish(still);
                tracks.cancel(crossing.stamp(),actual);tracks.finish(crossing);
                var retry=tracks.capture(n);var retried=chainEventRecords(retry);
                check(retried.remaining()==n*64,"cancelled chain readback discarded events");
                tracks.acknowledge(retry.stamp(),retried);tracks.finish(retry);gpu.stepChains(.05f,tracks);
                links=readBuffer(gpu.chainBuffer(),n*64);
                for(int i=0;i<n;i++)check(links.getFloat(i*64+32)==3,"exact chain ACK did not release progress");
                var clean=tracks.capture(n);check(chainEventRecords(clean).remaining()==0,"completed node repeatedly emitted");tracks.finish(clean);
                // Tables may grow with new links without invalidating existing track indices.
                tracks.appendTables(linearTrack(1,1,0),BufferUtils.createByteBuffer(0));
                if(n>0) {
                    boolean rejected=false;try{tracks.replaceTrack(0,linearTrack(1,0,1));}catch(IllegalArgumentException expected){rejected=true;}
                    check(rejected,"older chain track revision replaced active geometry");
                    var resetBody=read(gpu);resetBody.limit(64);
                    var resetChain=bodies(1);resetChain.putFloat(32,1).putFloat(40,2);
                    gpu.replace(0,resetBody,resetChain,1,true);gpu.stepChains(.05f,tracks);
                    var oldEvent=tracks.capture(n);check(chainEventRecords(oldEvent).remaining()==64,"reset chain crossing fixture");
                    tracks.replaceTrack(0,linearTrack(0x102030406L,0,1));gpu.stepChains(.05f,tracks);
                    check(read(gpu).getFloat(60)<0,"changed topology advanced an old handoff checkpoint");
                    tracks.acknowledge(oldEvent);tracks.finish(oldEvent);
                    var invalid=tracks.capture(n);var invalidRecords=chainEventRecords(invalid);
                    check(invalidRecords.remaining()==64 && (invalidRecords.getInt(44)&0x80000000)!=0
                            && invalidRecords.getLong(20)==0x102030406L,"old event ACK erased newer topology pause");
                    tracks.acknowledge(invalid);tracks.finish(invalid);
                    tracks.retire(0);gpu.retire(0);rejected=false;
                    try{tracks.activate(0);}catch(IllegalArgumentException expected){rejected=true;}
                    check(rejected,"late ACTIVE resurrected retired chain event identity");
                }
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"chain track validation GL error");
            }
        }
        trackedChainLoopReference();
    }
    static final class ChainTransport implements PackageChainEventChannel.Transport {
        final ArrayList<Long> sent=new ArrayList<>();
        final BitSet actual=new BitSet(),ahead=new BitSet();
        int records,failures;boolean rejectAfterFirst,failPreparation;
        @Override public Object prepare(long epoch,long revision,long sequence,ByteBuffer bytes) {
            if(failPreparation)throw new IllegalStateException("injected chain packet preparation failure");return null;
        }
        @Override public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes) {
            if(rejectAfterFirst && !sent.isEmpty())return false;
            check(epoch==77 && revision==3,"chain wire epoch namespace");
            var decoded=bodies(PackageChainEventCodec.BATCH);int count=PackageChainEventCodec.decode(bytes,decoded);decoded.flip();
            for(int i=0;i<count;i++) {
                int p=i*64,candidate=decoded.getInt(p+16);
                check(decoded.getLong(p)==candidate+1L && decoded.getLong(p+8)==99 && decoded.getLong(p+20)==7,"chain wire stable identity/revision");
                if(decoded.getInt(p+32)!=0){check(!actual.get(candidate),"wire repeated actual chain candidate");actual.set(candidate);}
                else{check(!ahead.get(candidate),"wire repeated anticipation candidate");ahead.set(candidate);}
            }
            records+=count;sent.add(sequence);return true;
        }
        @Override public void failed(String reason){failures++;}
    }
    static ClientboundChainPackagePacket chainCheckpoint(int action,int index,long id,long epoch,long revision,int eligibility) {
        float progress=revision==1?40:42;
        double angle=Math.toRadians(progress);
        var pose=new PackageLease.Pose(100+Math.sin(angle)*.875,70,200+Math.cos(angle)*.875,0,0,0,progress+180);
        var baseline=new PackageChainAuthority.Baseline(index,new PackageLease.Identity(id,600001),1,revision,0,3,
                new PackageChainAuthority.State(progress,100+revision,pose),eligibility);
        return new ClientboundChainPackagePacket(action,ResourceLocation.parse("minecraft:overworld"),epoch,null,baseline,
                action==ClientboundChainPackagePacket.OFFER?ResourceLocation.parse("create:cardboard_package_12x12"):null,
                action==ClientboundChainPackagePacket.OFFER?.75f:0,action==ClientboundChainPackagePacket.OFFER?1:0,0);
    }
    static void chainAcquisitionFrame(PackageMixedPhysicsGpu physics,PackagePoolGpu pool,PackageChainAcquisitionGpu acquisition,
                                      int particles,int counter,long generation,boolean commit) {
        physics.publish();physics.source(pool,96,64,192);putBuffer(counter,BufferUtils.createByteBuffer(16));
        pool.stage(particles,counter,3,new float[24],0,0,0);
        if(commit){pool.commit();acquisition.committed(generation);}else pool.abort();
    }
    static void recycledChainAcquisitions(){
        int cap=4,particles=buffer(bodies(cap)),counter=buffer(BufferUtils.createByteBuffer(16));long frame=1;int[] transitions={0,0};
        var geometry=new PackageChainTrack(new net.minecraft.world.phys.Vec3(100,70,200),net.minecraft.world.phys.Vec3.ZERO,.875f,0,-90,true,true,0,0,2,3);
        var track=new ClientboundChainPackagePacket.Track(0,new net.minecraft.core.BlockPos(100,70,200),null,geometry,List.of(new ClientboundChainPackagePacket.Node(35,1),new ClientboundChainPackagePacket.Node(30,2)));
        var offer=chainCheckpoint(ClientboundChainPackagePacket.OFFER,17,900001,19,1,3);
        try(var physics=new PackageMixedPhysicsGpu(0,cap,2,PackageGpuValidation::source);var pool=new PackagePoolGpu(cap,2,PackageGpuValidation::source);
            var tracks=new PackageChainTrackGpu(cap,1,2,PackageGpuValidation::source);
            var channel=new PackageChainEventChannel(tracks,19,1,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageChainEventChannel.Transport(){public boolean send(long e,long r,long seq,ByteBuffer b){return true;}public void failed(String reason){throw new AssertionError(reason);}});
            var acquisition=new PackageChainAcquisitionGpu(offer.dimension(),19,96,64,192,physics,pool,tracks,channel,Map.of(offer.model(),new PackageModelCache.Style(0,1)),p->0,p->.6875,(o,c)->null,new PackageChainAcquisitionGpu.Transport(){
                public void control(int action,ClientboundChainPackagePacket p,int candidate){check(action!=ServerboundChainPackagePacket.RELEASE,"chain recyclable capacity exhausted");}
                public void activated(ClientboundChainPackagePacket o,ClientboundChainPackagePacket a,ClientboundChainPackagePacket.Track t,int candidate,int slot){transitions[0]++;}
                public void released(ClientboundChainPackagePacket o,PackageChainAuthority.Baseline b){transitions[1]++;}
            })){
            var ranges=BufferUtils.createByteBuffer(32);ranges.putInt(4,3).putFloat(8,1).putInt(16,3).putInt(20,3).putFloat(24,1);pool.uploadMeshes(BufferUtils.createByteBuffer(6*48),ranges,2);
            acquisition.receive(new ClientboundChainPackagePacket(ClientboundChainPackagePacket.TRACK,offer.dimension(),19,track,null,null,0,0,0));
            ClientboundChainPackagePacket old=null;
            for(int life=0;life<64;life++){
                for(int phase=0;phase<3;phase++){
                    int action=phase==0?ClientboundChainPackagePacket.OFFER:phase==1?ClientboundChainPackagePacket.FINAL:ClientboundChainPackagePacket.ACTIVE;
                    acquisition.receive(chainCheckpoint(action,17,900001+life,19,phase==0?1:2,phase==0?3:1));acquisition.pump(64,(o,c)->true);
                    chainAcquisitionFrame(physics,pool,acquisition,particles,counter,frame++,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
                }
                check(acquisition.activeCount()==1&&physics.chainCount()==1&&tracks.count()==1&&pool.metadataCount()==1,"chain lifecycle grew immutable candidates");
                if(old!=null){acquisition.receive(old);acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.FINAL,17,900000+life,19,2,1));acquisition.pump(64,(o,c)->true);check(acquisition.activeCount()==1,"old chain packet retired a reused body");}
                old=chainCheckpoint(ClientboundChainPackagePacket.RELEASED,17,900001+life,19,2,1);acquisition.receive(old);acquisition.pump(64,(o,c)->true);
                chainAcquisitionFrame(physics,pool,acquisition,particles,counter,frame++,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
                check(acquisition.activeCount()==0&&physics.chainCount()==0&&tracks.count()==0&&pool.metadataCount()==0,"chain suffix not reclaimed after exact retirement");
            }
            check(transitions[0]==64&&transitions[1]==64,"chain lifecycle duplicated or lost transitions");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    static void chainAcquisitions() {
        var geometry=new PackageChainTrack(new net.minecraft.world.phys.Vec3(100,70,200),net.minecraft.world.phys.Vec3.ZERO,
                .875f,0,-90,true,true,0,0,2,3);
        var track=new ClientboundChainPackagePacket.Track(0,new net.minecraft.core.BlockPos(100,70,200),null,geometry,
                List.of(new ClientboundChainPackagePacket.Node(35,1),new ClientboundChainPackagePacket.Node(30,2)));
        var offer=chainCheckpoint(ClientboundChainPackagePacket.OFFER,17,900001,19,1,3);
        var finalPacket=chainCheckpoint(ClientboundChainPackagePacket.FINAL,17,900001,19,2,1);
        var active=chainCheckpoint(ClientboundChainPackagePacket.ACTIVE,17,900001,19,2,1);
        var style=new PackageModelCache.Style(0,1);
        ByteBuffer bodyStore=BufferUtils.createByteBuffer(80),chainStore=BufferUtils.createByteBuffer(80),poolStore=BufferUtils.createByteBuffer(96),eventStore=BufferUtils.createByteBuffer(48);
        for(var store:List.of(bodyStore,chainStore,poolStore,eventStore))for(int i=0;i<store.capacity();i++)store.put(i,(byte)0x5a);
        var body=bodyStore.duplicate().position(8).limit(72);var chain=chainStore.duplicate().position(8).limit(72);
        var meta=poolStore.duplicate().position(8).limit(88);var eventMeta=eventStore.duplicate().position(8).limit(40);
        PackageChainUpload.prepared(offer,finalPacket,track,style,9,0xf000f0,.6875f,96,64,192,null,body,chain,meta,eventMeta);
        for(var store:List.of(bodyStore,chainStore,poolStore,eventStore))check(store.get(0)==0x5a && store.get(store.capacity()-1)==0x5a,"chain upload overwrote adjacent records");
        check(body.position()==8 && chain.position()==8 && meta.position()==8 && eventMeta.position()==8,"chain upload consumed scratch");
        check(chain.order(ByteOrder.nativeOrder()).getFloat(8+32)==42 && chain.getFloat(8+40)==3 && chain.getFloat(8+44)==1,"tracked layout lost progress/reverse");
        check(meta.order(ByteOrder.nativeOrder()).getInt(8+28)==7 && meta.getInt(8+16)==9 && meta.getFloat(8+56)==.6875f,"chain layout lost shared body/model/hook");
        check(eventMeta.order(ByteOrder.nativeOrder()).getLong(8)==900001 && eventMeta.getInt(8+20)==1 && eventMeta.getInt(8+24)==0,"final chain checkpoint enabled events");
        check(body.order(ByteOrder.nativeOrder()).getFloat(8+60)==PackagePhysicsGpu.PREPARED,"prepared chain body became active");
        var saved=bodies(1);saved.put(body.slice()).flip();boolean rejected=false;
        try{PackageChainUpload.prepared(offer,chainCheckpoint(ClientboundChainPackagePacket.FINAL,17,900002,19,2,1),track,style,9,0,.5f,96,64,192,null,body,chain,meta,eventMeta);}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected && body.slice().equals(saved),"spoof chain checkpoint mutated storage");
        var controls=new ArrayList<Integer>();var candidates=new ArrayList<Integer>();var visible=new ArrayList<Integer>();var released=new ArrayList<Integer>();
        var sequences=new ArrayList<Long>();
        var checkpoints=new ArrayList<PackagePoseQueryGpu.Result>();
        int particles=buffer(bodies(8)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackageMixedPhysicsGpu(8,8,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(8,2,PackageGpuValidation::source);
            var tracks=new PackageChainTrackGpu(8,4,16,PackageGpuValidation::source);
            var channel=new PackageChainEventChannel(tracks,19,1,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageChainEventChannel.Transport() {
                @Override public boolean send(long e,long r,long sequence,ByteBuffer bytes){sequences.add(sequence);return true;}
                @Override public void failed(String reason){throw new AssertionError(reason);}
            });
            var queries=new PackagePoseQueryGpu(8,19,PackageGpuValidation::source);
            var acquisition=new PackageChainAcquisitionGpu(offer.dimension(),19,96,64,192,physics,pool,tracks,channel,
                    Map.of(offer.model(),style),p->0xf000f0,p->.6875,(o,c)->null,new PackageChainAcquisitionGpu.Transport() {
                @Override public void control(int action,ClientboundChainPackagePacket p,int candidate){controls.add(action);candidates.add(candidate);}
                @Override public void activated(ClientboundChainPackagePacket o,ClientboundChainPackagePacket a,ClientboundChainPackagePacket.Track t,int candidate,int slot){visible.add(slot);}
                @Override public void released(ClientboundChainPackagePacket o,PackageChainAuthority.Baseline b){released.add(b.index());}
                @Override public void released(ClientboundChainPackagePacket o,PackageChainAuthority.Baseline b,PackagePoseQueryGpu.Result pose) {
                    released.add(b.index());if(pose!=null)checkpoints.add(pose);
                }
            })) {
            acquisition.poseQueries(queries);
            var ranges=BufferUtils.createByteBuffer(32);ranges.putInt(4,3).putFloat(8,1).putInt(16,3).putInt(20,3).putFloat(24,1);
            pool.uploadMeshes(BufferUtils.createByteBuffer(6*48),ranges,2);
            // A pre-existing free object makes chain-body, generic candidate and event indices different.
            var freeBody=bodies(1);body(freeBody,0,10,10,10,1);physics.appendFree(freeBody,bodies(1),1);
            var freeMeta=BufferUtils.createByteBuffer(80);freeMeta.putLong(0,400001).putLong(8,500001).putInt(20,0).putInt(24,-1).putInt(28,PackagePoolGpu.HIDDEN);
            pool.appendMetadata(freeMeta,1);
            var trackPacket=new ClientboundChainPackagePacket(ClientboundChainPackagePacket.TRACK,offer.dimension(),19,track,null,null,0,0,0);
            check(acquisition.receive(trackPacket) && acquisition.receive(trackPacket),"chain geometry duplicate not idempotent");
            acquisition.receive(offer);acquisition.receive(offer);check(acquisition.pendingCount()==1,"duplicate chain offer allocated twice");
            acquisition.pump(64,(o,c)->false);check(physics.chainCount()==0 && tracks.trackCount()==1 && tracks.nodeCount()==2,"chain resource preparation froze native simulation");
            acquisition.pump(64,(o,c)->true);
            check(physics.chainCount()==1 && tracks.count()==1 && pool.metadataCount()==2,"chain acquisition did not share ordinary pool");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,1,true);
            var initialChain=readBuffer(physics.chainBuffer(),9*64);var offeredHook=offer.baseline().state().pose();
            check(initialChain.getFloat(8*64+48)==(float)(offeredHook.x()-96) && initialChain.getFloat(8*64+52)==(float)(offeredHook.y()-64-9.0/16)
                    && initialChain.getFloat(8*64+56)==(float)(offeredHook.z()-192),"tracked initialization interpreted track index as legacy radius");
            check(controls.isEmpty() && visible.isEmpty(),"chain ownership trusted unpolled admission");
            check(readBuffer(pool.commandBuffer(),32).getInt(4)==0 && readBuffer(pool.commandBuffer(),32).getInt(20)==0,"prepared chain model was drawn");
            physics.stepChains(.05f,tracks);physics.publish();var state=readBuffer(physics.bodyBuffer(),9*64);
            check(state.getFloat(8*64+60)==PackagePhysicsGpu.PREPARED,"prepared tracked chain moved");
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(controls.equals(List.of(ServerboundChainPackagePacket.PREPARED)) && candidates.equals(List.of(0)),"PREPARED used generic candidate instead of chain identity candidate");
            acquisition.receive(active);acquisition.pump(64,(o,c)->true);check(acquisition.simulationCount()==0,"ACTIVE bypassed chain final upload");
            acquisition.receive(finalPacket);acquisition.receive(finalPacket);acquisition.pump(64,(o,c)->true);
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,2,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(controls.equals(List.of(ServerboundChainPackagePacket.PREPARED,ServerboundChainPackagePacket.FINAL_READY)),"chain final checkpoint not acknowledged");
            check(!acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.ACTIVE,17,900001,20,2,1)),"old chain epoch activated");
            acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.ACTIVE,17,900002,19,2,1));acquisition.pump(64,(o,c)->true);
            check(acquisition.simulationCount()==0,"wrong chain identity activated");
            acquisition.receive(active);acquisition.pump(64,(o,c)->true);check(visible.isEmpty() && acquisition.simulationCount()==1,"chain Create hide occurred before visible confirmation");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,3,false);GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(visible.isEmpty() && acquisition.activeCount()==0,"aborted chain commit published ownership");
            var useQueue=new PackageChainUseQueue(19,()->0);var hookPose=active.baseline().state().pose();
            useQueue.enqueue(new PackagePoseQueryGpu.Ray((float)(hookPose.x()-96-2),(float)(hookPose.y()-64-9.0/16),(float)(hookPose.z()-192),4,0,0),"test use");
            var use=useQueue.queued();
            acquisition.pickResults(c->{check(acquisition.interaction(c.results().getFirst(),use.transaction())==null,"GPU pick bypassed visible ownership confirmation");check(useQueue.completed(c),"pick result not dispatched to input queue");});
            check(!acquisition.pick(use,3),"failed publication allowed user pick capture");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,3,true);
            check(acquisition.pick(use,3) && useQueue.submitted(use),"committed GPU pick not submitted");
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(visible.equals(List.of(1)) && acquisition.activeCount()==1 && acquisition.pendingCount()==0,"matching visible chain admission failed");
            var picked=useQueue.result();check(picked!=null && picked.id()==900001 && picked.generation()==600001 && picked.candidate()==1 && picked.body()==8,"real GPU pick lost stable identity/index namespaces");
            check(acquisition.interaction(picked,use.transaction(),2)==null,"old chain query became a new lifetime interaction");
            var interaction=acquisition.interaction(picked,use.transaction(),3);
            check(interaction!=null && interaction.identity().equals(active.baseline().identity()) && interaction.leaseEpoch()==active.baseline().leaseEpoch()
                    && interaction.revision()==active.baseline().revision() && interaction.track()==0 && interaction.trackRevision()==3 && interaction.progress()==42,"pick request did not use exact active lease");
            useQueue.sent(interaction);
            check(useQueue.acknowledge(new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainInteractionPacket(19,interaction.identity(),interaction.transaction(),PackageChainAuthority.Result.ACCEPTED)),"real GPU input request ACK failed");
            var commands=readBuffer(pool.commandBuffer(),32);check(commands.getInt(4)==1 && commands.getInt(20)==1,"chain box/rig did not share a single admitted particle");
            var slots=readBuffer(particles,64);check(Float.floatToRawIntBits(slots.getFloat(44))>>>24==3,"reversed rig flag lost at admission");
            rejected=false;try{tracks.rebasePrepared(0,eventMeta.slice());}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"active chain allowed checkpoint replacement");
            physics.stepChains(.05f,tracks);chainAcquisitionFrame(physics,pool,acquisition,particles,counter,4,true);
            check(acquisition.captureCommitted(4) && !acquisition.captureCommitted(4),"chain publication/event capture mismatch");
            for(int n=0;n<5 && sequences.isEmpty();n++){GL11.glFinish();channel.pump(256);}
            check(sequences.equals(List.of(0L)),"chain node event not journaled exactly once");
            check(channel.acknowledge(19,1,0),"chain event ACK rejected");channel.pump(256);
            check(acquisition.captureCommitted(4),"chain ACK did not allow durable paused candidate retry");
            acquisition.requestRelease(17);acquisition.pump(64,(o,c)->true);check(released.isEmpty(),"chain pause preceded retired pool commit");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,5,false);
            check(queries.pending()==0 && released.isEmpty(),"failed frame published a chain pose checkpoint");
            // Occupy all four banks without polling. Committed retirement must retain its
            // dirty request rather than overwrite a snapshot or wait for a fence.
            var request=new PackagePoseQueryGpu.Request(900001,600001,1,true);
            for(int n=0;n<4;n++)check(queries.poses(PackagePoseQueryGpu.Input.of(physics,pool),List.of(request),4,"pressure"),"query bank unavailable");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,5,true);
            check(queries.pending()==4 && released.isEmpty(),"full query ring lost retirement ownership");
            GL11.glFinish();check(queries.poll(c->check(c.tag().equals("pressure"),"pressure query mixed namespaces"))==4,"pressure snapshots lost");
            acquisition.pump(64,(o,c)->true);
            check(released.isEmpty() && acquisition.pendingCount()==1,"retirement admission bypassed its GPU pose");
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,6,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(released.equals(List.of(17)) && acquisition.activeCount()==0 && acquisition.pendingCount()==0 && acquisition.simulationCount()==0,"chain pause did not finish once");
            state=readBuffer(physics.bodyBuffer(),9*64);check(state.getFloat(8*64+60)==PackagePhysicsGpu.RETIRED,"released chain contact ghost retained");
            check(checkpoints.size()==1,"active chain restored without its latest GPU checkpoint");
            var pose=checkpoints.getFirst();
            check(acquisition.interaction(picked,2)==null && acquisition.interaction(pose,2)==null,"retired package could produce an inventory interaction");
            check(pose.id()==900001 && pose.generation()==600001 && pose.candidate()==1 && pose.body()==8 && pose.track()==0,"checkpoint confused chain, pool and body indices");
            check(pose.x()==state.getFloat(8*64) && pose.y()==state.getFloat(8*64+4),"retirement pose");
            check(pose.vy()==state.getFloat(8*64+20) && pose.yaw()==state.getFloat(8*64+44),"retirement velocity/yaw");
            var history=readBuffer(physics.historyBuffer(),9*32);
            check(pose.px()==history.getFloat(8*32) && pose.previousYaw()==history.getFloat(8*32+12)
                    && pose.pty()==history.getFloat(8*32+20),"retirement interpolation history");
            var restored=PackageChainUpload.checkpoint(pose,offer.baseline().identity(),track,96,64,192);
            check(restored.progress()==pose.progress(),"native checkpoint progress");
            check(restored.pendulum().x()==pose.x()+96.0 && restored.hookY()==pose.ty()+64.0+9.0/16,"native checkpoint origin/hook offset");
            check(restored.previous().x()==pose.px()+96.0 && restored.previous().targetY()==pose.pty()+64.0,"native checkpoint previous pose origin");
            physics.stepChains(.05f,tracks);physics.publish();var heldHistory=readBuffer(physics.historyBuffer(),9*32);
            for(int p=8*32;p<9*32;p++)check(heldHistory.get(p)==history.get(p),"another chain step changed a retired checkpoint history");
            rejected=false;try{PackageChainUpload.checkpoint(pose,new PackageLease.Identity(900001,600002),track,96,64,192);}
            catch(IllegalArgumentException expected){rejected=true;}check(rejected,"stale checkpoint reused a native generation");
            acquisition.receive(active);acquisition.receive(finalPacket);acquisition.pump(64,(o,c)->true);check(visible.size()==1,"late chain ACTIVE resurrected released package");
            check(!pool.reservesIdentity(900001,600001),"retired chain kept its global identity reservation");
            acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.OFFER,18,900001,19,1,3));acquisition.pump(64,(o,c)->false);
            check(physics.chainCount()==1 && acquisition.pendingCount()==1,"retired identity could not begin a fresh sidecar lifecycle");
            acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.RELEASED,18,900001,19,1,3));acquisition.pump(64,(o,c)->true);
            // A RELEASE arrives while hidden admission is in flight; stale confirmation must not freeze Create.
            var canceled=chainCheckpoint(ClientboundChainPackagePacket.OFFER,19,900003,19,1,3);
            acquisition.receive(canceled);acquisition.pump(64,(o,c)->true);chainAcquisitionFrame(physics,pool,acquisition,particles,counter,7,true);
            acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.RELEASED,19,900003,19,1,3));acquisition.pump(64,(o,c)->true);
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,8,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
            chainAcquisitionFrame(physics,pool,acquisition,particles,counter,9,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(released.equals(List.of(17,18,19)) && controls.stream().filter(x->x==ServerboundChainPackagePacket.PREPARED).count()==1,"stale hidden admission froze a released chain object");
            check(checkpoints.size()==1,"never-activated chain read back a prepared pendulum");
            for(int n=0;n<8;n++)acquisition.receive(chainCheckpoint(ClientboundChainPackagePacket.OFFER,30+n,910001+n,19,1,3));
            acquisition.pump(64,(o,c)->true);
            check(pool.metadataCount()==8 && physics.chainCount()==7 && tracks.count()==7,"chain aggregate reservation exceeded ordinary pool capacity");
            check(acquisition.pendingCount()==5 && released.containsAll(List.of(35,36,37)),"capacity refusal froze or lost overflow chain offers");
            var baselineEvents=BufferUtils.createByteBuffer(32);
            baselineEvents.putLong(0,910001).putLong(8,600001).putInt(16,0).putInt(20,1);
            tracks.rebasePrepared(2,baselineEvents);
            baselineEvents.putLong(8,600002);rejected=false;
            try{tracks.rebasePrepared(2,baselineEvents);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"prepared chain accepted foreign generation");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    static void poseQueries() {
        for(int count:new int[]{0,1,63,64,65,4095,4096,4097,131072}) {
            int capacity=Math.max(1,count);var state=bodies(capacity);var chain=bodies(capacity);var history=BufferUtils.createByteBuffer(capacity*32);
            var meta=BufferUtils.createByteBuffer(capacity*80);var admission=BufferUtils.createByteBuffer(capacity*32);
            for(int i=0;i<count;i++) {
                body(state,i,i*3,0,0,1);int p=i*64;state.putFloat(p+16,.125f).putFloat(p+44,i%360);
                chain.putFloat(p+12,5).putFloat(p+32,42).putFloat(p+36,90).putFloat(p+40,3)
                        .putFloat(p+48,i*3).putFloat(p+60,42);
                history.putFloat(i*32,i*3-.125f).putFloat(i*32+4,-.25f).putFloat(i*32+12,i%360-1)
                        .putFloat(i*32+16,i*3-.5f).putFloat(i*32+20,-.75f).putFloat(i*32+28,40);
                int m=i*80,a=i*32;long id=0x100000001L+i,generation=0x200000003L;
                meta.putLong(m,id).putLong(m+8,generation).putInt(m+16,count-1-i).putInt(m+28,PackagePoolGpu.CHAIN|PackagePoolGpu.HIDDEN);
                admission.putLong(a,id).putLong(a+8,generation).putInt(a+16,i+1).putInt(a+20,PackagePoolGpu.CHAIN);
            }
            int bodies=buffer(state),chains=buffer(chain),metadata=buffer(meta),admitted=buffer(admission),previous=buffer(history);
            try(var queries=new PackagePoseQueryGpu(capacity,99,PackageGpuValidation::source)) {
                var input=new PackagePoseQueryGpu.Input(bodies,chains,metadata,admitted,previous,count,count,capacity);
                var completed=new ArrayList<PackagePoseQueryGpu.Completed>();
                var ray=new PackagePoseQueryGpu.Ray(-2,0,0,Math.max(4,count*3+4),0,0);
                check(queries.pick(input,ray,11,"initial"),"GPU pick not submitted "+count);
                check(completed.isEmpty(),"GPU query trusted an unpolled fence");GL11.glFinish();queries.poll(completed::add);
                var pick=completed.removeFirst();var result=pick.results().getFirst();
                check(pick.submission()==11 && pick.kind()==PackagePoseQueryGpu.Kind.PICK && pick.tag().equals("initial"),"GPU query lost generation/tag");
                check(count==0?!result.present():result.present() && result.candidate()==count-1 && result.body()==0
                        && result.id()==0x100000000L+count && result.generation()==0x200000003L
                        && result.chain() && result.track()==5 && result.tx()==0 && result.vx()==.125f,"GPU nearest reduction/body namespace "+count);
                check(queries.readbackBytes()==PackagePoseQueryGpu.RESULT_BYTES,"GPU pick downloaded population-sized data");
                if(count==0)continue;
                var requests=new ArrayList<PackagePoseQueryGpu.Request>();
                for(int i=0;i<Math.min(count,256);i++)requests.add(new PackagePoseQueryGpu.Request(0x100000001L+i,0x200000003L,i,false));
                check(queries.poses(input,requests,12,"poses"),"GPU gather not submitted");GL11.glFinish();queries.poll(completed::add);
                var poses=completed.removeFirst();
                for(int i=0;i<requests.size();i++) {
                    var value=poses.results().get(i);check(value.present() && value.candidate()==i && value.body()==count-1-i
                            && value.x()==(count-1-i)*3 && value.tx()==value.x() && value.yaw()==(count-1-i)%360
                            && value.flags()==PackagePoolGpu.CHAIN,"GPU gather confused mutable metadata and committed flags");
                    check(value.px()==(count-1-i)*3-.125f && value.py()==-.25f && value.previousYaw()==(count-1-i)%360-1
                            && value.ptx()==(count-1-i)*3-.5f && value.pty()==-.75f && value.previousTargetYaw()==40,"GPU gather lost committed pose history");
                }
                // Request the exact current identity and a foreign generation in the same batch.
                check(queries.poses(input,List.of(new PackagePoseQueryGpu.Request(0x100000001L,0x200000003L,0,false),
                        new PackagePoseQueryGpu.Request(0x100000001L,0x200000004L,0,false)),13,"stale"),"GPU identity query submission");
                GL11.glFinish();queries.poll(completed::add);var stale=completed.removeFirst();
                check(stale.results().getFirst().present() && !stale.results().getLast().present(),"GPU query used a bare pool/body index for another generation");
                int last=(count-1)*64;state.putFloat(last+60,PackagePhysicsGpu.RETIRED);admission.putInt(16,0).putInt(20,0);
                putBuffer(bodies,state);putBuffer(admitted,admission);
                check(queries.poses(input,List.of(new PackagePoseQueryGpu.Request(0x100000001L,0x200000003L,0,false),
                        new PackagePoseQueryGpu.Request(0x100000001L,0x200000003L,0,true)),14,"retired"),"retired pose query submission");
                GL11.glFinish();queries.poll(completed::add);var retired=completed.removeFirst();
                check(!retired.results().getFirst().present() && retired.results().getLast().present() && retired.results().getLast().retired(),"retired pose policy failed");
                check(retired.results().getLast().x()==(count-1)*3 && retired.results().getLast().flags()==5,"retired pose/identity not preserved");
                state.putFloat(last+60,0);admission.putInt(16,1).putInt(20,1);putBuffer(bodies,state);putBuffer(admitted,admission);
                for(int bank=0;bank<4;bank++)check(queries.pick(input,ray,20+bank,bank),"GPU query bank pressure before full");
                check(!queries.pick(input,ray,24,"overflow") && queries.pending()==4,"GPU query overwrote a borrowed bank");
                // Outstanding copies remain immutable after the source changes and a failed program rebuild.
                state.putFloat(0,999);chain.putFloat(48,999);putBuffer(bodies,state);putBuffer(chains,chain);
                boolean rejected=false;try{queries.rebuild(name->name.contains("query_gather")?"INVALID":source(name));}catch(IllegalStateException expected){rejected=true;}
                check(rejected,"invalid query shader replaced working programs");
                GL11.glFinish();queries.poll(completed::add);check(completed.size()==4 && queries.pending()==0,"GPU query banks did not retire independently");
                for(int bank=0;bank<4;bank++)check(completed.get(bank).submission()==20+bank && completed.get(bank).tag().equals(bank)
                        && completed.get(bank).results().getFirst().x()==0,"GPU query snapshot overwritten or reordered");
                completed.clear();state.putFloat(0,0);chain.putFloat(48,0);putBuffer(bodies,state);putBuffer(chains,chain);
                check(queries.pick(input,ray,25,"rebuild"),"old query programs not retained after rebuild failure");GL11.glFinish();queries.poll(completed::add);
                check(completed.removeFirst().results().getFirst().body()==0,"old query programs changed after rebuild failure");
                // Compare slab/entry semantics against Minecraft AABB.clip, including axis-parallel
                // rays, inside starts, exact edges, negative direction and end-segment exclusion.
                var target=new net.minecraft.world.phys.Vec3((count-1)*3,0,0);
                var bounds=new net.minecraft.world.phys.AABB(target,target).move(0,-.25,0).expandTowards(0,.5,0).inflate(.45);
                var rays=List.of(new PackagePoseQueryGpu.Ray((float)target.x,0,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)target.x+.45f,0,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)target.x+.5f,0,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)target.x,0,2,0,0,-4),
                        new PackagePoseQueryGpu.Ray((float)target.x,0,0,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)target.x,.75f,-2,0,0,4));
                // Isolate this one candidate, so CPU oracle and GPU search have identical inputs.
                for(int i=1;i<count;i++)admission.putInt(i*32+16,0).putInt(i*32+20,0);putBuffer(admitted,admission);
                for(var test:rays) {
                    check(queries.pick(input,test,30,"oracle"),"query oracle submit");GL11.glFinish();queries.poll(completed::add);
                    var from=new net.minecraft.world.phys.Vec3(test.x(),test.y(),test.z());var to=from.add(test.dx(),test.dy(),test.dz());
                    boolean expected=bounds.clip(from,to).isPresent();var value=completed.removeFirst().results().getFirst();
                    check(value.present()==expected,"GPU ray differed from Create/Minecraft AABB.clip: "+count+" "+test+" expected="+expected);
                }
            }finally{GL15.glDeleteBuffers(bodies);GL15.glDeleteBuffers(chains);GL15.glDeleteBuffers(metadata);GL15.glDeleteBuffers(admitted);GL15.glDeleteBuffers(previous);}
        }
    }
    static final class CheckpointFixture implements AutoCloseable {
        final int count,capacity,bodies,chains,metadata,admitted,previous;
        final ByteBuffer state,chain,meta,admission,history;
        final PackagePoseQueryGpu.Input input;
        CheckpointFixture(int count) {
            this.count=count;capacity=Math.max(1,count);state=bodies(capacity);chain=bodies(capacity);
            meta=BufferUtils.createByteBuffer(capacity*80);admission=BufferUtils.createByteBuffer(capacity*32);history=BufferUtils.createByteBuffer(capacity*32);
            for(int i=0;i<count;i++) {
                body(state,i,i*3,0,0,1);state.putFloat(i*64+16,.125f).putFloat(i*64+44,i%360);
                chain.putFloat(i*64+12,5).putFloat(i*64+32,42).putFloat(i*64+36,90).putFloat(i*64+40,3)
                        .putFloat(i*64+48,i*3).putFloat(i*64+60,42);
                meta.putLong(i*80,0x100000001L+i).putLong(i*80+8,0x200000003L).putInt(i*80+16,count-1-i).putInt(i*80+28,5);
                admission.putLong(i*32,0x100000001L+i).putLong(i*32+8,0x200000003L).putInt(i*32+16,i+1).putInt(i*32+20,1);
                history.putFloat(i*32,i*3-.125f).putFloat(i*32+12,i%360-1).putFloat(i*32+16,i*3-.5f).putFloat(i*32+28,40);
            }
            bodies=buffer(state);chains=buffer(chain);metadata=buffer(meta);admitted=buffer(admission);previous=buffer(history);
            input=new PackagePoseQueryGpu.Input(bodies,chains,metadata,admitted,previous,count,count,capacity);
        }
        public void close(){for(int b:new int[]{bodies,chains,metadata,admitted,previous})GL15.glDeleteBuffers(b);}
        void free() {
            for(int i=0;i<count;i++){meta.putInt(i*80+28,0);admission.putInt(i*32+20,0);state.putFloat(i*64+28,i%2);}
            putBuffer(metadata,meta);putBuffer(admitted,admission);putBuffer(bodies,state);
        }
    }
    static void freePoseQueries() {
        for(int count:new int[]{0,1,63,64,65,4095,4096,4097,131072})try(var f=new CheckpointFixture(count);
                var queries=new PackagePoseQueryGpu(f.capacity,99,PackageGpuValidation::source)) {
            f.free();var completed=new ArrayList<PackagePoseQueryGpu.Completed>();
            var ray=new PackagePoseQueryGpu.Ray(-2,0,0,Math.max(4,count*3+4),0,0);
            check(queries.pickFree(f.input,ray,count,1,1,"free"),"Free pick not submitted");GL11.glFinish();queries.poll(completed::add);
            var value=completed.removeFirst().results().getFirst();
            if(count==0){check(!value.present(),"Empty free pick returned identity");continue;}
            check(value.present()&&!value.chain()&&value.body()==0&&value.candidate()==count-1&&value.id()==0x100000000L+count
                    &&Math.abs(value.tx()+.5f)<1e-5&&value.ty()==0&&value.halfHeight()==.5f&&!value.grounded(),"Free reduction/hit point/body/physical side fields: "+count+" "+value);
            check(queries.readbackBytes()==128,"Free pick downloaded population-sized pose data");
            // A nearer observer has the same flags. Only the committed free-body prefix may win.
            var backwards=new PackagePoseQueryGpu.Ray((count-1)*3+2,0,0,-Math.max(4,count*3+4),0,0);
            check(queries.pickFree(f.input,backwards,count-1,1,2,"observer exclusion"),"Free domain query not submitted");GL11.glFinish();queries.poll(completed::add);
            value=completed.removeFirst().results().getFirst();check(count==1?!value.present():value.present()&&value.body()==count-2,"Observer entered authority free pick");
            // Isolate one identity, compare interpolation and intersection against actual AABB.
            for(int i=1;i<count;i++)f.admission.putInt(i*32+16,0);putBuffer(f.admitted,f.admission);
            int body=count-1,p=body*64;var current=new net.minecraft.world.phys.Vec3(f.state.getFloat(p),f.state.getFloat(p+4),f.state.getFloat(p+8));
            var previous=new net.minecraft.world.phys.Vec3(f.history.getFloat(body*32),f.history.getFloat(body*32+4),f.history.getFloat(body*32+8));
            for(float partial:new float[]{0,.25f,.5f,.75f,1}) {
                var center=previous.lerp(current,partial);var bounds=new net.minecraft.world.phys.AABB(center,center).inflate(.5);
                var rays=List.of(new PackagePoseQueryGpu.Ray((float)center.x,0,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)center.x,0,2,0,0,-4),
                        new PackagePoseQueryGpu.Ray((float)center.x,0,0,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)center.x+.5f,0,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)center.x,1,-2,0,0,4),
                        new PackagePoseQueryGpu.Ray((float)center.x,0,-2,0,0,1.5f));
                for(var test:rays) {
                    check(queries.pickFree(f.input,test,count,partial,3,"oracle"),"Free oracle not submitted");GL11.glFinish();queries.poll(completed::add);
                    value=completed.removeFirst().results().getFirst();var from=new net.minecraft.world.phys.Vec3(test.x(),test.y(),test.z());
                    var clip=bounds.clip(from,from.add(test.dx(),test.dy(),test.dz()));boolean inside=bounds.contains(from);
                    check(value.present()==(inside||clip.isPresent()),"Free pick differed from vanilla AABB: "+count+" "+partial+" "+test);
                    if(value.present()){var hit=inside?from:clip.orElseThrow();check(Math.abs(value.tx()-hit.x)<1e-4&&Math.abs(value.ty()-hit.y)<1e-4&&Math.abs(value.tz()-hit.z)<1e-4,"Free GPU hit point differed from oracle");
                        check(Math.abs(value.ptx()-center.x)<1e-4&&Math.abs(value.pty()-center.y)<1e-4&&Math.abs(value.ptz()-center.z)<1e-4,
                                "Free input materialization did not match the GPU displayed center");}
                }
            }
            for(int bank=0;bank<4;bank++)check(queries.pickFree(f.input,backwards,count,1,10+bank,bank),"Free query bank pressure");
            check(!queries.pickFree(f.input,ray,count,1,14,"busy"),"Free query overwrote pending bank");
            f.state.putFloat(p,999);putBuffer(f.bodies,f.state);
            boolean rejected=false;try{queries.rebuild(name->"INVALID");}catch(IllegalStateException expected){rejected=true;}check(rejected,"Free query replaced working shader");
            GL11.glFinish();queries.poll(completed::add);for(var result:completed)check(result.results().getFirst().x()==current.x,"Free query snapshot changed under source mutation");
        }
    }
    static void parallelFreeChainQueries() {
        try(var f=new CheckpointFixture(2);var queries=new PackagePoseQueryGpu(2,77,PackageGpuValidation::source)) {
            f.free();f.meta.putInt(16,0).putInt(96,1).putInt(108,1);f.admission.putInt(52,1);
            f.state.putFloat(44,1);f.history.putFloat(12,359);f.chain.putFloat(76,0);
            putBuffer(f.metadata,f.meta);putBuffer(f.admitted,f.admission);putBuffer(f.bodies,f.state);putBuffer(f.previous,f.history);putBuffer(f.chains,f.chain);
            var queue=new PackageFreePickQueue(77,()->0);queue.enqueue(PackageFreePickQueue.Action.USE,new PackagePoseQueryGpu.Ray(0,0,-2,0,0,4),"mixed native input");
            var input=queue.queued();Object chainTag=new Object();var completed=new ArrayList<PackagePoseQueryGpu.Completed>();
            check(queries.pick(f.input,new PackagePoseQueryGpu.Ray(3,0,-2,0,0,4),7,chainTag),"Parallel chain pick unavailable");
            check(queries.pickFree(f.input,input.ray(),1,.5f,7,input),"Parallel free pick unavailable");queue.submitted(input);
            check(queue.result()==null,"Parallel native input trusted pending GPU work");
            GL11.glFinish();queries.poll(completed::add);check(completed.size()==2&&queries.readbackBytes()==256,"Parallel queries downloaded a population or lost a domain");
            var chain=completed.stream().filter(c->c.tag()==chainTag).findFirst().orElseThrow().results().getFirst();
            check(chain.present()&&chain.chain()&&chain.candidate()==1&&chain.body()==1,"Parallel query returned wrong chain identity");
            var free=completed.stream().filter(c->c.tag()==input).findFirst().orElseThrow();check(queue.completed(free),"Parallel free completion failed its input tag");
            check(queue.result().present()&&!queue.result().chain()&&queue.result().body()==0&&queue.result().previousTargetYaw()==360,
                    "Free input lost its authority prefix or shortest-path displayed yaw");
            queue.clear();check(!queue.completed(free),"Parallel native input replayed twice");
        }
    }
    static void chainCheckpoints() {
        for(var transfer:PackageChainCheckpointGpu.Transfer.values())for(int count:new int[]{0,1,63,64,65,131072}) {
            try(var f=new CheckpointFixture(count);var checkpoint=new PackageChainCheckpointGpu(f.capacity,99,PackageGpuValidation::source,transfer)) {
                check(checkpoint.latestSubmission()==-1 && checkpoint.epoch()==99,"checkpoint trusted uninitialized mapped storage");
                if(count==0){check(!checkpoint.capture(f.input,1) && checkpoint.readbackBytes()==0,"zero checkpoint dispatched stale records");continue;}
                check(checkpoint.capture(f.input,1),"checkpoint initial capture");
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"checkpoint read before completed fence publication");
                GL11.glFinish();check(checkpoint.poll()==1,"checkpoint not completed");
                for(int i=0;i<count;i++) {
                    var pose=checkpoint.find(i,0x100000001L+i,0x200000003L);int body=count-1-i;
                    check(pose.present() && pose.candidate()==i && pose.body()==body && pose.chain() && pose.track()==5
                            && pose.x()==body*3 && pose.px()==body*3-.125f && pose.ptx()==body*3-.5f && pose.vx()==.125f,
                            "checkpoint identity/body/history mapping "+transfer+" "+count+" "+i);
                }
                check(!checkpoint.find(0,0x100000002L,0x200000003L).present()
                        && !checkpoint.find(0,0x100000001L,0x200000004L).present(),"checkpoint accepted bare candidate foreign identity");
                for(int s=2;s<=4;s++)check(checkpoint.capture(f.input,s),"checkpoint retained-bank pressure");
                check(!checkpoint.capture(f.input,5) && checkpoint.pending()==3,"checkpoint overwrote latest confirmed bank");
                f.state.putFloat((count-1)*64,999);putBuffer(f.bodies,f.state);
                check(checkpoint.find(0,0x100000001L,0x200000003L).x()==(count-1)*3,"unfinished checkpoint replaced completed pose");
                GL11.glFinish();check(checkpoint.poll()==3 && checkpoint.latestSubmission()==4,"checkpoint pressure lost monotonic publication");
                check(checkpoint.find(0,0x100000001L,0x200000003L).x()==(count-1)*3,"immutable checkpoint source overwrite");
                boolean rejected=false;try{checkpoint.rebuild(name->"INVALID");}catch(IllegalStateException expected){rejected=true;}
                check(rejected,"checkpoint rebuild replaced good shader");
                check(checkpoint.capture(f.input,5),"checkpoint did not retry skipped generation");GL11.glFinish();checkpoint.poll();
                check(checkpoint.find(0,0x100000001L,0x200000003L).x()==999,"old checkpoint program was not retained");
                f.admission.putInt(20,5);putBuffer(f.admitted,f.admission);checkpoint.capture(f.input,51);GL11.glFinish();checkpoint.poll();
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"hidden active body entered checkpoint");
                f.admission.putInt(20,0);putBuffer(f.admitted,f.admission);checkpoint.capture(f.input,511);GL11.glFinish();checkpoint.poll();
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"checkpoint trusted stale metadata type instead of committed admission");
                f.admission.putInt(20,1);f.meta.putInt(28,0);putBuffer(f.admitted,f.admission);putBuffer(f.metadata,f.meta);
                checkpoint.capture(f.input,512);GL11.glFinish();checkpoint.poll();
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"free package entered chain checkpoint");
                f.meta.putInt(28,5);putBuffer(f.metadata,f.meta);
                // Prepared/hidden bodies never become visible recovery records; retired preserves
                // the last physical pose only when admission contains the exact full identity.
                f.state.putFloat((count-1)*64+60,-2);putBuffer(f.bodies,f.state);checkpoint.capture(f.input,600);GL11.glFinish();checkpoint.poll();
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"prepared checkpoint leaked into recovery");
                f.state.putFloat((count-1)*64+60,-3);f.admission.putInt(16,0).putInt(20,0);
                putBuffer(f.bodies,f.state);putBuffer(f.admitted,f.admission);checkpoint.capture(f.input,700);GL11.glFinish();checkpoint.poll();
                check(checkpoint.find(0,0x100000001L,0x200000003L).retired(),"retired emergency checkpoint lost physical state");
                f.admission.putLong(8,0x200000004L);putBuffer(f.admitted,f.admission);checkpoint.capture(f.input,800);GL11.glFinish();checkpoint.poll();
                check(!checkpoint.find(0,0x100000001L,0x200000003L).present(),"checkpoint ignored admission generation mismatch");
                // Close with unresolved flights; no wait/download and the next epoch has no view.
                for(int s=900;s<=902;s++)check(checkpoint.capture(f.input,s),"checkpoint close pressure");
            }
            try(var fresh=new PackageChainCheckpointGpu(Math.max(1,count),100,PackageGpuValidation::source,transfer)) {
                check(fresh.latestSubmission()==-1 && !fresh.find(0,0x100000001L,0x200000003L).present(),"closed checkpoint leaked into a new epoch");
            }
        }
        // Initially all four independent banks are usable until the first complete view is pinned.
        try(var f=new CheckpointFixture(65);var checkpoint=new PackageChainCheckpointGpu(65,99,PackageGpuValidation::source)) {
            for(int s=1;s<=4;s++)check(checkpoint.capture(f.input,s),"initial four checkpoint banks not independent");
            check(!checkpoint.capture(f.input,5) && checkpoint.pending()==4,"fifth checkpoint overwrote in-flight data");
            check(checkpoint.overdue(System.nanoTime()+200_000_000L),"checkpoint processing deadline ignored");
            GL11.glFinish();check(checkpoint.poll()==4 && !checkpoint.overdue(System.nanoTime()),"completed checkpoint did not clear deadline");
        }
    }
    static volatile double checkpointChecksum;
    static void chainCheckpointBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,transfer,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,cpu_poll_p50_ms,cpu_poll_p95_ms,cpu_emergency_decode_p50_ms,cpu_emergency_decode_p95_ms,readback_bytes,storage_bytes");
        samples.add("count,transfer,run,sample,gpu_ms,cpu_submit_ms,cpu_poll_ms,cpu_emergency_decode_ms");
        for(int count:new int[]{10000,65536,131072})for(var transfer:PackageChainCheckpointGpu.Transfer.values()) {
            try(var f=new CheckpointFixture(count);var checkpoint=new PackageChainCheckpointGpu(count,99,PackageGpuValidation::source,transfer)) {
                long sequence=1;int timer=GL15.glGenQueries();
                try {
                    long start=System.nanoTime();int warm=0;
                    while(warm<30 || System.nanoTime()-start<1_000_000_000L){checkpoint.capture(f.input,sequence++);GL11.glFinish();checkpoint.poll();warm++;}
                    for(int run=0;run<3;run++) {
                        double[] gpu=new double[120],submit=new double[120],poll=new double[120],decode=new double[120];
                        for(int sample=0;sample<120;sample++) {
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long begin=System.nanoTime();
                            check(checkpoint.capture(f.input,sequence++),"checkpoint benchmark full ring");submit[sample]=(System.nanoTime()-begin)/1e6;
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[sample]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
                            begin=System.nanoTime();check(checkpoint.poll()==1,"checkpoint benchmark no complete publication");poll[sample]=(System.nanoTime()-begin)/1e6;
                            begin=System.nanoTime();double sum=0;
                            for(int i=0;i<count;i++){var value=checkpoint.find(i,0x100000001L+i,0x200000003L);sum+=value.x()+value.px()+value.vx();}
                            checkpointChecksum=sum;decode[sample]=(System.nanoTime()-begin)/1e6;
                            check(sum==3.0*count*(count-1),"checkpoint benchmark omitted moving state");
                            samples.add(count+","+transfer+","+run+","+sample+","+gpu[sample]+","+submit[sample]+","+poll[sample]+","+decode[sample]);
                        }
                        String row=count+","+transfer+","+run+","+percentile(gpu,.5)+","+percentile(gpu,.95)+","+percentile(submit,.5)+","+percentile(submit,.95)
                                +","+percentile(poll,.5)+","+percentile(poll,.95)+","+percentile(decode,.5)+","+percentile(decode,.95)
                                +","+(long)count*128+","+checkpoint.storageBytes();rows.add(row);System.out.println(row);
                        Files.write(Path.of("build/package-chain-checkpoint.csv"),rows);Files.write(Path.of("build/package-chain-checkpoint-samples.csv"),samples);
                    }
                }finally{GL15.glDeleteQueries(timer);}
            }
        }
    }
    static void poseQueryBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,cpu_decode_p50_ms,cpu_decode_p95_ms,readback_bytes");
        samples.add("count,run,sample,gpu_ms,cpu_submit_ms,cpu_decode_ms");
        for(int count:new int[]{10000,65536,131072}) {
            var state=bodies(count);var chain=bodies(count);var meta=BufferUtils.createByteBuffer(count*80);
            var history=BufferUtils.createByteBuffer(count*32);
            var admission=BufferUtils.createByteBuffer(count*32);var requests=new ArrayList<PackagePoseQueryGpu.Request>();
            for(int i=0;i<count;i++) {
                float x=(i%256)*2,y=(i/65536)*2,z=(i/256%256)*2;int p=i*64;
                body(state,i,x,y,z,1);chain.putFloat(p+12,0).putFloat(p+32,42).putFloat(p+36,90).putFloat(p+40,3)
                        .putFloat(p+48,x).putFloat(p+52,y).putFloat(p+56,z).putFloat(p+60,42);
                history.putFloat(i*32,x).putFloat(i*32+4,y).putFloat(i*32+8,z)
                        .putFloat(i*32+16,x).putFloat(i*32+20,y).putFloat(i*32+24,z);
                meta.putLong(i*80,i+1L).putLong(i*80+8,1).putInt(i*80+16,i).putInt(i*80+28,1);
                admission.putLong(i*32,i+1L).putLong(i*32+8,1).putInt(i*32+16,i+1).putInt(i*32+20,1);
            }
            for(int i=0;i<256;i++){int candidate=i*(count-1)/255;requests.add(new PackagePoseQueryGpu.Request(candidate+1L,1,candidate,false));}
            int bodies=buffer(state),chains=buffer(chain),metadata=buffer(meta),admitted=buffer(admission),previous=buffer(history),timer=GL15.glGenQueries();
            try(var queries=new PackagePoseQueryGpu(count,99,PackageGpuValidation::source)) {
                var input=new PackagePoseQueryGpu.Input(bodies,chains,metadata,admitted,previous,count,count,count);
                var hit=new PackagePoseQueryGpu.Ray(-2,.1f,.1f,8,0,0);var miss=new PackagePoseQueryGpu.Ray(-2,-4,-4,8,0,0);
                for(String mode:List.of("pick_hit","pick_miss","gather_256"))for(int run=1;run<=3;run++) {
                    boolean gather=mode.equals("gather_256");var ray=mode.equals("pick_hit")?hit:miss;
                    java.util.function.Consumer<PackagePoseQueryGpu.Completed> verify=c->{
                        check(c.results().size()==(gather?256:1),"query benchmark result count");
                        if(gather)for(int i=0;i<256;i++)check(c.results().get(i).id()==requests.get(i).id(),"gather benchmark dropped identities");
                        else check(mode.equals("pick_hit")?c.results().getFirst().id()==1:!c.results().getFirst().present(),"pick benchmark reduced wrong hit");
                    };
                    long sequence=0,warmUntil=System.nanoTime()+1_000_000_000;int warm=0;
                    while(warm++<30 || System.nanoTime()<warmUntil) {
                        check(gather?queries.poses(input,requests,sequence++,mode):queries.pick(input,ray,sequence++,mode),"query warmup capture");
                        GL11.glFinish();check(queries.poll(verify)==1,"query warmup readback");
                    }
                    double[] gpu=new double[120],cpu=new double[120],decode=new double[120];long beforeBytes=queries.readbackBytes();
                    for(int s=0;s<120;s++) {
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long started=System.nanoTime();
                        boolean submitted=gather?queries.poses(input,requests,sequence++,mode):queries.pick(input,ray,sequence++,mode);
                        cpu[s]=(System.nanoTime()-started)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        check(submitted,"query benchmark capture");gpu[s]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
                        started=System.nanoTime();int decoded=queries.poll(c->{});decode[s]=(System.nanoTime()-started)/1e6;
                        check(decoded==1,"timer completion did not complete query snapshot");
                        samples.add(count+","+mode+","+run+","+s+","+gpu[s]+","+cpu[s]+","+decode[s]);
                    }
                    long bytes=(queries.readbackBytes()-beforeBytes)/120;
                    check(bytes==(gather?256:1)*PackagePoseQueryGpu.RESULT_BYTES,"query benchmark downloaded excess data");
                    String row=count+","+mode+","+run+","+percentile(gpu,.5)+","+percentile(gpu,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                            +","+percentile(decode,.5)+","+percentile(decode,.95)+","+bytes;
                    rows.add(row);System.out.println(row);Files.write(Path.of("build/package-pose-query.csv"),rows);
                    Files.write(Path.of("build/package-pose-query-samples.csv"),samples);
                }
            }finally{GL15.glDeleteBuffers(bodies);GL15.glDeleteBuffers(chains);GL15.glDeleteBuffers(metadata);GL15.glDeleteBuffers(admitted);GL15.glDeleteBuffers(previous);GL15.glDeleteQueries(timer);}
        }
    }
    static void chainEventChannels() {
        for(int n:new int[]{0,1,65,1025,20000}) {
            int capacity=Math.max(1,n);var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();
            var transport=new ChainTransport();var encoder=new PackageDeltaJournal.Encoder(tasks::add,4);
            try(var gpu=new PackagePhysicsGpu(capacity,2,PackageGpuValidation::source);
                var tracks=new PackageChainTrackGpu(capacity,1,1,PackageGpuValidation::source);
                var channel=new PackageChainEventChannel(tracks,77,3,encoder,transport,clock::get)) {
                var node=BufferUtils.createByteBuffer(16);node.putFloat(0,1.5f).putInt(4,1);
                tracks.uploadTables(linearTrack(7,0,1),node);
                var meta=BufferUtils.createByteBuffer(n*32);var b=bodies(n);var c=bodies(n);
                for(int i=0;i<n;i++){body(b,i,0,-9f/16f,0,1);c.putFloat(i*64+40,2);
                    meta.putLong(i*32,i+1L).putLong(i*32+8,99).putInt(i*32+20,1).putInt(i*32+24,1);}
                int split=n/2;var first=meta.duplicate();first.limit(split*32);channel.append(first);
                var second=meta.duplicate();second.position(split*32);channel.append(second);
                gpu.upload(b,n);gpu.uploadChains(c);gpu.stepChains(.05f,tracks);
                for(int bank=0;bank<4;bank++)check(channel.capture(),"chain channel snapshot unavailable");
                check(!channel.capture(),"chain channel full bank overwritten");
                for(int frame=0;frame<8;frame++)gpu.stepChains(.05f,tracks);
                for(int frame=0;frame<10;frame++){GL11.glFinish();channel.pump(4);}
                check(transport.records==0 && transport.failures==0,"unfinished chain worker was sent");
                var pending=readBuffer(tracks.pendingBuffer(),n*64);
                for(int i=0;i<n;i++)check(pending.getInt(i*64+32)==1,"readback implicitly ACKed chain crossing");
                transport.rejectAfterFirst=true;
                while(!tasks.isEmpty())tasks.remove().run();channel.pump(4);
                if(n==0){check(channel.stats().payloadBytes()==0 && transport.sent.isEmpty(),"empty channel downloaded records");continue;}
                check(transport.sent.size()==1,"partial chain transport ignored ordered refusal");
                check(!channel.acknowledge(78,3,0) && !channel.acknowledge(77,4,0)
                        && !channel.acknowledge(77,3,99999),"stale or unsent chain ACK accepted");
                check(channel.acknowledge(77,3,0) && !channel.acknowledge(77,3,0),"chain ACK mailbox failed duplicate gate");
                clock.set(9_000_000);channel.pump(4);
                check(channel.stats().latestRoundTripNanos()==9_000_000,"chain network latency not measured separately");
                transport.rejectAfterFirst=false;
                for(int frame=0;frame<160 && transport.records<n;frame++) {
                    GL11.glFinish();channel.pump(4);while(!tasks.isEmpty())tasks.remove().run();
                }
                check(transport.records==n && transport.ahead.cardinality()==n,"fragmented chain readback lost anticipation");
                for(long sequence:transport.sent)if(sequence!=0)check(channel.acknowledge(77,3,sequence),"chain packet ACK rejected");
                channel.pump(4);pending=readBuffer(tracks.pendingBuffer(),n*64);
                for(int i=0;i<n;i++)check(pending.getInt(i*64+32)==1,"old anticipation ACK erased newer actual node event");
                int oldPackets=transport.sent.size();check(channel.capture(),"chain channel actual retry unavailable");
                for(int frame=0;frame<160 && transport.records<n*2;frame++) {
                    GL11.glFinish();channel.pump(4);while(!tasks.isEmpty())tasks.remove().run();
                }
                check(transport.records==n*2 && transport.actual.cardinality()==n && transport.failures==0,"chain actual event transport incomplete");
                check(channel.stats().payloadBytes()==(long)n*2*64 && channel.stats().wireBytes()<channel.stats().payloadBytes(),
                        "chain readback copied maximum capacity or codec expanded normal events");
                for(int i=oldPackets;i<transport.sent.size();i++)check(channel.acknowledge(77,3,transport.sent.get(i)),"chain actual ACK rejected");
                channel.pump(4);gpu.stepChains(.05f,tracks);var links=readBuffer(gpu.chainBuffer(),n*64);
                for(int i=0;i<n;i++)check(links.getFloat(i*64+32)==3,"channel server ACK did not release chain progress");
                check(channel.capture(),"chain clean capture unavailable");
                for(int frame=0;frame<4;frame++){GL11.glFinish();channel.pump(4);}
                check(transport.records==n*2 && channel.stats().ackedPackets()==transport.sent.size(),"clean chain state retransmitted");
                channel.close();check(!channel.acknowledge(77,3,0),"closed chain epoch accepted late ACK");
            }
        }
        for(boolean preparationFailure:new boolean[]{false,true}) {
            var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();var transport=new ChainTransport();
            var encoder=new PackageDeltaJournal.Encoder(tasks::add,1);
            try(var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source);
                var tracks=new PackageChainTrackGpu(1,1,1,PackageGpuValidation::source);
                var channel=new PackageChainEventChannel(tracks,77,3,encoder,transport,clock::get)) {
                var node=BufferUtils.createByteBuffer(16);node.putFloat(0,1.5f).putInt(4,1);tracks.uploadTables(linearTrack(7,0,1),node);
                var m=BufferUtils.createByteBuffer(32);m.putLong(0,1).putLong(8,99).putInt(20,1).putInt(24,1);channel.append(m);
                var b=bodies(1);body(b,0,0,-9f/16f,0,1);var c=bodies(1);c.putFloat(40,2);gpu.upload(b,1);gpu.uploadChains(c);gpu.stepChains(.05f,tracks);
                check(channel.capture(),"failure fixture chain capture");
                if(preparationFailure){transport.failPreparation=true;for(int frame=0;frame<8;frame++){GL11.glFinish();channel.pump(4);}
                    while(!tasks.isEmpty())tasks.remove().run();channel.pump(4);}
                else{clock.set(100_000_000L+1);GL11.glFinish();channel.pump(4);}
                if(preparationFailure){check(channel.closed() && transport.failures==1 && transport.records==0,"chain preparation failure published partial events");
                    channel.pump(4);check(transport.failures==1 && !channel.acknowledge(77,3,0),"chain failure repeated or old ACK applied");}
                else{check(!channel.closed()&&transport.failures==0&&transport.records==0,"worker latency revoked reliable chain journal");channel.close();}
                while(!tasks.isEmpty())tasks.remove().run();check(encoder.activeTasks()==0,"chain failed worker budget leaked");
            }
        }
    }
    static void trackedChainLoopReference() {
        int n=16;float[] before={358,1,179,181,0,0,359,1,45,315,135,225,270,90,5,355};
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var tracks=new PackageChainTrackGpu(n,n,n,PackageGpuValidation::source)) {
            var table=bodies(n);var node=BufferUtils.createByteBuffer(n*16);var b=bodies(n);var c=bodies(n);var m=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++) {
                int p=i*64;boolean reversed=i%2==1;float rate=reversed?-60:60;float threshold=i==2||i==3?180:0;
                body(b,i,i*3,-9f/16f,.875f,1);
                table.putFloat(p,i*3).putFloat(p+12,.875f).putFloat(p+32,rate).putFloat(p+36,1).putFloat(p+40,reversed?1:0)
                        .putInt(p+48,i).putInt(p+52,1).putLong(p+56,1);
                c.putFloat(p+12,i).putFloat(p+32,before[i]).putFloat(p+40,3);
                node.putFloat(i*16,threshold).putInt(i*16+4,1).putInt(i*16+8,i);
                m.putLong(i*32,i+1L).putLong(i*32+8,1).putInt(i*32+16,i).putInt(i*32+20,1).putInt(i*32+24,1);
            }
            tracks.uploadTables(table,node);tracks.append(m);gpu.upload(b,n);gpu.uploadChains(c);gpu.stepChains(.05f,tracks);
            var events=readBuffer(tracks.pendingBuffer(),n*64);var links=readBuffer(gpu.chainBuffer(),n*64);var state=read(gpu);
            for(int i=0;i<n;i++) {
                boolean reversed=i%2==1;float rate=reversed?-60:60;float after=(before[i]+rate*.05f+360)%360,threshold=i==2||i==3?180:0;
                float anticipated=(after+rate*.2f+360)%360;
                int a=net.minecraft.util.Mth.sign(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(threshold,before[i]));
                int d=net.minecraft.util.Mth.sign(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(threshold,after));
                int e=net.minecraft.util.Mth.sign(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(threshold,anticipated));
                boolean crossed=reversed?a>d:a<d,ahead=!crossed && (reversed?a>e:a<e);
                check(events.getInt(i*64+32)==(crossed?1:0) && events.getInt(i*64+36)==(ahead?1:0),"Create loopThresholdCrossed exact reference");
                check(Math.abs(links.getFloat(i*64+32)-after)<1e-5 && links.getFloat(i*64+44)==(reversed?1:0),"loop progress/reversed state");
                float[] position={i*3,-9f/16f,.875f};
                float[] target={i*3+(float)Math.sin(Math.toRadians(after))*.875f,0,(float)Math.cos(Math.toRadians(after))*.875f};
                float distance=0;for(int j=0;j<3;j++)distance+=(position[j]-target[j])*(position[j]-target[j]);distance=(float)Math.sqrt(distance);
                if(distance>1.5f)for(int j=0;j<3;j++)position[j]=target[j]+(position[j]-target[j])*(1.5f/distance);
                for(int j=0;j<3;j++) {
                    float motion=(j==1?-.25f:0)*.75f+(target[j]-position[j])*.25f;position[j]+=motion;
                    check(Math.abs(state.getFloat(i*64+j*4)-position[j])<1e-4 && Math.abs(state.getFloat(i*64+16+j*4)-motion)<1e-4,
                            "tracked Create swing position/velocity reference");
                    check(Math.abs(links.getFloat(i*64+48+j*4)-(target[j]-(j==1?9f/16f:0)))<1e-4,"tracked Create rig target reference");
                }
                float expectedYaw=net.createmod.catnip.math.AngleHelper.angleLerp(.25,0,after+(reversed?180:0));
                check(Math.abs(state.getFloat(i*64+44)-expectedYaw)<1e-4,"tracked Create yaw reference");
            }
            var beforeInvalid=read(gpu);var invalidBody=beforeInvalid.duplicate();invalidBody.limit(64);
            var invalidChain=readBuffer(gpu.chainBuffer(),n*64);invalidChain.limit(64);invalidChain.putFloat(12,.25f);
            gpu.replace(0,invalidBody,invalidChain,1,true);gpu.stepChains(.05f,tracks);var afterInvalid=read(gpu);
            for(int j=0;j<3;j++)check(afterInvalid.getFloat(j*4)==beforeInvalid.getFloat(j*4),"fractional chain index moved a body before pause");
            check(afterInvalid.getFloat(60)<0 && (readBuffer(tracks.pendingBuffer(),64).getInt(44)&0x80000000)!=0,
                    "fractional chain index did not request pause");
        }
    }
    static void chainTrackBenchmark()throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,repeat,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms");
        samples.add("count,repeat,sample,gpu_ms,cpu_submit_ms");
        for(int n:new int[]{10000,65536,131072})for(String mode:new String[]{"legacy_loop","shared_loop","shared_ports"})for(int run=1;run<=3;run++) {
            boolean shared=!mode.equals("legacy_loop"),ports=mode.equals("shared_ports");int nt=16,nodesPerTrack=ports?4:0;
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
                var tracks=new PackageChainTrackGpu(n,nt,Math.max(1,nt*nodesPerTrack),PackageGpuValidation::source)) {
                var b=bodies(n);var c=bodies(n);var table=bodies(nt);var nodes=BufferUtils.createByteBuffer(nt*nodesPerTrack*16);
                var meta=BufferUtils.createByteBuffer(n*32);
                for(int t=0;t<nt;t++) {
                    int p=t*64;table.putFloat(p,t*3).putFloat(p+12,.875f).putFloat(p+32,90).putFloat(p+36,1)
                            .putInt(p+48,t*nodesPerTrack).putInt(p+52,nodesPerTrack).putLong(p+56,1);
                    for(int node=0;node<nodesPerTrack;node++){int q=(t*nodesPerTrack+node)*16;nodes.putFloat(q,node*90).putInt(q+4,1).putInt(q+8,node);}
                }
                for(int i=0;i<n;i++) {
                    int t=i%nt,p=i*64;float angle=(i*137.50776f)%360,rad=(float)Math.toRadians(angle);
                    float x=t*3+(float)Math.sin(rad)*.875f,z=(float)Math.cos(rad)*.875f;
                    body(b,i,x,-9f/16f,z,1);b.putFloat(p+44,angle);
                    c.putFloat(p,shared?0:t*3).putFloat(p+12,shared?t:.875f).putFloat(p+32,angle).putFloat(p+36,90)
                            .putFloat(p+40,shared?3:1).putFloat(p+48,x).putFloat(p+52,-9f/16f).putFloat(p+56,z).putFloat(p+60,angle);
                    meta.putLong(i*32,i+1L).putLong(i*32+8,1).putInt(i*32+16,t).putInt(i*32+20,ports?15:0).putInt(i*32+24,1);
                }
                tracks.uploadTables(table,nodes);tracks.append(meta);gpu.upload(b,n);gpu.uploadChains(c);
                long warmDeadline=System.nanoTime()+1_000_000_000L;int warm=0;
                while(warm++<30 || System.nanoTime()<warmDeadline) {
                    if(shared){gpu.stepChains(.05f,tracks);var capture=tracks.capture(n);tracks.acknowledge(capture);tracks.finish(capture);}
                    else gpu.stepChains(.05f);
                    GL11.glFinish();
                }
                var beforeRun=read(gpu);
                double[] timing=new double[120],cpu=new double[120];int query=GL15.glGenQueries();
                try {
                    for(int s=0;s<120;s++) {
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long started=System.nanoTime();
                        if(shared){gpu.stepChains(.05f,tracks);var capture=tracks.capture(n);tracks.acknowledge(capture);tracks.finish(capture);}
                        else gpu.stepChains(.05f);
                        cpu[s]=(System.nanoTime()-started)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        timing[s]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
                        samples.add(n+","+mode+","+run+","+s+","+timing[s]+","+cpu[s]);
                    }
                }finally{GL15.glDeleteQueries(query);}
                // Numerical motion check is outside timings. No retired/unknown bodies may replace active work.
                var after=read(gpu);for(int i=0;i<n;i++) {
                    int p=i*64;double distance=0;for(int j=0;j<3;j++){double d=after.getFloat(p+j*4)-beforeRun.getFloat(p+j*4);distance+=d*d;}
                    check(after.getFloat(p+60)>=0 && Float.isFinite(after.getFloat(p+4)) && distance>.0001,"chain benchmark silent fallback/static work");
                }
                String row=n+","+mode+","+run+","+percentile(timing,.5)+","+percentile(timing,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95);
                rows.add(row);System.out.println(row);Files.write(Path.of("build/package-chain-tracks.csv"),rows);
                Files.write(Path.of("build/package-chain-tracks-samples.csv"),samples);
            }
        }
    }
    static void incrementalPhysics() {
        try(var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
            ByteBuffer first=bodies(63),firstChains=bodies(63);
            for(int i=0;i<63;i++) {
                body(first,i,i*3,-9f/16f,.875f,1);
                firstChains.putFloat(i*64,i*3).putFloat(i*64+12,.875f)
                        .putFloat(i*64+36,90).putFloat(i*64+40,1);
            }
            gpu.append(first,firstChains,63,true);
            gpu.stepChains(.05f);
            ByteBuffer old=read(gpu),oldHistory=readBuffer(gpu.historyBuffer(),63*32);
            ByteBuffer next=bodies(2),nextChains=bodies(2);
            for(int i=0;i<2;i++) {
                body(next,i,200+i*3,-9f/16f,.875f,1);
                nextChains.putFloat(i*64,200+i*3).putFloat(i*64+12,.875f)
                        .putFloat(i*64+36,90).putFloat(i*64+40,1);
            }
            gpu.append(next,nextChains,2,true);
            check(gpu.count()==65,"incremental physics count at workgroup boundary");
            ByteBuffer joined=read(gpu),history=readBuffer(gpu.historyBuffer(),65*32);
            for(int i=0;i<63*64;i++)check(joined.get(i)==old.get(i),"append reset an existing body");
            for(int i=0;i<63*32;i++)check(history.get(i)==oldHistory.get(i),"append reset existing interpolation history");
            for(int i=63;i<65;i++) {
                check(joined.getFloat(i*64)==200+(i-63)*3,"appended body offset");
                check(Math.abs(history.getFloat(i*32+16)-(200+(i-63)*3))<1e-5,"appended chain target");
            }
            ByteBuffer rebased=bodies(1),rebasedChain=bodies(1);
            body(rebased,0,444,-9f/16f,.875f,1);
            rebasedChain.putFloat(0,444).putFloat(12,.875f).putFloat(36,90).putFloat(40,1);
            gpu.replace(31,rebased,rebasedChain,1,true);
            ByteBuffer afterRebase=read(gpu),afterHistory=readBuffer(gpu.historyBuffer(),65*32);
            for(int i=0;i<65;i++)if(i!=31) {
                for(int byteIndex=0;byteIndex<64;byteIndex++)
                    check(afterRebase.get(i*64+byteIndex)==joined.get(i*64+byteIndex),"final checkpoint reset another body");
                for(int byteIndex=0;byteIndex<32;byteIndex++)
                    check(afterHistory.get(i*32+byteIndex)==history.get(i*32+byteIndex),"final checkpoint reset another interpolation history");
            }
            check(afterRebase.getFloat(31*64)==444 && afterHistory.getFloat(31*32)==444
                    && Math.abs(afterHistory.getFloat(31*32+16)-444)<1e-5,
                    "chain final checkpoint did not rebase pose/target history");
            boolean rejected=false;
            try{gpu.append(bodies(1),bodies(1),1,true);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected && gpu.count()==65,"capacity overflow changed physics count");
            gpu.stepChains(.05f);
            joined=read(gpu);
            for(int i=0;i<65;i++)check(Float.isFinite(joined.getFloat(i*64)),"append/ping-pong chain lost body");
        }
        try(var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer one=bodies(1);body(one,0,0,10,0,1);
            gpu.append(one,bodies(1),1,false);gpu.step(.05f);
            float prior=read(gpu).getFloat(4);
            ByteBuffer second=bodies(1);body(second,0,5,10,0,1);
            gpu.append(second,bodies(1),1,false);
            check(read(gpu).getFloat(4)==prior,"free append reset existing integration");
            gpu.step(.05f);ByteBuffer result=read(gpu);
            check(result.getFloat(4)<prior && result.getFloat(64)==5,"free append failed after ping-pong");
            ByteBuffer freeRebase=bodies(1);body(freeRebase,0,3,20,0,1);
            ByteBuffer oldOther=readBuffer(gpu.historyBuffer(),64);
            gpu.replace(0,freeRebase,bodies(1),1,false);
            ByteBuffer newHistory=readBuffer(gpu.historyBuffer(),64);
            check(read(gpu).getFloat(0)==3 && newHistory.getFloat(0)==3,
                    "free final checkpoint did not replace the current ping-pong body");
            for(int byteIndex=32;byteIndex<64;byteIndex++)
                check(newHistory.get(byteIndex)==oldOther.get(byteIndex),"free checkpoint touched another history record");
            freeRebase.putFloat(12,0);gpu.replace(0,freeRebase,bodies(1),1,false);
            check(gpu.staticBodyCount()==1,"static checkpoint did not enable static collision queries");
            freeRebase.putFloat(12,1);gpu.replace(0,freeRebase,bodies(1),1,false);
            check(gpu.staticBodyCount()==0,"last static replacement retained redundant static GPU queries");
            body(second,0,5,10,0,0);gpu.upload(second,1);
            check(gpu.staticBodyCount()==1,"epoch upload lost static collider");
            body(second,0,8,10,0,0);gpu.append(second,bodies(1),1,false);
            check(gpu.staticBodyCount()==2,"appended static collider count");
            freeRebase.putFloat(12,1);gpu.replace(0,freeRebase,bodies(1),1,false);
            check(gpu.staticBodyCount()==1,"partial replacement disabled remaining static collision queries");
            gpu.retire(1);check(gpu.staticBodyCount()==0,"retired static collider retained redundant GPU queries");
            gpu.upload(freeRebase,1);check(gpu.staticBodyCount()==0,"epoch reset retained a removed static collider");
        }
    }
    static void mixedPhysics() {
        try(var mixed=new PackageMixedPhysicsGpu(64,65,2,PackageGpuValidation::source)) {
            ByteBuffer free=bodies(63),chainBodies=bodies(64),links=bodies(64);
            for(int i=0;i<63;i++)body(free,i,i*3,10,0,1);
            for(int i=0;i<64;i++) {
                body(chainBodies,i,300+i*3,-9f/16f,.875f,1);
                links.putFloat(i*64,300+i*3).putFloat(i*64+12,.875f)
                        .putFloat(i*64+36,90).putFloat(i*64+40,1);
            }
            mixed.uploadFree(free,63);mixed.uploadChains(chainBodies,links,64);
            mixed.stepFree(.05f);mixed.stepChains(.05f);mixed.publish();
            int firstBank=mixed.bodyBuffer();
            long firstVersion=mixed.publicationVersion();mixed.publish();
            check(mixed.bodyBuffer()==firstBank && mixed.publicationVersion()==firstVersion,
                    "unchanged mixed state was copied or advanced its publication");
            check(mixed.bodyCount()==128 && mixed.freeCount()==63 && mixed.chainCount()==64,"mixed domain counts");
            ByteBuffer first=readBuffer(firstBank,128*64),history=readBuffer(mixed.historyBuffer(),128*32);
            for(int i=0;i<63;i++)check(Math.abs(first.getFloat(i*64+4)-9.9216f)<1e-5,"mixed free integration");
            for(int i=0;i<64;i++) {
                int bodyIndex=mixed.chainBodyIndex(i);
                check(Math.abs(first.getFloat(bodyIndex*64)-(300+i*3))<1.5,"mixed chain pose");
                check(Float.isFinite(history.getFloat(bodyIndex*32+16)),"mixed chain target");
            }
            ByteBuffer extraFree=bodies(1),extraChain=bodies(1),extraLink=bodies(1);
            body(extraFree,0,189,10,0,1);body(extraChain,0,492,-9f/16f,.875f,1);
            extraLink.putFloat(0,492).putFloat(12,.875f).putFloat(36,90).putFloat(40,1);
            mixed.appendFree(extraFree,bodies(1),1);mixed.appendChains(extraChain,extraLink,1);
            check(readBuffer(firstBank,128*64).getFloat(4)==first.getFloat(4),"append changed published bank");
            mixed.publish();check(mixed.bodyBuffer()!=firstBank,"mixed publication did not switch bank");
            check(mixed.bodyCount()==129 && mixed.freeBodyIndex(63)==63 && mixed.chainBodyIndex(64)==128,
                    "mixed appended indices shifted");
            ByteBuffer joined=readBuffer(mixed.bodyBuffer(),129*64);
            check(joined.getFloat(63*64)==189 && joined.getFloat(128*64)==492,"mixed append missing from published input");
            check(readBuffer(firstBank,128*64).getFloat(4)==first.getFloat(4),"new publication rewrote old bank");
            mixed.stepFree(.05f);mixed.stepChains(.05f);mixed.publish();
            joined=readBuffer(mixed.bodyBuffer(),129*64);
            check(joined.getFloat(63*64+4)<10 && Float.isFinite(joined.getFloat(128*64+44)),
                    "mixed solver lost appended body after ping-pong");
            int priorBank=mixed.bodyBuffer();ByteBuffer priorPublished=joined;
            ByteBuffer finalFree=bodies(1),finalChain=bodies(1),finalLink=bodies(1);
            body(finalFree,0,250,12,0,1);body(finalChain,0,600,-9f/16f,.875f,1);
            finalLink.putFloat(0,600).putFloat(12,.875f).putFloat(36,90).putFloat(40,1);
            mixed.replaceFree(63,finalFree,bodies(1),1);
            mixed.replaceChains(64,finalChain,finalLink,1);
            check(readBuffer(priorBank,129*64).getFloat(63*64)==priorPublished.getFloat(63*64),
                    "final checkpoint changed published mixed bank before commit");
            mixed.publish();joined=readBuffer(mixed.bodyBuffer(),129*64);
            check(mixed.bodyBuffer()!=priorBank && joined.getFloat(63*64)==250 && joined.getFloat(128*64)==600,
                    "mixed final checkpoint was not published atomically");
            ByteBuffer rebasedHistory=readBuffer(mixed.historyBuffer(),129*32);
            check(rebasedHistory.getFloat(63*32)==250 && rebasedHistory.getFloat(128*32)==600,
                    "mixed final checkpoint lost interpolation baseline");
            ByteBuffer deltaMeta=BufferUtils.createByteBuffer(64),baseline=BufferUtils.createByteBuffer(64);
            deltaMeta(deltaMeta,0,1);deltaMeta(deltaMeta,1,1);
            deltaMeta.putInt(16,63).putInt(48,128);
            try(var detector=new PackageDeltaGpu(2,PackageGpuValidation::source)) {
                detector.upload(deltaMeta,baseline,2);
                var capture=detector.capture(mixed.bodyBuffer(),mixed.bodyCount(),0,0,0,2);
                check(captureRecords(capture).remaining()==128,"mixed bodies unavailable to delta detector");
                detector.cancel(capture);
            }
            int pool=buffer(bodies(2)),counter=buffer(BufferUtils.createByteBuffer(16));
            try(var bridge=new PackagePoolGpu(2,1,PackageGpuValidation::source)) {
                ByteBuffer mesh=BufferUtils.createByteBuffer(3*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(16);
                ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
                bridge.uploadMeshes(mesh,ranges,1);
                ByteBuffer meta=BufferUtils.createByteBuffer(2*PackagePoolGpu.META_BYTES);
                meta.putLong(0,1001).putLong(8,1).putInt(16,63).putInt(20,0).putInt(24,PackagePoolGpu.NO_MESH);
                meta.putLong(80,1002).putLong(88,1).putInt(96,128).putInt(100,0).putInt(104,0)
                        .putInt(108,PackagePoolGpu.CHAIN);
                bridge.uploadMetadata(meta,2);mixed.source(bridge,0,0,0);
                bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==2,"mixed source did not admit one slot per package");
                ByteBuffer admitted=readBuffer(bridge.admissionBuffer(),64),particles=readBuffer(pool,128);
                int freeSlot=admitted.getInt(16)-1,chainSlot=admitted.getInt(48)-1;
                check(freeSlot>=0 && chainSlot>=0 && freeSlot!=chainSlot,"mixed admission shared a slot");
                check(particles.getFloat(freeSlot*64)==joined.getFloat(63*64)
                                && particles.getFloat(chainSlot*64)==joined.getFloat(128*64),
                        "mixed pool imported wrong body index");
                ByteBuffer draws=readBuffer(bridge.commandBuffer(),16);
                check(draws.getInt(4)==3,"mixed box/rig mesh instance count");
            }finally{GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
        }
    }
    static void preparedPhysics() {
        var air=snapshot((s,i)->WORLD_AIR);int n=1025;
        for(var mode:List.of("linked")) {
            try(var world=new PackageCollisionGpu(27,1);
                var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
                var bridge=new PackagePoolGpu(2,1,PackageGpuValidation::source)) {
                cubeWorld(world,air,air,0,0,0);ByteBuffer initial=bodies(n);
                for(int i=0;i<n;i++) {
                    body(initial,i,4,4,4,1);
                    if(i>0)initial.putFloat(i*64+16,20).putFloat(i*64+60,i<=n/2?PackagePhysicsGpu.RETIRED:PackagePhysicsGpu.PREPARED);
                }
                gpu.upload(initial,n);
                try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
                var state=read(gpu);
                check(Math.abs(state.getFloat(4)-3.9216f)<1e-5 && state.getFloat(60)==0,
                        "prepared crowd entered contact or candidate budget: "+mode);
                for(int i=64;i<n*64;i++)check(state.get(i)==initial.get(i),"prepared body moved: "+mode);
                ByteBuffer metadata=BufferUtils.createByteBuffer(160),vertices=BufferUtils.createByteBuffer(3*48),ranges=BufferUtils.createByteBuffer(16);
                metadata.putLong(0,1).putLong(8,1).putInt(16,0).putInt(20,0).putInt(24,-1);
                metadata.putLong(80,2).putLong(88,1).putInt(96,n-1).putInt(100,0).putInt(104,-1).putInt(108,PackagePoolGpu.HIDDEN);
                ranges.putInt(4,3).putFloat(8,1);bridge.uploadMeshes(vertices,ranges,1);bridge.uploadMetadata(metadata,2);
                int pool=buffer(bodies(2)),counter=buffer(BufferUtils.createByteBuffer(16));
                try {
                    bridge.source(gpu.stateBuffer(),gpu.chainBuffer(),gpu.historyBuffer(),n,0,0,0);
                    bridge.stage(pool,counter,3,new float[24],0,0,0);bridge.commit();
                    var admission=readBuffer(bridge.admissionBuffer(),64);
                    check(admission.getInt(16)>0 && admission.getInt(48)>0,"frozen candidate failed hidden slot admission");
                    check(readBuffer(bridge.commandBuffer(),16).getInt(4)==1,"prepared candidate became visible");
                    bridge.setHidden(1,false);
                    putBuffer(counter,BufferUtils.createByteBuffer(16));bridge.stage(pool,counter,3,new float[24],0,0,0);bridge.commit();
                    check(readBuffer(bridge.admissionBuffer(),64).getInt(48)==0,"visible metadata admitted an unactivated body");
                    gpu.activatePrepared(n-1);
                    var activated=read(gpu);
                    check(activated.getFloat((n-1)*64+60)==0,"activation did not unfreeze current ping-pong body");
                    for(int i=0;i<n*64-4;i++)check(activated.get(i)==state.get(i),"activation touched another body or checkpoint field");
                    bridge.source(gpu.stateBuffer(),gpu.chainBuffer(),gpu.historyBuffer(),n,0,0,0);
                    putBuffer(counter,BufferUtils.createByteBuffer(16));bridge.stage(pool,counter,3,new float[24],0,0,0);bridge.commit();
                    check(readBuffer(bridge.admissionBuffer(),64).getInt(48)>0
                            && readBuffer(bridge.commandBuffer(),16).getInt(4)==2,"activated body failed visible admission");
                }finally{GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
            }
        }
        try(var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            var state=bodies(2);var links=bodies(2);
            for(int i=0;i<2;i++) {
                body(state,i,i*3,-9f/16f,.875f,1);state.putFloat(i*64+60,PackagePhysicsGpu.PREPARED);
                links.putFloat(i*64,i*3).putFloat(i*64+12,.875f).putFloat(i*64+36,90).putFloat(i*64+40,1);
            }
            gpu.upload(state,2);gpu.uploadChains(links);var beforeLinks=readBuffer(gpu.chainBuffer(),128);
            gpu.stepChains(.05f);var after=read(gpu);var afterLinks=readBuffer(gpu.chainBuffer(),128);
            check(state.equals(after) && beforeLinks.equals(afterLinks),"prepared chain advanced progress or swing");
            gpu.activatePrepared(1);gpu.stepChains(.05f);after=read(gpu);
            check(after.getFloat(64)!=state.getFloat(64),"activated chain remained frozen");
            for(int i=0;i<64;i++)check(after.get(i)==state.get(i),"chain activation moved another prepared body");
        }
        try(var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(1,1,PackageGpuValidation::source)) {
            ByteBuffer paused=bodies(1);body(paused,0,4,4,4,1);paused.putFloat(60,PackagePhysicsGpu.COLLISION_FROZEN);gpu.upload(paused,1);
            ByteBuffer metadata=BufferUtils.createByteBuffer(PackagePoolGpu.META_BYTES);
            metadata.putLong(0,77).putLong(8,1).putInt(16,0).putInt(20,0).putInt(24,PackagePoolGpu.NO_MESH)
                    .putInt(28,PackagePoolGpu.ACTIVE_AUTHORITY);
            ByteBuffer ranges=BufferUtils.createByteBuffer(16);ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
            bridge.uploadMeshes(BufferUtils.createByteBuffer(3*PackagePoolGpu.VERTEX_BYTES),ranges,1);bridge.uploadMetadata(metadata,1);
            int particles=buffer(bodies(1)),counter=buffer(BufferUtils.createByteBuffer(16));
            try {
                bridge.source(gpu.stateBuffer(),gpu.chainBuffer(),gpu.historyBuffer(),1,0,0,0);
                bridge.stage(particles,counter,9,new float[24],0,0,0);bridge.commit();
                check(readBuffer(bridge.commandBuffer(),16).getInt(4)==1,
                        "locally paused body vanished from GPU rendering");
            }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
        }
    }
    static void mixedFullReservation() {
        try(var mixed=new PackageMixedPhysicsGpu(131072,131072,2,PackageGpuValidation::source)) {
            ByteBuffer free=bodies(1),chain=bodies(1),link=bodies(1);
            body(free,0,1,3,1,1);body(chain,0,2,-9f/16f,.875f,1);
            link.putFloat(0,2).putFloat(12,.875f).putFloat(36,90).putFloat(40,1);
            mixed.uploadFree(free,1);mixed.uploadChains(chain,link,1);mixed.publish();
            check(mixed.chainBodyIndex(0)==131072 && mixed.bodyCount()==131073,"full reservation high body index");
            boolean rejected=false;
            try{mixed.uploadFree(bodies(0),131072);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected && mixed.freeCount()==1,"full reservation exceeded 131072 active packages");
            ByteBuffer high=bodies(1);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,mixed.bodyBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,131072L*64,high);
            check(high.getFloat(0)==2,"high-offset chain body publication");
            ByteBuffer deltaMeta=BufferUtils.createByteBuffer(32),baseline=BufferUtils.createByteBuffer(32);
            deltaMeta(deltaMeta,0,1);deltaMeta.putInt(16,131072);
            try(var detector=new PackageDeltaGpu(1,PackageGpuValidation::source)) {
                detector.upload(deltaMeta,baseline,1);
                var capture=detector.capture(mixed.bodyBuffer(),mixed.bodyCount(),0,0,0,1);
                check(captureRecords(capture).remaining()==64,"high-offset chain body unavailable to delta detector");
                detector.cancel(capture);
            }
            int pool=buffer(bodies(1)),counter=buffer(BufferUtils.createByteBuffer(16));
            try(var bridge=new PackagePoolGpu(1,1,PackageGpuValidation::source)) {
                ByteBuffer mesh=BufferUtils.createByteBuffer(3*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(16);
                ranges.putInt(0,0).putInt(4,3).putFloat(8,1);bridge.uploadMeshes(mesh,ranges,1);
                ByteBuffer meta=BufferUtils.createByteBuffer(PackagePoolGpu.META_BYTES);
                meta.putLong(0,7001).putLong(8,1).putInt(16,131072).putInt(20,0).putInt(24,0)
                        .putInt(28,PackagePoolGpu.CHAIN);
                bridge.uploadMetadata(meta,1);mixed.source(bridge,0,0,0);
                bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==1 && readBuffer(pool,64).getFloat(0)==2,
                        "high-offset chain body unavailable to common pool");
            }finally{GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
        }
    }
    static void mixedBenchmark() throws Exception {
        List<String> rows=new ArrayList<>(),samples=new ArrayList<>();
        rows.add("packages,free,chain,run,free_gpu_p50_ms,free_gpu_p95_ms,chain_gpu_p50_ms,chain_gpu_p95_ms,publish_gpu_p50_ms,publish_gpu_p95_ms,total_gpu_p50_ms,total_gpu_p95_ms,submit_cpu_p50_ms,submit_cpu_p95_ms");
        samples.add("packages,free,chain,run,sample,free_gpu_ms,chain_gpu_ms,publish_gpu_ms,total_gpu_ms,submit_cpu_ms");
        for(int n:new int[]{10000,65536,131072}) {
            int nf=n/2,nc=n-nf;
            try(var mixed=new PackageMixedPhysicsGpu(nf,nc,2,PackageGpuValidation::source)) {
                ByteBuffer free=bodies(nf),chain=bodies(nc),links=bodies(nc);
                for(int i=0;i<nf;i++)body(free,i,(i%256)*3,10,(i/256)*3,1);
                for(int i=0;i<nc;i++) {
                    int p=i*64;float x=(i%256)*3,z=(i/256)*3;
                    body(chain,i,x,-9f/16f,z+.875f,1);
                    links.putFloat(p,x).putFloat(p+8,z).putFloat(p+12,.875f)
                            .putFloat(p+36,90).putFloat(p+40,1);
                }
                mixed.uploadFree(free,nf);mixed.uploadChains(chain,links,nc);mixed.publish();
                for(int run=1;run<=3;run++) {
                    for(int warm=0;warm<30;warm++){mixed.stepFree(.05f);mixed.stepChains(.05f);mixed.publish();}
                    GL11.glFinish();int[] query={GL15.glGenQueries(),GL15.glGenQueries(),GL15.glGenQueries()};
                    double[][] gpu=new double[4][40];double[] cpu=new double[40];
                    try {
                        for(int sample=0;sample<40;sample++) {
                            long started=System.nanoTime();
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query[0]);mixed.stepFree(.05f);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query[1]);mixed.stepChains(.05f);GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query[2]);mixed.publish();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            cpu[sample]=(System.nanoTime()-started)/1e6;
                            GL11.glFinish();
                            for(int pass=0;pass<3;pass++) {
                                gpu[pass][sample]=GL33.glGetQueryObjectui64(query[pass],GL15.GL_QUERY_RESULT)/1e6;
                                gpu[3][sample]+=gpu[pass][sample];
                            }
                            samples.add(n+","+nf+","+nc+","+run+","+sample+","+gpu[0][sample]+","+gpu[1][sample]+","+gpu[2][sample]+","+gpu[3][sample]+","+cpu[sample]);
                        }
                    }finally{for(int q:query)GL15.glDeleteQueries(q);}
                    String row=n+","+nf+","+nc+","+run+","+percentile(gpu[0],.5)+","+percentile(gpu[0],.95)
                            +","+percentile(gpu[1],.5)+","+percentile(gpu[1],.95)
                            +","+percentile(gpu[2],.5)+","+percentile(gpu[2],.95)
                            +","+percentile(gpu[3],.5)+","+percentile(gpu[3],.95)
                            +","+percentile(cpu,.5)+","+percentile(cpu,.95);
                    rows.add(row);System.out.println(row);
                    Files.write(Path.of("build/package-mixed-benchmark.csv"),rows);
                    Files.write(Path.of("build/package-mixed-benchmark-samples.csv"),samples);
                }
            }
        }
    }
    static void sweep(){
        try(var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(2);body(b,0,0,0,0,0);body(b,1,0,4,0,1);
            b.putFloat(64+20,-100);gpu.upload(b,2);gpu.step(.05f);
            ByteBuffer r=read(gpu);check(r.getFloat(68)>=.9999f,"high-speed body tunneled through static block");
            check(r.getFloat(64+20)==0,"swept collision did not remove inward velocity");
            body(b,1,0,4,0,1);b.putFloat(64+20,-100000);gpu.upload(b,2);gpu.step(.05f);
            r=read(gpu);check(r.getFloat(64+60)<0,"oversized sweep did not request fallback");
            check(r.getFloat(68)==4,"oversized sweep advanced without collision coverage");
        }
    }
    static void asymmetricSweepRegression(){
        var air=snapshot((section,i)->WORLD_AIR);
        for(var mode:List.of("linked"))try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var b=bodies(2);body(b,0,-.76f,10,0,1);body(b,1,0,10,0,1);
            for(int i=0;i<2;i++)for(int axis=0;axis<3;axis++)b.putFloat(i*64+32+axis*4,.375f);
            b.putFloat(16,31);gpu.upload(b,2);try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var r=read(gpu);check(r.getFloat(0)<r.getFloat(64)&&r.getFloat(64)-r.getFloat(0)>=.7499f,"asymmetric CCD passed through stationary body: "+mode);
            check(Math.abs(r.getFloat(16)-r.getFloat(80))<1e-3,"asymmetric CCD did not resolve both velocities");
            check(r.getFloat(60)>=0&&r.getFloat(124)>=0,"asymmetric CCD paused a valid pair");
        }
    }
    static void localBudgetRetry(){
        int n=8202;try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)){
            var data=bodies(n);for(int i=0;i<n;i++)body(data,i,4,4,4,i==n-1?1:0);gpu.upload(data,n);gpu.step(.05f);
            var r=read(gpu);check(r.getFloat((n-1)*64+60)==PackagePhysicsGpu.COLLISION_FROZEN,"candidate budget did not pause locally");
            check(r.getFloat((n-1)*64+4)==4,"budget exhaustion lost the last stable position");
            for(int i=0;i<n-1;i++)gpu.retire(i);gpu.step(.05f);r=read(gpu);
            check(r.getFloat((n-1)*64+60)>=0&&r.getFloat((n-1)*64+4)<4,"local pause did not retry after congestion cleared");
        }
    }
    static void environmentRegression(){
        var air=snapshot((section,i)->WORLD_AIR);
        // Reproduce a dense-pile correction moving the final centre far beyond a valid
        // machine sweep. Reporting that endpoint made the server reject the contact.
        var sweptMachine=snapshot((section,i)->i==(5|5<<4|4<<8)?new PackageCollisionCache.Cell(List.of(),.6f,16):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,sweptMachine,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(1);
            body(b,0,5.5f,10.75f,5.5f,1);b.putFloat(48,5.5f).putFloat(52,4.75f).putFloat(56,5.5f);
            gpu.upload(b,1);env.reset(0,91,7,3,0,1,0,5,7);
            try(var view=world.view(0,0,0)){env.step(gpu.stateBuffer(),1,view);}check(env.capture(1),"long machine sweep capture");GL11.glFinish();
            env.poll(bytes->{var payload=new byte[bytes.remaining()];bytes.get(payload);var event=com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.decode(payload);
                var sample=event.samples().getFirst();check(sample.contact()==8&&sample.y()==4,"long machine sweep contact missing");
                check(Math.abs(sample.py()-4.75f)<1e-6,"machine sweep reported corrected end pose instead of actual contact");});
        }
        for(int contact:new int[]{1,2,4}){
            var scene=snapshot((section,i)->i>>>8==0?shape(1,.6f):i>>>8==1?new PackageCollisionCache.Cell(List.of(),.6f,contact):WORLD_AIR);
            try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
                cubeWorld(world,air,scene,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(1);
                body(b,0,5.5f,2.01f,5.5f,1);gpu.upload(b,1);env.reset(0,91,7,3,0,1,0,5,7);
                int steps=contact==1?200:20;
                try(var view=world.view(0,0,0)){for(int i=0;i<steps;i++)gpu.stepWorld(.05f,view,true,4);}
                var r=read(gpu);var h=readBuffer(env.headerBuffer(),64);
                check(r.getFloat(4)<2.01f,"environment froze above contact surface");
                if(contact==1){check(h.getInt(24)==1&&h.getInt(36)==1,"water destruction event not retained exactly once");}
                else if(contact==2){
                    check(h.getInt(24)==16&&h.getInt(36)==1&&h.getFloat(48)<=.5f,"lava did not retain its terminal damage event");
                    try(var view=world.view(0,0,0)){for(int i=0;i<10;i++)gpu.stepWorld(.05f,view,true,4);}
                    check(readBuffer(env.headerBuffer(),64).getInt(24)==16,"terminal lava body advanced while awaiting destruction");
                }else{
                    check(h.getInt(24)==20&&h.getInt(28)==0,"environment history truncated unacknowledged contacts kind="+contact+" written="+h.getInt(24)+" ack="+h.getInt(28)+" fire="+h.getInt(32)+" health="+h.getFloat(48)+" state="+r.getFloat(60));
                    try(var view=world.view(0,0,0)){for(int i=0;i<10;i++)gpu.stepWorld(.05f,view,true,4);}
                    check(readBuffer(env.headerBuffer(),64).getInt(24)==20,"full environment queue overwrote history");
                    env.acknowledge(0,20,h.getInt(32),h.getFloat(48));
                    try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
                    check(readBuffer(env.headerBuffer(),64).getInt(24)==21,"ACK did not resume locally paused environment");
                }
                check(env.capture(1),"environment capture");GL11.glFinish();var events=new ArrayList<com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent>();
                env.poll(bytes->{byte[] payload=new byte[bytes.remaining()];bytes.get(payload);events.add(com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.decode(payload));});
                check(events.size()==1&&events.getFirst().identity().equals(new PackageLease.Identity(91,7)),"environment lifecycle readback mismatch");
                check(events.getFirst().samples().getFirst().contact()==contact,"environment sweep missed actual contact");
            }
        }
        // Server permissions can overturn a predicted terminal result without discarding later steps.
        var flames=snapshot((section,i)->i>>>8==0?shape(1,.6f):i>>>8==1?new PackageCollisionCache.Cell(List.of(),.6f,4):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,flames,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(1);body(b,0,5,1.5f,5,1);gpu.upload(b,1);env.reset(0,1,1,1,0,5,7);
            try(var view=world.view(0,0,0)){for(int i=0;i<10;i++)gpu.stepWorld(.05f,view,true,4);}
            env.acknowledge(0,5,96,4.4f,7);var h=readBuffer(env.headerBuffer(),64);
            check(h.getInt(28)==5&&h.getInt(32)==91&&Math.abs(h.getFloat(48)-3.65f)<1e-5,"ACK lost unconfirmed fire suffix");
            env.acknowledge(0,4,0,5,0);h=readBuffer(env.headerBuffer(),64);check(h.getInt(28)==5&&h.getInt(32)==91,"old environment ACK overwrote state");
            env.acknowledge(0,10,0,5,0);h=readBuffer(env.headerBuffer(),64);check(h.getInt(32)==0&&h.getFloat(48)==5&&h.getInt(44)==0,"server immunity was not applied");
            try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            h=readBuffer(env.headerBuffer(),64);check(h.getInt(24)==11&&h.getInt(32)==0&&h.getFloat(48)==5,"immune fire contact stopped progressing");
        }
        var machine=snapshot((section,i)->i>>>8==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,16):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,machine,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(1);body(b,0,5.5f,1.5f,5.5f,1);gpu.upload(b,1);env.reset(0,1,1,1,0,5,7);
            try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}check(env.capture(1),"machine surface capture");GL11.glFinish();
            env.poll(bytes->{check(bytes.getInt(76)==8&&bytes.getInt(68)==0,"standing package did not report its supporting machine");});
        }
        // Leaving fire still advances the burn timer; callbacks are not required to keep burning.
        var floor=snapshot((section,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,floor,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(1);body(b,0,5,1.5f,5,1);gpu.upload(b,1);env.reset(0,1,1,1,100,5,7);
            try(var view=world.view(0,0,0)){for(int i=0;i<20;i++)gpu.stepWorld(.05f,view,true,4);}
            var h=readBuffer(env.headerBuffer(),64);check(h.getInt(32)==80&&Math.abs(h.getFloat(48)-4.85f)<1e-5,"leaving fire stopped burn timer or doubled periodic damage");
            env.acknowledge(0,20,80,4.85f);env.reset(0,1,2,2,0,5,7);
            h=readBuffer(env.headerBuffer(),64);check(h.getLong(8)==2&&h.getInt(24)==0&&h.getInt(28)==0&&h.getInt(32)==0,"reused environment retained a previous lifetime");
        }
        // More terminal bodies than the sparse queue can hold are retried without losing inventory events.
        var water=snapshot((section,i)->i>>>8==0?shape(1,.6f):i>>>8==1?new PackageCollisionCache.Cell(List.of(),.6f,1):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(32,2,PackageGpuValidation::source)){
            cubeWorld(world,air,water,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);var b=bodies(32);
            for(int i=0;i<32;i++){body(b,i,1.5f+i%8*1.8f,1.5f,1.5f+i/8*1.8f,1);env.reset(i,i+1,1,1,0,5,7);}gpu.upload(b,32);
            try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var identities=new HashSet<Long>();
            for(int round=0;round<4;round++){check(env.capture(32),"environment overflow retry capture");GL11.glFinish();env.poll(bytes->{int body=bytes.getInt(52);long id=bytes.getLong(0);identities.add(id);env.acknowledge(body,Integer.toUnsignedLong(bytes.getInt(24)),0,5);});}
            check(identities.size()==32,"bounded environment queue dropped terminal bodies");
        }
    }
    static void dynamicPackageSweep(){
        var air=snapshot((s,i)->WORLD_AIR);
        for(var mode:List.of("linked")) {
            try(var world=new PackageCollisionGpu(27,1);
                var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
                cubeWorld(world,air,air,0,0,0);
                ByteBuffer b=bodies(2);body(b,0,-1.25f,10,0,1);body(b,1,1.25f,10,0,1);
                b.putFloat(16,25).putFloat(64+16,-25);gpu.upload(b,2);
                try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,false,4);}
                ByteBuffer r=read(gpu);float left=r.getFloat(0),right=r.getFloat(64);
                check(left<right && right-left>=.99f,"fast dynamic packages tunneled: "+mode+" / "+left+" / "+right);
                check(Math.abs(r.getFloat(16))<1e-3&&Math.abs(r.getFloat(64+16))<1e-3,
                        "relative normal velocity survived swept package contact: "+mode);
                check(r.getFloat(60)>=0&&r.getFloat(64+60)>=0,"bounded package sweep requested fallback: "+mode);
            }
        }
        try(var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(2);body(b,0,-4,10,0,1);body(b,1,4,10,0,1);
            b.putFloat(16,100).putFloat(64+16,-100);gpu.upload(b,2);gpu.step(.05f);
            ByteBuffer r=read(gpu);
            check(r.getFloat(60)>=0&&r.getFloat(124)>=0,"bounded head-on sweep paused");
            check(Math.abs(r.getFloat(0)+.5f)<1e-4&&Math.abs(r.getFloat(64)-.5f)<1e-4&&Math.abs(r.getFloat(16))<1e-4&&Math.abs(r.getFloat(80))<1e-4,"bounded head-on CCD response");
            body(b,0,-4,10,0,1);body(b,1,4,10,0,1);b.putFloat(16,1000).putFloat(80,-1000);gpu.upload(b,2);gpu.step(.05f);r=read(gpu);
            check(r.getFloat(60)<0&&r.getFloat(124)<0,"oversized pair sweeps did not pause");
            check(r.getFloat(0)==-4&&r.getFloat(64)==4&&r.getFloat(16)==1000&&r.getFloat(80)==-1000,"oversized sweep did not restore stable state");
        }
        crossingPackageSweeps();
    }
    static void environmentThroughput(){
        var air=snapshot((s,i)->WORLD_AIR);
        var floor=snapshot((s,i)->i>>>8==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,16):WORLD_AIR);
        for(int rate:new int[]{20,200})try(var world=new PackageCollisionGpu(75,1);var gpu=new PackagePhysicsGpu(512,2,PackageGpuValidation::source)){
            for(int x=-1;x<=3;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=3;z++)world.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
            uploadWorld(world);var b=bodies(512);var env=gpu.enableEnvironment(PackageGpuValidation::source);env.tickRate(rate);
            for(int i=0;i<512;i++){body(b,i,.6f+(i%32)*1.01f,1.5f,.6f+(i/32)*1.01f,1);env.reset(i,i+1,1,1,0,5,7);}gpu.upload(b,512);
            var previous=new ArrayList<com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent>();
            try(var view=world.view(0,0,0)){for(int frame=0;frame<6;frame++){
                for(int step=0;step<rate/5;step++)gpu.stepWorld(.05f,view,true,4);
                var state=read(gpu);for(int i=0;i<512;i++)check(state.getFloat(i*64+60)>=0,"healthy machine journal paused at 5 FPS / rate="+rate);
                check(env.capture(512),"throughput capture");GL11.glFinish();var events=new ArrayList<com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent>();
                env.poll(bytes->{byte[] payload=new byte[bytes.remaining()];bytes.get(payload);events.add(com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.decode(payload));});
                check(events.size()==512,"environment capture lost the large active burst");
                for(var event:events){
                    long begin=frame*(long)rate/5,end=(frame+1L)*rate/5,covered=0;var last=event.samples().getLast();
                    check(last.step()==end,"environment run lost game steps");
                    for(var sample:event.samples())covered+=Math.max(0,Math.min(end,sample.step())-Math.max(begin,sample.step()-sample.ticks()));
                    check(covered==rate/5,"compact history lost elapsed ticks");
                    // The initial support correction can split the first run at a slightly different contact point.
                    check(last.ticks()>=rate/5-1,"stationary contact failed to compact");
                }
                // A whole-frame delayed ACK must not erase the newly captured suffix.
                for(var event:previous)env.acknowledge((int)event.identity().id()-1,event.through(),0,5,7);
                previous=events;
            }}
        }
        // Fire state after a delayed ACK must replay every tick in its compact suffix.
        var flames=snapshot((s,i)->i>>>8==0?shape(1,.6f):i>>>8==1?new PackageCollisionCache.Cell(List.of(),.6f,4):WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,flames,0,0,0);var env=gpu.enableEnvironment(PackageGpuValidation::source);env.tickRate(200);var b=bodies(1);body(b,0,5,1.5f,5,1);gpu.upload(b,1);env.reset(0,1,1,1,0,5,7);
            long[] through={0};
            try(var view=world.view(0,0,0)){
                for(int i=0;i<5;i++)gpu.stepWorld(.05f,view,true,4);env.capture(1);GL11.glFinish();
                env.poll(bytes->{byte[] payload=new byte[bytes.remaining()];bytes.get(payload);through[0]=com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.decode(payload).through();});
                for(int i=0;i<5;i++)gpu.stepWorld(.05f,view,true,4);env.acknowledge(0,through[0],96,4.4f,7);
            }
            var h=readBuffer(env.headerBuffer(),64);check(h.getInt(24)==through[0]+1&&h.getInt(28)==through[0]&&h.getInt(32)==91&&Math.abs(h.getFloat(48)-3.65f)<1e-5,"compact ACK lost the five-tick fire suffix / written="+h.getInt(24)+" ack="+h.getInt(28)+" fire="+h.getInt(32)+" health="+h.getFloat(48));
        }
    }
    static void crossingPackageSweeps(){
        var air=snapshot((s,i)->WORLD_AIR);
        for(int axis:new int[]{1,2})for(boolean support:new boolean[]{false,true})for(int sign:new int[]{-1,1})for(float speed:new float[]{200,240})try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)){
            float start=-sign*speed*.025f;
            cubeWorld(world,air,air,0,0,0);var b=bodies(2);body(b,0,-sign*5,10,0,1);body(b,1,0,axis==1?10+start:10,axis==2?start:0,1);
            b.putFloat(16,sign*200).putFloat(64+16+axis*4,sign*speed);gpu.upload(b,2);
            try(var view=world.view(0,0,0)){gpu.stepWorld(.05f,view,support,4);}var r=read(gpu);
            check(r.getFloat(60)>=0&&r.getFloat(124)>=0,"crossing sweep paused / axis="+axis+" support="+support+" sign="+sign+" speed="+speed+" status="+r.getFloat(60)+","+r.getFloat(124));
            check(Math.abs(r.getFloat(16)-sign*196)>1||Math.abs(r.getFloat(64+16+axis*4)-sign*speed*.98f)>2,"crossing trajectories passed without CCD response axis="+axis);
        }
    }
    static void dynamicPackageBenchmark() throws Exception {
        int n=131072;var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,solver,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,penetrating_pairs,all_pair_overlap_max,terrain_penetration_max,fallbacks,moving,quality_pass");
        samples.add("count,scenario,solver,run,sample,gpu_ms,cpu_submit_ms");
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            movingWorld(atlas,true);
            for(int run=1;run<=3;run++) {
                gpu.upload(stackBodies(n,64,64,true),n);
                try(var view=atlas.view(0,0,0)) {
                    for(int warm=0;warm<50;warm++){probe.drive(gpu,warm);gpu.stepWorld(.05f,view,true,4);}
                    GL11.glFinish();int[] queries=new int[40];double[] times=new double[40],cpu=new double[40];
                    try {
                        for(int i=0;i<queries.length;i++) {
                            queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();
                            probe.drive(gpu,50+i);gpu.stepWorld(.05f,view,true,4);
                            cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        }
                        for(int i=0;i<queries.length;i++) {
                            times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;
                            samples.add(n+",staggered_driven,support4_linked,"+run+","+i+","+times[i]+","+cpu[i]);
                        }
                    } finally {for(int query:queries)if(query!=0)GL15.glDeleteQueries(query);}
                    var stats=probe.inspect(gpu,1.5f);float overlap=stats.getFloat(4),ground=stats.getFloat(24);
                    int fallbacks=stats.getInt(12),moving=stats.getInt(20),valid=stats.getInt(28),nonfinite=stats.getInt(16);
                    boolean quality=valid==n&&fallbacks==0&&nonfinite==0&&overlap<.002&&ground<1e-4&&moving>n*.99;
                    check(quality,"dynamic CCD benchmark quality gate failed: fallback="+fallbacks+" overlap="+overlap+" ground="+ground);
                    var row=n+",staggered_driven,support4_linked,"+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                            +","+stats.getInt(0)+","+overlap+","+ground+","+fallbacks+","+moving+","+(quality?1:0);
                    rows.add(row);System.out.println(row);
                    Files.write(Path.of("build/package-dynamic-ccd-benchmark.csv"),rows);
                    Files.write(Path.of("build/package-dynamic-ccd-benchmark-samples.csv"),samples);
                }
            }
        }
    }
    static final PackageCollisionCache.Cell WORLD_AIR=new PackageCollisionCache.Cell(List.of(),.6f,0);
    static PackageCollisionCache.Cell shape(float height,float friction) {
        return new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,height,1)),friction,0);
    }
    static PackageCollisionCache.Snapshot snapshot(PackageCollisionCache.Source source) {
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);
        var section=new PackageCollisionCache.Section(0,0,0);cache.request(section);
        for(int i=0;i<3 && cache.snapshot(section)==null;i++)cache.tick(source,1);
        check(cache.snapshot(section)!=null,"immutable world fixture did not finish");return cache.snapshot(section);
    }
    static void uploadWorld(PackageCollisionGpu atlas) {
        for(int i=0;i<1000 && atlas.stats().pending()!=0;i++)atlas.pump(262144,Long.MAX_VALUE);
        check(atlas.stats().pending()==0,"world upload did not complete");
    }
    static void cubeWorld(PackageCollisionGpu atlas,PackageCollisionCache.Snapshot air,PackageCollisionCache.Snapshot floor,int ox,int oy,int oz) {
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)
            check(atlas.offer(new PackageCollisionCache.Section(ox+x,oy+y,oz+z),y==0?floor:air),"world section admission");
        uploadWorld(atlas);
    }
    static void worldUploadVersions() {
        var air=snapshot((s,i)->WORLD_AIR);var section=new PackageCollisionCache.Section(0,0,0);
        try(var atlas=new PackageCollisionGpu(1,1,()->0L)) {
            check(atlas.offer(section,air),"world offer rejected");atlas.pump(1024,1);
            check(!atlas.covered(section,air.revision()),"partial GPU upload published coverage");
            check(atlas.stats().uploadedBytes()==1024,"upload exceeded byte budget");
            uploadWorld(atlas);check(atlas.covered(section,air.revision()),"completed coverage missing");
            atlas.invalidate(section,air.revision()+1);
            check(atlas.covered(section,air.revision())&&!atlas.covered(section,air.revision()+1),"geometry revision changed before replacement upload");
            check(!atlas.offer(section,air),"obsolete worker reintroduced coverage");
            atlas.forget(section);check(atlas.offer(section,air),"retired identity prevented reuse");uploadWorld(atlas);
            var replacement=new PackageCollisionCache.Section(1,0,0);
            check(atlas.offer(replacement,air),"full atlas failed to recycle its least-recent section");
            check(!atlas.covered(section,air.revision()),"LRU eviction left old section covered");
            uploadWorld(atlas);check(atlas.covered(replacement,air.revision()),"replacement section was not uploaded");
            check(atlas.stats().evictions()==1,"atlas LRU eviction was not counted");
            atlas.clear();check(!atlas.covered(section,air.revision()),"clear kept coverage");
            boolean invalid=false;try(var view=atlas.view(Integer.MAX_VALUE,0,0)){}catch(IllegalArgumentException expected){invalid=true;}
            check(invalid,"invalid world origin accepted");
        }
        var many=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1),
                new PackageCollisionCache.Box(0,0,0,1,.5f,1)),.6f,0):WORLD_AIR);
        var overhang=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(-1.01f,0,0,1,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(1,1)) {
            check(!atlas.offer(section,many),"shape capacity silently truncated");
            check(!atlas.offer(section,overhang),"unbounded overhang admitted");
            check(!atlas.covered(section,many.revision()),"rejected world falsely covered");
        }
        var ticks=new java.util.concurrent.atomic.AtomicLong();
        try(var atlas=new PackageCollisionGpu(1,1,()->ticks.getAndAdd(100))) {
            atlas.offer(section,air);atlas.pump(65536,50);
            check(atlas.stats().uploadedBytes()==0 && !atlas.covered(section,air.revision()),"time budget ignored");
        }
    }
    static void worldAtlasLru() {
        var air=snapshot((s,i)->WORLD_AIR);
        var a=new PackageCollisionCache.Section(0,0,0);var b=new PackageCollisionCache.Section(1,0,0);
        var c=new PackageCollisionCache.Section(2,0,0);
        try(var atlas=new PackageCollisionGpu(2,1)) {
            check(atlas.offer(a,air)&&atlas.offer(b,air),"initial LRU atlas offers failed");uploadWorld(atlas);
            check(atlas.covered(a,air.revision()),"active A section missing");
            // Open a table that references both slots, then let the newer lookup keep A hot.
            try(var oldView=atlas.view(0,0,0)) { }
            check(atlas.covered(a,air.revision()),"hot section lookup failed");
            check(atlas.offer(c,air),"full GPU atlas did not evict its least-recent section");
            check(atlas.covered(a,air.revision()),"recently used section was evicted");
            check(!atlas.covered(b,air.revision()),"least-recent section remained addressable");
            check(atlas.stats().evictions()==1&&atlas.stats().retired()>0,
                    "evicted slot was not retained behind its table fence");
            uploadWorld(atlas);check(atlas.covered(c,air.revision()),"LRU replacement upload missing");
        }
        try(var atlas=new PackageCollisionGpu(2,1)) {
            check(atlas.offer(a,air)&&atlas.offer(b,air),"protected-set atlas setup failed");uploadWorld(atlas);
            long version;try(var view=atlas.view(0,0,0)){version=view.version();}
            check(atlas.beginPackageUsage(version),"current table rejected its own package usage set");
            check(a.equals(atlas.touchPackageUsage(version,0)),"active row A failed to resolve");
            check(b.equals(atlas.touchPackageUsage(version,5)),"active row B failed to resolve");
            check(!atlas.beginPackageUsage(version-1),"stale usage feedback replaced the current active set");
            check(!atlas.offer(c,air),"new geometry evicted a section occupied by active packages");
            check(atlas.stats().capacityRejections()==1&&atlas.covered(a,air.revision())&&atlas.covered(b,air.revision()),
                    "full active atlas did not preserve current collision sections");
            atlas.clearPackageUsage();check(atlas.covered(a,air.revision()),"usage release lost resident section");
            check(atlas.offer(c,air),"released package section set did not unblock LRU recycling");
            uploadWorld(atlas);
            check(atlas.covered(a,air.revision())&&!atlas.covered(b,air.revision())&&atlas.covered(c,air.revision()),
                    "released LRU set recycled the wrong section");
        }
    }
    static void worldShapes() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float height:new float[]{.5f,1f,1.5f}) {
            var floor=snapshot((s,i)->i>>>8==0?shape(height,.6f):WORLD_AIR);
            for(int[] origin:new int[][]{{0,0,0},{-100,-2000000,100},{100,2000000,-100}}) {
                try(var atlas=new PackageCollisionGpu(27,2);var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
                    cubeWorld(atlas,air,floor,origin[0],origin[1],origin[2]);
                    ByteBuffer b=bodies(65);
                    for(int i=0;i<65;i++)body(b,i,2+i%7*4,7,2+i/7*3,1);
                    gpu.upload(b,65);
                    try(var view=atlas.view(origin[0],origin[1],origin[2])) {
                        for(int step=0;step<80;step++)gpu.stepWorld(.05f,view);
                    }
                    var result=read(gpu);
                    for(int i=0;i<65;i++) {
                        check(Math.abs(result.getFloat(i*64+4)-(height+.5f))<1e-4,"world support / origin / tail "+i);
                        check(result.getFloat(i*64+28)==1 && result.getFloat(i*64+60)>=0,"world grounded or fallback "+i);
                        check(result.getFloat(i*64)==b.getFloat(i*64) && result.getFloat(i*64+8)==b.getFloat(i*64+8),"unrelated world bodies moved");
                    }
                }
            }
        }
        // A two-box stair and an offset slab cannot be represented as a full block.
        var step=snapshot((s,i)->i==0?new PackageCollisionCache.Cell(List.of(
                new PackageCollisionCache.Box(0,0,0,1,.5f,1),new PackageCollisionCache.Box(.5f,.5f,0,1,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,4);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,step,0,0,0);ByteBuffer b=bodies(2);
            body(b,0,.25f,4,.5f,1);body(b,1,.75f,4,.5f,1);
            for(int i=0;i<2;i++)for(int axis=0;axis<3;axis++)b.putFloat(i*64+32+axis*4,.1f);
            gpu.upload(b,2);try(var view=atlas.view(0,0,0)){for(int i=0;i<80;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);check(Math.abs(r.getFloat(4)-.6f)<1e-4,"stair lower tread");check(Math.abs(r.getFloat(68)-1.1f)<1e-4,"stair upper tread");
        }
    }
    static void worldSweepsAndMaterials() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float material:new float[]{.6f,.98f}) {
            var floor=snapshot((s,i)->i>>>8==0?shape(1,material):WORLD_AIR);
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(1);body(b,0,5,6,5,1);b.putFloat(20,-100);
                gpu.upload(b,1);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
                var r=read(gpu);check(Math.abs(r.getFloat(4)-1.5f)<1e-4,"world high-speed sweep tunneled");check(r.getFloat(20)==0,"world inward velocity remained");
                body(b,0,5,1.5f,5,1);b.putFloat(16,10).putFloat(20,0);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
                r=read(gpu);check(Math.abs(r.getFloat(16)-9.8f*material)<1e-4,"support material friction mismatch");
                body(b,0,5,6,5,1);b.putFloat(16,0).putFloat(20,-100000);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);gpu.stepWorld(.05f,view);}
                r=read(gpu);check(r.getFloat(4)==6 && r.getFloat(60)<0,"oversized world sweep advanced or resumed");
            }
        }
        // Negative local blocks and a guard cell with a shape extending beyond its owner.
        var overhang=snapshot((s,i)->i==15?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,2,1,1)),.6f,0):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);atlas.forget(new PackageCollisionCache.Section(-1,0,0));
            atlas.offer(new PackageCollisionCache.Section(-1,0,0),overhang);uploadWorld(atlas);
            ByteBuffer b=bodies(1);body(b,0,.25f,4,.5f,1);for(int axis=0;axis<3;axis++)b.putFloat(32+axis*4,.1f);
            gpu.upload(b,1);try(var view=atlas.view(0,0,0)){for(int i=0;i<80;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);check(Math.abs(r.getFloat(4)-1.1f)<1e-4 && r.getFloat(60)>=0,"negative-section overhang missed");
        }
    }
    static void historicalGeometryIsolation(){
        var air=snapshot((section,i)->WORLD_AIR);var sourceCache=new PackageCollisionCache(Runnable::run,1,()->0L);var key=new PackageCollisionCache.Section(0,0,0);
        sourceCache.request(key);for(int i=0;i<3;i++)sourceCache.tick((section,index)->WORLD_AIR,1);sourceCache.invalidate(key);for(int i=0;i<3;i++)sourceCache.tick((section,index)->WORLD_AIR,1);var newer=sourceCache.snapshot(key);
        try(var atlas=new PackageCollisionGpu(64,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source)){
            var versions=new HashMap<PackageCollisionCache.Section,Long>();
            for(int x=-1;x<=3;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){var section=new PackageCollisionCache.Section(x,y,z);atlas.offer(section,air);versions.put(section,air.revision());}
            uploadWorld(atlas);var changed=new PackageCollisionCache.Section(0,0,0);atlas.offer(changed,newer);uploadWorld(atlas);
            var b=bodies(2);body(b,0,5,6,5,1);body(b,1,37,6,5,1);gpu.upload(b,2);
            try(var view=atlas.historicalView(0,0,0,versions)){gpu.stepWorld(.05f,view);}
            var r=read(gpu);check(r.getFloat(4)==6&&r.getFloat(60)==PackagePhysicsGpu.COLLISION_FROZEN,"unavailable historical geometry advanced its body");
            check(r.getFloat(68)<6&&r.getFloat(124)>=0,"unrelated body paused for another section's historical geometry");
            versions.put(changed,newer.revision());try(var view=atlas.historicalView(0,0,0,versions)){gpu.stepWorld(.05f,view);}
            r=read(gpu);check(r.getFloat(4)<6&&r.getFloat(60)>=0,"body did not retry once its exact geometry version became available");
        }
    }
    static void machineEnvironmentConfirmations() {
        var air=snapshot((s,i)->WORLD_AIR);int sourceIndex=5|5<<4|4<<8;
        var machine=snapshot((s,i)->i==sourceIndex?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,16):WORLD_AIR);
        var owner=new UUID(7,9);
        for(int sign:new int[]{1,-1})for(var side:List.of(net.minecraft.core.Direction.DOWN,net.minecraft.core.Direction.EAST)) {
            int ox=sign*320,oy=sign*80,oz=-sign*144;
            var sourceBounds=new net.minecraft.world.phys.AABB(ox+5,oy+4,oz+5,ox+6,oy+5,oz+6);
            var position=new net.minecraft.world.phys.Vec3(ox+5.5,oy+(side==net.minecraft.core.Direction.DOWN?3.75:4.875),oz+5.5);
            var motion=new net.minecraft.world.phys.Vec3(side.getStepX()*.125,side==net.minecraft.core.Direction.DOWN?-.25:.125,0);
            var pose=PackageOutputPose.clearSource(PackageOutputPose.dropped(position,motion,0),.75f,.5f,sourceBounds,side);
            var target=new ChannelTarget(0);target.state=new PackageAuthorityRegion.Snapshot(pose,0);var region=PackageRegion.at(pose);
            var server=new PackageAuthorityRegion(region,owner,70,1,0);var offered=server.offer(target,0);
            var checkpoint=server.prepared(owner,70,offered.index(),target.identity,offered.leaseEpoch(),offered.revision(),0);
            check(server.finalReady(owner,70,checkpoint.index(),target.identity,checkpoint.leaseEpoch(),checkpoint.revision(),0),"machine final-ready failed");
            var model=ResourceLocation.parse("create:cardboard_package_12x12");var dimension=ResourceLocation.parse("minecraft:overworld");
            var offer=new ClientboundPackagePacket(ClientboundPackagePacket.OFFER,dimension,region,70,1,0,offered,42,new UUID(1,1),model,.75f,.5f);
            var finalPacket=new ClientboundPackagePacket(ClientboundPackagePacket.FINAL_BASELINE,dimension,region,70,1,0,checkpoint,42,new UUID(1,1),model,.75f,.5f);
            try(var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source);var detector=new PackageDeltaGpu(2,PackageGpuValidation::source,true)) {
                cubeWorld(world,air,machine,ox/16,oy/16,oz/16);var env=gpu.enableEnvironment(PackageGpuValidation::source);
                var body=bodies(1);var meta=BufferUtils.createByteBuffer(32);var baseline=BufferUtils.createByteBuffer(32);
                PackageFreeUpload.prepared(offer,finalPacket,new PackageModelCache.Style(0,-1),0,0,ox,oy,oz,body,BufferUtils.createByteBuffer(80),meta,baseline);
                gpu.upload(body,1);gpu.activatePrepared(0);meta.putInt(24,1);detector.upload(meta,baseline,1);
                var other=bodies(1);body(other,0,12,12,12,1);gpu.append(other,bodies(1),1,false);
                env.reset(0,target.identity.id(),target.identity.generation(),checkpoint.leaseEpoch(),checkpoint.index(),checkpoint.revision(),0,5,7);
                long through=0;int contacts=0;float otherY=12;
                for(int step=1;step<=10;step++) {
                    try(var view=world.view(ox/16,oy/16,oz/16)){gpu.stepWorldMoving(view,4,List.of());}
                    var state=read(gpu);check(state.getFloat(60)>=0&&state.getFloat(124)>=0,"machine contact paused a valid body");
                    check(state.getFloat(68)<otherY,"machine event interrupted an unrelated falling body");otherY=state.getFloat(68);
                    check(env.capture(2),"machine event capture failed");GL11.glFinish();var events=new ArrayList<com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent>();
                    env.poll(raw->events.add(com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.decode(
                            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentEvent.regionPayload(raw,ox,oy,oz,region))));
                    for(var event:events) {
                        check(event.identity().equals(target.identity)&&event.lease()==checkpoint.leaseEpoch()&&event.revision()==checkpoint.revision(),"machine environment lost exact lease");
                        for(var sample:event.samples())if(sample.step()>through){
                            check(sample.contact()==8&&sample.block(region).equals(new net.minecraft.core.BlockPos(ox+5,oy+4,oz+5)),"machine environment block origin mismatch");
                            contacts++;
                        }
                        through=event.through();if(step%2==0||step==10)env.acknowledge(0,through,0,5,7);
                    }
                    var capture=detector.capture(gpu.stateBuffer(),2,(float)(ox-region.originX()),(float)(oy-region.originY()),(float)(oz-region.originZ()),2);
                    var raw=captureRecords(capture);check(raw.remaining()==64&&raw.getInt(28)==0,"machine motion generated a terminal delta");
                    var q=new PackageDeltaCodec.Quantized(raw.getInt(32),raw.getInt(36),raw.getInt(40),(short)raw.getInt(48),(short)raw.getInt(52),(short)raw.getInt(56),(short)raw.getInt(60),raw.getInt(44));
                    check(server.deltaStepped(owner,70,1,step,step,List.of(new PackageDeltaCodec.Entry(raw.getInt(20),raw.getInt(24),q)),4,1,step)==PackageAuthorityRegion.Result.ACCEPTED,"server rejected machine exit delta");
                    check(Math.abs(target.state.pose().y()-(oy+state.getFloat(4)-.25))<=1./4096,"machine confirmation rewound");
                    acknowledge(detector,capture,raw);detector.finish(capture);
                }
                check(contacts>0&&target.releases==0,"machine fixture failed to exercise outgoing contact/retained authority");
            }
        }
    }
    static void machineOutputProgress() {
        var air=snapshot((s,i)->WORLD_AIR);
        int sourceIndex=5|5<<4|4<<8;
        var chute=snapshot((s,i)->i==sourceIndex?new PackageCollisionCache.Cell(List.of(
                new PackageCollisionCache.Box(.0625f,.5f,.0625f,.9375f,1,.9375f),
                new PackageCollisionCache.Box(.125f,0,.125f,.875f,.5f,.875f)),.6f,16):WORLD_AIR);
        var machine=snapshot((s,i)->i==sourceIndex?new PackageCollisionCache.Cell(List.of(
                new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,16):WORLD_AIR);
        var sourceBounds=new net.minecraft.world.phys.AABB(5,4,5,6,5,6);
        for(var side:net.minecraft.core.Direction.values())for(float height:new float[]{.5f,.625f,.75f,1}) {
            float width=.75f;
            var tickVelocity=new net.minecraft.world.phys.Vec3(side.getStepX()*.125,side==net.minecraft.core.Direction.DOWN?-.25:
                    side==net.minecraft.core.Direction.UP?.25:.125,side.getStepZ()*.125);
            var itemPosition=new net.minecraft.world.phys.Vec3(5.5+side.getStepX()*.501,
                    side==net.minecraft.core.Direction.DOWN?3.75:side==net.minecraft.core.Direction.UP?5:4.875,5.5+side.getStepZ()*.501);
            var pose=PackageOutputPose.clearSource(PackageOutputPose.dropped(itemPosition,tickVelocity,27),width,height,sourceBounds,side);
            try(var atlas=new PackageCollisionGpu(27,2);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,side==net.minecraft.core.Direction.DOWN?chute:machine,0,0,0);
                var base=new PackageAuthorityRegion.Baseline(0,new PackageLease.Identity(1,1),1,1,new PackageAuthorityRegion.Snapshot(pose,0));
                var packet=new ClientboundPackagePacket(ClientboundPackagePacket.OFFER,ResourceLocation.parse("minecraft:overworld"),PackageRegion.at(pose),1,1,0,
                        base,42,new UUID(1,1),ResourceLocation.parse("create:cardboard_package_12x12"),width,height);
                var b=bodies(1);PackageFreeUpload.prepared(packet,packet,new PackageModelCache.Style(0,1),0,0xf000f0,b,
                        BufferUtils.createByteBuffer(PackagePoolGpu.META_BYTES),BufferUtils.createByteBuffer(32),BufferUtils.createByteBuffer(32));
                gpu.upload(b,1);gpu.activatePrepared(0);
                var admitted=read(gpu);
                check(admitted.getFloat(16)==pose.vx()&&admitted.getFloat(20)==pose.vy()&&admitted.getFloat(24)==pose.vz(),"machine impulse lost in prepared/ACTIVE upload");
                int axis=side.getAxis()==net.minecraft.core.Direction.Axis.X?0:side.getAxis()==net.minecraft.core.Direction.Axis.Y?1:2;
                float previous=admitted.getFloat(axis*4),direction=side.getAxisDirection()==net.minecraft.core.Direction.AxisDirection.POSITIVE?1:-1;
                int steps=side==net.minecraft.core.Direction.UP?3:10;
                for(int step=0;step<steps;step++) {
                    try(var view=atlas.view(0,0,0)){gpu.stepWorldMoving(view,PackagePhysicsGpu.ITERATIONS,List.of());}
                    var result=read(gpu);float next=result.getFloat(axis*4);
                    check(result.getFloat(60)>=0,"machine output collision retry loop: "+side+" height="+height);
                    check(direction*(next-previous)>0,"machine output stopped or rewound: "+side+" height="+height+" step="+step);
                    if(step==0)check(Math.abs(result.getFloat(16)-pose.vx()*.98f)<1e-5&&Math.abs(result.getFloat(24)-pose.vz()*.98f)<1e-5,"source contact erased belt output impulse");
                    previous=next;
                }
            }
        }
    }
    static void worldFrictionOncePerStep() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float material:new float[]{.6f,.98f})for(boolean projection:new boolean[]{false,true})for(int iterations:new int[]{1,4,8,16}) {
            var floor=snapshot((s,i)->i>>>8==0?shape(1,material):WORLD_AIR);
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,floor,0,0,0);var b=bodies(1);body(b,0,5,1.5f,5,1);b.putFloat(16,3.75f);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,projection,iterations);}
                var result=read(gpu);
                check(Math.abs(result.getFloat(16)-3.75f*.98f*material)<1e-5,"material friction consumed extra steps: projection="+projection+" iterations="+iterations);
                check(result.getFloat(28)==1&&result.getFloat(60)>=0,"friction correction lost valid floor support");
            }
        }
    }
    static void sectionSeamProgress() {
        var air=snapshot((s,i)->WORLD_AIR);
        var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int axis:new int[]{1,0,2})for(int direction:new int[]{-1,1}) {
            try(var atlas=new PackageCollisionGpu(125,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)
                    atlas.offer(new PackageCollisionCache.Section(x,y,z),axis==1?air:y==0?floor:air);
                uploadWorld(atlas);var b=bodies(1);
                float start=direction>0?15.25f:.75f;
                body(b,0,axis==0?start:8,axis==1?start:1.5f,axis==2?start:8,1);
                b.putFloat(16+axis*4,direction*16);gpu.upload(b,1);
                float last=start;
                for(int step=0;step<(axis==1&&direction>0?6:12);step++) {
                    try(var view=atlas.view(0,0,0)){gpu.stepWorldMoving(view,PackagePhysicsGpu.ITERATIONS,List.of());}
                    var r=read(gpu);float next=r.getFloat(axis*4);
                    check(r.getFloat(60)>=0,"covered section seam froze axis="+axis+" direction="+direction+" step="+step);
                    check(direction*(next-last)>0,"covered section seam stopped/reversed axis="+axis+" direction="+direction+" step="+step+" position="+next);
                    last=next;
                }
                check(direction>0?last>16:last<0,"body failed to cross section seam axis="+axis+" direction="+direction+" position="+last);
            }
        }
    }
    static void sectionDemandAndPause() {
        var air=snapshot((s,i)->WORLD_AIR);var lower=new PackageCollisionCache.Section(0,-1,0);
        try(var atlas=new PackageCollisionGpu(3,1);var physics=new PackagePhysicsGpu(1,2,PackageGpuValidation::source);
            var prefetch=new PackageWorldPrefetchGpu(73,PackageGpuValidation::source);var detector=new PackageDeltaGpu(1,PackageGpuValidation::source)) {
            atlas.offer(new PackageCollisionCache.Section(0,0,0),air);uploadWorld(atlas);
            var b=bodies(1);body(b,0,8,1.25f,8,1);b.putFloat(20,-30);physics.upload(b,1);
            try(var world=atlas.view(0,0,0)){
                check(prefetch.capture(physics.stateBuffer(),1,.5f,world),"falling seam prefetch submission");
                physics.stepWorld(.05f,world);
            }
            GL11.glFinish();var requested=new java.util.HashSet<PackageCollisionCache.Section>();prefetch.poll(requested::add);
            check(requested.equals(java.util.Set.of(lower)),"far look-ahead starved the section below the body: "+requested);
            var paused=read(physics);check(paused.getFloat(4)==1.25f&&paused.getFloat(60)==PackagePhysicsGpu.COLLISION_FROZEN,"missing section did not preserve stable pose");
            var meta=BufferUtils.createByteBuffer(32);deltaMeta(meta,0,1);detector.upload(meta,BufferUtils.createByteBuffer(32),1);
            var capture=detector.capture(physics.stateBuffer(),1,0,0,0,1);var raw=captureRecords(capture);
            check(raw.remaining()==64&&raw.getInt(28)==0&&raw.getInt(36)==3072,"paused section pose was omitted from confirmation");
            check(raw.getInt(48)==0&&raw.getInt(52)==0&&raw.getInt(56)==0,"paused confirmation extrapolated through missing geometry");detector.cancel(capture);
            atlas.offer(lower,air);uploadWorld(atlas);
            try(var world=atlas.view(0,0,0)){physics.stepWorld(.05f,world);}
            var resumed=read(physics);check(resumed.getFloat(4)<0&&resumed.getFloat(60)>=0&&resumed.getFloat(20)< -30,"section coverage did not resume the retained falling motion");
        }
    }
    static void fallingSectionConfirmations() {
        var air=snapshot((s,i)->WORLD_AIR);var owner=new UUID(7,9);var target=new ChannelTarget(0);
        target.state=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(8,39.5,8,0,0,0,0),0);
        try(var atlas=new PackageCollisionGpu(200,1);var physics=new PackagePhysicsGpu(1,2,PackageGpuValidation::source);
            var detector=new PackageDeltaGpu(1,PackageGpuValidation::source)) {
            for(int x=-1;x<=1;x++)for(int y=-12;y<=3;y++)for(int z=-1;z<=1;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),air);
            uploadWorld(atlas);var b=bodies(1);body(b,0,8,40,8,1);physics.upload(b,1);
            PackageAuthorityRegion server=null;PackageRegion key=null;long epoch=70;float previous=40;int migrations=0;
            for(int step=0;step<70;step++) {
                if(server==null) {
                    key=PackageRegion.at(target.state.pose());server=new PackageAuthorityRegion(key,owner,++epoch,1,step);
                    var offered=server.offer(target,step);var last=server.prepared(owner,epoch,offered.index(),target.identity,offered.leaseEpoch(),offered.revision(),step);
                    check(server.finalReady(owner,epoch,last.index(),target.identity,last.leaseEpoch(),last.revision(),step),"falling migration final baseline");
                    var meta=BufferUtils.createByteBuffer(32);deltaMeta(meta,0,1);meta.putInt(20,last.index());
                    var q=PackageDeltaCodec.quantize(target.state.pose(),key.originX(),key.originY(),key.originZ(),0);
                    var baseline=BufferUtils.createByteBuffer(32);baseline.putInt(0,q.x()).putInt(4,q.y()).putInt(8,q.z()).putInt(12,q.flags());
                    baseline.putInt(16,q.vx()).putInt(20,q.vy()).putInt(24,q.vz()).putInt(28,q.yaw());detector.upload(meta,baseline,1);
                }
                try(var world=atlas.view(0,0,0)){physics.stepWorldMoving(world,PackagePhysicsGpu.ITERATIONS,List.of());}
                var state=read(physics);check(state.getFloat(4)<previous&&state.getFloat(60)>=0,"falling section physics stopped step="+step+" y="+state.getFloat(4)+" vy="+state.getFloat(20)+" state="+state.getFloat(60));previous=state.getFloat(4);
                var capture=detector.capture(physics.stateBuffer(),1,(float)-key.originX(),(float)-key.originY(),(float)-key.originZ(),1);
                var raw=captureRecords(capture);check(raw.remaining()==64&&raw.getInt(28)==0,"falling section emitted a pose-less release");
                var q=new PackageDeltaCodec.Quantized(raw.getInt(32),raw.getInt(36),raw.getInt(40),(short)raw.getInt(48),(short)raw.getInt(52),(short)raw.getInt(56),(short)raw.getInt(60),raw.getInt(44));
                check(server.deltaStepped(owner,epoch,1,step,step+1,List.of(new PackageDeltaCodec.Entry(raw.getInt(20),raw.getInt(24),q)),4,0,step+1)==PackageAuthorityRegion.Result.ACCEPTED,"falling confirmation rejected");
                check(Math.abs(target.state.pose().y()-(state.getFloat(4)-.5))<=1.0/4096,"falling confirmation rewound to previous section");
                acknowledge(detector,capture,raw);detector.finish(capture);
                if(server.baseline(target.identity)==null){migrations++;server=null;}
            }
            check(migrations==2&&target.releases==2,"falling authority migrated repeatedly at the same boundary");
            check(target.state.pose().vy()< -32,"free-fall stopped at the old velocity encoding limit");
        }
    }
    static void worldMissingAndInvalidated() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int flags:new int[]{1,2,4,8,16}) {
            var hazard=snapshot((s,i)->i==(5|5<<4|4<<8)?new PackageCollisionCache.Cell(List.of(),.6f,flags):WORLD_AIR);
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
                cubeWorld(atlas,air,hazard,0,0,0);ByteBuffer b=bodies(1);body(b,0,5.5f,4.5f,5.5f,1);gpu.upload(b,1);
                try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);gpu.stepWorld(.05f,view);}
                var r=read(gpu);if(flags==8)check(r.getFloat(4)==4.5f && r.getFloat(60)<0,"unsupported contact advanced");
                else check(r.getFloat(4)<4.5f && r.getFloat(60)>=0,"environment incorrectly froze collision path");
            }
        }
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(1);body(b,0,4,6,4,1);gpu.upload(b,1);
            try(var view=atlas.view(0,0,0)) {
                gpu.stepWorld(.05f,view);
                atlas.invalidate(new PackageCollisionCache.Section(0,0,0),floor.revision()+1);
                check(atlas.covered(new PackageCollisionCache.Section(0,0,0),floor.revision())&&!atlas.covered(new PackageCollisionCache.Section(0,0,0),floor.revision()+1),"history geometry version was lost or mislabeled");
                gpu.stepWorld(.05f,view);
            }
            var r=read(gpu);check(r.getFloat(4)<5.9216f && r.getFloat(60)>=0,"immutable historical view was revoked before replacement");
            atlas.forget(new PackageCollisionCache.Section(0,0,0));atlas.offer(new PackageCollisionCache.Section(0,0,0),floor);uploadWorld(atlas);
            gpu.upload(b,1);atlas.forget(new PackageCollisionCache.Section(0,0,0));
            try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            r=read(gpu);check(r.getFloat(4)==6 && r.getFloat(60)<0,"missing section treated as air");
            gpu.upload(bodies(0),0);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}check(gpu.count()==0,"zero world workload changed count");
        }
    }
    static void worldFullCapacity() {
        int n=131072;var air=snapshot((s,i)->WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(196,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            for(int x=0;x<7;x++)for(int y=0;y<4;y++)for(int z=0;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),air);
            uploadWorld(atlas);ByteBuffer b=bodies(n);
            for(int i=0;i<n;i++){body(b,i,2+i%64*1.5f,4+i/4096*1.5f,2+i/64%64*1.5f,1);b.putFloat(i*64+16,.25f);}
            gpu.upload(b,n);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            var r=read(gpu);
            gpu.upload(b,n);try(var view=atlas.view(0,0,0,false)){gpu.stepWorld(.05f,view);}
            var reference=read(gpu);
            for(int i=0;i<n;i++) {
                check(Math.abs(r.getFloat(i*64+4)-(b.getFloat(i*64+4)-.0784f))<1e-5,"full world gravity "+i);
                check(Math.abs(r.getFloat(i*64)-(b.getFloat(i*64)+.01225f))<1e-5,"full world motion "+i);
                check(r.getFloat(i*64+60)>=0,"full world coverage "+i);
                for(int word=0;word<16;word++)check(r.getInt(i*64+word*4)==reference.getInt(i*64+word*4),"coarse/cell full-world parity "+i+"/"+word);
            }
        }
    }
    static void worldPrefetch() {
        var air=snapshot((s,i)->WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(131072,2,PackageGpuValidation::source);
            var prefetch=new PackageWorldPrefetchGpu(71,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);
            var bodies=bodies(131072);
            for(int i=0;i<131072;i++)body(bodies,i,2+(i&15)*.5f,4+(i>>>8&15)*.25f,2+(i>>>4&15)*.5f,1);
            gpu.upload(bodies,131072);
            long tableVersion;
            try(var view=atlas.view(0,0,0)) {
                tableVersion=view.version();
                check(prefetch.capture(gpu.stateBuffer(),gpu.count(),.5f,view),"131072 covered-body prefetch submission");
            }
            GL11.glFinish();var requests=new java.util.HashSet<PackageCollisionCache.Section>();
            var usedSections=new java.util.HashSet<PackageCollisionCache.Section>();
            prefetch.poll(requests::add,new PackageCollisionRequests.Usage() {
                @Override public void begin(long version){check(version==tableVersion&&atlas.beginPackageUsage(version),"usage feedback used a stale collision table");}
                @Override public void row(long version,int row) {
                    var section=atlas.touchPackageUsage(version,row);if(section!=null)usedSections.add(section);
                }
            });
            check(requests.isEmpty(),"fully covered moving population requested resident world sections");
            check(usedSections.contains(new PackageCollisionCache.Section(0,-1,0))
                    && usedSections.contains(new PackageCollisionCache.Section(0,0,0)),
                    "active swept sections were not returned for cache protection");
            try(var view=atlas.view(0,0,0)) {
                for(int i=0;i<PackageReadbackRing.SLOTS;i++)check(prefetch.capture(gpu.stateBuffer(),gpu.count(),.5f,view),"free collision prefetch readback slot "+i);
                check(!prefetch.capture(gpu.stateBuffer(),gpu.count(),.5f,view),"full prefetch readback ring blocked instead of skipping");
            }
            GL11.glFinish();prefetch.poll(section->{throw new AssertionError("resident ring fixture unexpectedly requested "+section);});

            bodies=bodies(1);body(bodies,0,30.5f,8,5.5f,1);bodies.putFloat(16,4);
            gpu.upload(bodies,1);
            try(var view=atlas.view(0,0,0)) {
                check(prefetch.capture(gpu.stateBuffer(),1,.5f,view),"missing-section prefetch submission");
            }
            GL11.glFinish();requests.clear();prefetch.poll(requests::add,new PackageCollisionRequests.Usage() {
                @Override public void begin(long version) {}
                @Override public void row(long version,int row) {}
            });
            check(requests.contains(new PackageCollisionCache.Section(2,0,0)),"GPU did not prefetch the section ahead of the swept package");

            // Sixty-five identical bodies cross a workgroup boundary. Per-group compaction may
            // retain one copy per group, but the decoder must deliver a single numeric request.
            bodies=bodies(65);
            for(int i=0;i<65;i++){body(bodies,i,30.5f,8,5.5f,1);bodies.putFloat(i*64+16,4);}
            gpu.upload(bodies,65);
            try(var view=atlas.view(0,0,0)) {
                check(prefetch.capture(gpu.stateBuffer(),65,.5f,view),"tail-group prefetch submission");
            }
            GL11.glFinish();requests.clear();prefetch.poll(requests::add);
            check(requests.size()==1&&requests.contains(new PackageCollisionCache.Section(2,0,0)),
                    "workgroup-tail prefetch output was lost or not de-duplicated");

            bodies=bodies(65);
            for(int i=0;i<65;i++)body(bodies,i,4,4,4,1);
            bodies.putFloat(2*64+60,-1).putFloat(64*64+60,-1);gpu.upload(bodies,65);requests.clear();
            try(var view=atlas.view(0,0,0)) {
                check(prefetch.capture(gpu.stateBuffer(),65,.5f,view),"invalid lifecycle prefetch submission");
            }
            GL11.glFinish();prefetch.poll(requests::add,new PackageCollisionRequests.Usage() {
                @Override public void begin(long version) {}
                @Override public void row(long version,int row) {}
            });

            check(requests.isEmpty(),"invalid body lifecycle produced collision requests");
            try(var overflowAtlas=new PackageCollisionGpu(64,1)) {
                for(int source=0;source<128;source+=2)
                    check(overflowAtlas.offer(new PackageCollisionCache.Section(source,0,0),air),"overflow source section admission");
                uploadWorld(overflowAtlas);
                bodies=bodies(PackageCollisionRequests.MAX_REQUESTS+1);
                for(int i=0;i<PackageCollisionRequests.MAX_REQUESTS+1;i++) {
                    int sourceSection=(i&63)*2;
                    body(bodies,i,sourceSection*16+13.4f,8,8,1);
                    bodies.putFloat(i*64+16,4.1f);
                }
                gpu.upload(bodies,PackageCollisionRequests.MAX_REQUESTS+1);requests.clear();
                try(var view=overflowAtlas.view(0,0,0)) {
                    check(prefetch.capture(gpu.stateBuffer(),gpu.count(),.5f,view),"collision request overflow fixture submission");
                }
                GL11.glFinish();prefetch.poll(requests::add,new PackageCollisionRequests.Usage() {
                    @Override public void begin(long version) {}
                    @Override public void row(long version,int row) {}
                });
                boolean allDistinctRequestsPresent=requests.size()==64;
                for(int lane=0;lane<64;lane++)
                    allDistinctRequestsPresent&=requests.contains(new PackageCollisionCache.Section(lane*2+1,0,0));
                check(allDistinctRequestsPresent&&prefetch.stats().contains("overflow=1"),
                        "request-buffer overflow silently truncated collision work without reporting its overflow: requests="
                                +requests.size()+" stats="+prefetch.stats());
            }

            bodies=bodies(4);body(bodies,0,4,4,4,1);body(bodies,1,5,4,4,1);
            body(bodies,2,6,4,4,1);body(bodies,3,7,4,4,1);
            bodies.putFloat(64+60,PackagePhysicsGpu.RETIRED).putFloat(3*64+60,PackagePhysicsGpu.PREPARED);
            gpu.upload(bodies,4);requests.clear();
            try(var staleView=atlas.view(0,0,0)) {
                atlas.clear();
                check(!staleView.ready(),"revoked world table remained eligible for package prefetch");
                check(prefetch.capture(gpu.stateBuffer(),gpu.count(),.5f,staleView),"stale-world prefetch submission");
            }
            GL11.glFinish();prefetch.poll(requests::add,new PackageCollisionRequests.Usage() {
                @Override public void begin(long version) {}
                @Override public void row(long version,int row) {}
            });
            check(requests.isEmpty(),"revoked atlas produced coverage from an unavailable table");
        }
    }
    static void worldPrefetchBenchmark() throws Exception {
        var air=snapshot((s,i)->WORLD_AIR);var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,run,gpu_p50_ms,gpu_p95_ms,cpu_capture_p50_ms,cpu_capture_p95_ms,cpu_poll_p50_ms,cpu_poll_p95_ms");
        samples.add("count,run,sample,gpu_ms,cpu_capture_ms,cpu_poll_ms");
        try(var atlas=new PackageCollisionGpu(27,1);var prefetch=new PackageWorldPrefetchGpu(72,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);int timer=GL15.glGenQueries();
            var usage=new PackageCollisionRequests.Usage() {
                @Override public void begin(long version){atlas.beginPackageUsage(version);}
                @Override public void row(long version,int row){atlas.touchPackageUsage(version,row);}
            };
            try {
                try(var view=atlas.view(0,0,0)) {
                for(int count:new int[]{10000,65536,131072}) {
                    try(var gpu=new PackagePhysicsGpu(count,2,PackageGpuValidation::source)) {
                        ByteBuffer input=bodies(count);
                        for(int i=0;i<count;i++)body(input,i,2+(i%28)*.4f,4+(i/784%8)*.4f,2+(i/28%28)*.4f,1);
                        gpu.upload(input,count);
                        for(int run=0;run<3;run++) {
                            int warm=0;while(warm<30){prefetch.capture(gpu.stateBuffer(),count,.5f,view);GL11.glFinish();prefetch.poll(section->{},usage);warm++;}
                            double[] gpuMs=new double[60],captureMs=new double[60],pollMs=new double[60];
                            for(int sample=0;sample<60;sample++) {
                                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long began=System.nanoTime();
                                check(prefetch.capture(gpu.stateBuffer(),count,.5f,view),"prefetch benchmark readback slot");
                                captureMs[sample]=(System.nanoTime()-began)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                                GL11.glFinish();gpuMs[sample]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
                                began=System.nanoTime();prefetch.poll(section->{},usage);pollMs[sample]=(System.nanoTime()-began)/1e6;
                                samples.add(count+","+run+","+sample+","+gpuMs[sample]+","+captureMs[sample]+","+pollMs[sample]);
                            }
                            String row=count+","+run+","+percentile(gpuMs,.5)+","+percentile(gpuMs,.95)+","+percentile(captureMs,.5)+","+
                                    percentile(captureMs,.95)+","+percentile(pollMs,.5)+","+percentile(pollMs,.95);
                            rows.add(row);System.out.println(row);
                            Files.write(Path.of("build/package-world-prefetch-benchmark.csv"),rows);
                            Files.write(Path.of("build/package-world-prefetch-benchmark-samples.csv"),samples);
                        }
                    }
                }
                }
            }finally{GL15.glDeleteQueries(timer);}
        }
    }
    static void worldRigidSupportAndReplacement() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(4,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,floor,0,0,0);ByteBuffer b=bodies(4);
            for(int i=0;i<4;i++)body(b,i,4,1.5f+i,4,1);
            gpu.upload(b,4);try(var view=atlas.view(0,0,0)){for(int i=0;i<100;i++)gpu.stepWorld(.05f,view);}
            var r=read(gpu);for(int i=0;i<4;i++)check(r.getFloat(i*64+4)>=1.5f,"dynamic contacts diluted rigid world support");
        }
        // Queue world versions and GPU copies without finishing between them. Retired
        // storage cannot be overwritten merely because a new CPU version exists.
        var cache=new PackageCollisionCache(Runnable::run,1,()->0L);var section=new PackageCollisionCache.Section(0,0,0);
        cache.request(section);int saved=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,saved);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,12*64,GL15.GL_DYNAMIC_READ);
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);ByteBuffer b=bodies(1);
            for(int version=0;version<12;version++) {
                float height=(version&1)==0?.5f:1f;cache.invalidate(section);
                for(int tick=0;tick<3 && cache.snapshot(section)==null;tick++)cache.tick((s,i)->i>>>8==0?shape(height,.6f):WORLD_AIR,1);
                var snapshot=cache.snapshot(section);atlas.invalidate(section,snapshot.revision());atlas.offer(section,snapshot);uploadWorld(atlas);
                body(b,0,4,4,4,1);b.putFloat(20,-100);gpu.upload(b,1);
                var view=atlas.view(0,0,0);
                // Ring backpressure is legitimate. This offline geometry-lifetime
                // fixture waits only on exhaustion, then tests the requested revision.
                if(!view.ready()){view.close();GL11.glFinish();view=atlas.view(0,0,0);}
                try(var readyView=view){check(readyView.ready(),"version replacement view did not recover");gpu.stepWorld(.05f,readyView);}
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,gpu.stateBuffer());GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,saved);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,version*64,64);
            }
            var records=readBuffer(saved,12*64);
            for(int version=0;version<12;version++) {
                check(Math.abs(records.getFloat(version*64+4)-((version&1)==0?1f:1.5f))<1e-4,"world version snapshot overwritten / version="+version+" y="+records.getFloat(version*64+4)+" sentinel="+records.getFloat(version*64+60));
                check(records.getFloat(version*64+60)>=0,"world version unexpectedly unavailable");
            }
        }finally{GL15.glDeleteBuffers(saved);}
    }
    static void supportProjection() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{1,32,63,64,65,129}) {
            int top=1+n/16;
            try(var atlas=new PackageCollisionGpu(9*(top+2),1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=top;y++)
                    atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
                uploadWorld(atlas);var b=bodies(n);
                for(int i=0;i<n;i++){body(b,i,4,1.5f+i*1.03125f,4,1);b.putFloat(i*64+20,-1);}
                gpu.upload(b,n);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<100;step++)gpu.stepWorld(.05f,view,true,4);}
                var r=read(gpu);var counters=readBuffer(gpu.supportStatsBuffer(),32);
                System.out.println("Support stack "+n+" edges="+counters.getInt(0)+" corrected="+counters.getInt(4)+" rejected="+counters.getInt(8)+" top="+r.getFloat((n-1)*64+4));
                check(counters.getInt(8)==0,"support stack rejected valid bodies");
                for(int i=0;i<n;i++) {
                    check(r.getFloat(i*64+60)>=0,"support projection marked valid stack unsupported");
                    check(Math.abs(r.getFloat(i*64+4)-(1.5f+i))<.002f,"support stack compressed layer "+i+" / "+n);
                    check(r.getFloat(i*64)==4 && r.getFloat(i*64+8)==4,"vertical support moved lateral coordinates");
                }
            }
        }
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source)) {
            cubeWorld(atlas,air,air,0,0,0);var b=bodies(65);for(int i=0;i<65;i++)body(b,i,2+i%7*4,7,2+i/7*3,1);
            gpu.upload(b,65);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view);}
            var reference=read(gpu);gpu.upload(b,65);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var r=read(gpu);var counters=readBuffer(gpu.supportStatsBuffer(),32);
            check(counters.getInt(0)==0 && counters.getInt(16)==0 && counters.getInt(20)==1 && counters.getInt(24)==1,"empty support dispatch did not generate zero groups");
            for(int p=0;p<r.limit();p+=4)check(r.getInt(p)==reference.getInt(p),"support-free frame changed body fields");
        }
    }
    // Validation only: rebuild the final-state grid and inspect every candidate pair, rather
    // than only neighbours in the initial fixture. No fixed per-cell neighbour cutoff.
    static final class ContactProbe implements AutoCloseable {
        final int capacity,tableSize,heads,links,stats,grid,probe,drive;
        final int gridCount,probeCount,driveCount,phaseLocation,floorLocation;
        ContactProbe(int capacity) {
            this.capacity=capacity;tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
            heads=buffer(BufferUtils.createByteBuffer(tableSize*4));links=buffer(BufferUtils.createByteBuffer(capacity*4));
            stats=buffer(BufferUtils.createByteBuffer(32));grid=compute(source("packages/grid.comp"));
            probe=compute(source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                layout(std430,binding=6) buffer Statistics { uint stats[8]; };
                uniform float uFloor;
                shared uint sums[512];
                void main() {
                    uint i=gl_GlobalInvocationID.x,lane=gl_LocalInvocationID.x;
                    uint row[8]=uint[8](0u,0u,0u,0u,0u,0u,0u,0u);
                    if(i<uCount) {
                        Body b=src[i];bool finite=!(any(isnan(b.positionMass))||any(isinf(b.positionMass))
                            ||any(isnan(b.velocityGround))||any(isinf(b.velocityGround))
                            ||any(isnan(b.extentYaw))||any(isinf(b.extentYaw))
                            ||any(isnan(b.previousSleep))||any(isinf(b.previousSleep)));
                        row[3]=b.previousSleep.w<0?1u:0u;row[4]=finite?0u:1u;
                        row[5]=length(b.velocityGround.xyz)>1e-4?1u:0u;row[7]=1u;
                        float maximum=0.0;
                        if(finite && b.previousSleep.w>=0) {
                            ivec3 cell=cellOf(b.positionMass.xyz);
                            for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                                ivec3 neighbor=cell+ivec3(x,y,z);uint j=heads[hashCell(neighbor)];
                                while(j!=END) {
                                    Body other=src[j];
                                    if(j!=i && other.previousSleep.w>=0 && (b.positionMass.w>0 || other.positionMass.w>0)
                                            && all(equal(cellOf(other.positionMass.xyz),neighbor))) {
                                        vec3 overlap=b.extentYaw.xyz+other.extentYaw.xyz-abs(b.positionMass.xyz-other.positionMass.xyz);
                                        if(all(greaterThan(overlap,vec3(0)))) {
                                            float depth=min(overlap.x,min(overlap.y,overlap.z));maximum=max(maximum,depth);
                                            if(j>i && depth>1e-4)row[0]++;
                                        }
                                    }
                                    j=links[j];
                                }
                            }
                        }
                        row[1]=floatBitsToUint(maximum);row[2]=maximum>1e-4?1u:0u;
                        row[6]=finite?floatBitsToUint(max(0.0,uFloor-b.positionMass.y)):0u;
                    }
                    for(uint k=0u;k<8u;k++)sums[k*64u+lane]=row[k];barrier();
                    for(uint stride=32u;stride>0u;stride>>=1u) {
                        if(lane<stride)for(uint k=0u;k<8u;k++) {
                            uint p=k*64u+lane;
                            sums[p]=(k==1u || k==6u)?max(sums[p],sums[p+stride]):sums[p]+sums[p+stride];
                        }
                        barrier();
                    }
                    if(lane==0u)for(uint k=0u;k<8u;k++) {
                        if(k==1u || k==6u)atomicMax(stats[k],sums[k*64u]);else atomicAdd(stats[k],sums[k*64u]);
                    }
                }
                """);
            drive=compute("#define CMI_BODY_INPLACE 1\n"+source("packages/state.glsl")+"""
                layout(local_size_x=64) in;
                uniform float uPhase;
                void main() {
                    uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;Body b=src[i];
                    if(b.positionMass.w>0 && b.previousSleep.w>=0) {
                        float phase=uPhase+float(i/4096u)*.12;
                        b.velocityGround.x+=.08*cos(phase);b.velocityGround.z+=.06*sin(phase);src[i]=b;
                    }
                }
                """);
            for(int program:new int[]{grid,probe,drive}) {
                GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uTableMask"),tableSize-1);
                GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"uCellSize"),2);
            }
            gridCount=GL20.glGetUniformLocation(grid,"uCount");probeCount=GL20.glGetUniformLocation(probe,"uCount");
            driveCount=GL20.glGetUniformLocation(drive,"uCount");phaseLocation=GL20.glGetUniformLocation(drive,"uPhase");
            floorLocation=GL20.glGetUniformLocation(probe,"uFloor");
        }
        void bind(int program,PackagePhysicsGpu gpu) {
            if(gpu.count()>capacity)throw new IllegalArgumentException("Probe capacity");
            GL20.glUseProgram(program);GL30.glUniform1ui(program==grid?gridCount:program==probe?probeCount:driveCount,gpu.count());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,gpu.stateBuffer());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,heads);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,links);
        }
        ByteBuffer inspect(PackagePhysicsGpu gpu){return inspect(gpu,-1e30f);}
        ByteBuffer inspect(PackagePhysicsGpu gpu,float floor) {
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,heads);
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,stats);
                GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
            }
            if(gpu.count()>0) {
                bind(grid,gpu);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                bind(probe,gpu);GL20.glUniform1f(floorLocation,floor);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,stats);GL43.glDispatchCompute((gpu.count()+63)/64,1,1);
            }
            return readBuffer(stats,32);
        }
        void drive(PackagePhysicsGpu gpu,int step) {
            if(gpu.count()==0)return;bind(drive,gpu);GL20.glUniform1f(phaseLocation,step*.15f);
            GL43.glDispatchCompute((gpu.count()+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        }
        public void close(){for(int p:new int[]{grid,probe,drive})GL20.glDeleteProgram(p);for(int b:new int[]{heads,links,stats})GL15.glDeleteBuffers(b);}
    }
    static int compute(String text) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+text);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }
    static void contactProbeReference() {
        try(var gpu=new PackagePhysicsGpu(65,2,PackageGpuValidation::source);var probe=new ContactProbe(65)) {
            for(int n:new int[]{0,1,63,64,65}) {
                var b=bodies(n);var random=new Random(567);
                for(int i=0;i<n;i++)body(b,i,random.nextFloat()*5-2.5f,random.nextFloat()*5-2.5f,random.nextFloat()*5-2.5f,i%5==0?0:1);
                gpu.upload(b,n);var actual=probe.inspect(gpu);int pairs=0,penetrating=0;float maximum=0;
                for(int i=0;i<n;i++) {
                    float own=0;
                    for(int j=0;j<n;j++)if(j!=i && (b.getFloat(i*64+12)>0 || b.getFloat(j*64+12)>0)) {
                        float dx=1-Math.abs(b.getFloat(i*64)-b.getFloat(j*64)),dy=1-Math.abs(b.getFloat(i*64+4)-b.getFloat(j*64+4)),
                                dz=1-Math.abs(b.getFloat(i*64+8)-b.getFloat(j*64+8));
                        if(dx>0 && dy>0 && dz>0){float depth=Math.min(dx,Math.min(dy,dz));own=Math.max(own,depth);if(j>i && depth>1e-4)pairs++;}
                    }
                    maximum=Math.max(maximum,own);if(own>1e-4)penetrating++;
                }
                check(actual.getInt(0)==pairs,"GPU contact probe missed or duplicated a pair");
                check(Math.abs(actual.getFloat(4)-maximum)<1e-6,"GPU contact probe penetration maximum differs");
                check(actual.getInt(8)==penetrating,"GPU contact probe body count differs");
                check(actual.getInt(12)==0 && actual.getInt(16)==0 && actual.getInt(28)==n,"GPU contact probe tail/empty count");
                var terrain=probe.inspect(gpu,-1.25f);float depth=0;
                for(int i=0;i<n;i++)depth=Math.max(depth,-1.25f-b.getFloat(i*64+4));
                check(terrain.getFloat(24)==depth,"GPU contact probe plane depth differs");
            }
        }
    }
    static ByteBuffer stackBodies(int n,int width,int depth,boolean staggered) {
        var b=bodies(n);
        for(int i=0;i<n;i++) {
            int layer=i/(width*depth);float shift=staggered && (layer&1)!=0?.4f:0;
            body(b,i,2+i%width*1.03125f+shift,1.5f+layer*1.03125f,2+i/width%depth*1.03125f+shift,1);
            b.putFloat(i*64+20,-1);
        }
        return b;
    }
    static void supportContactCases() {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int fixture=0;fixture<3;fixture++) {
            boolean staggered=fixture>0;
            int n=1024;
            try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)) {
                cubeWorld(atlas,air,floor,0,0,0);var initial=stackBodies(n,8,8,staggered);
                if(fixture==2)for(int i=0;i<n;i++)initial.putFloat(i*64+12,i%3==0?.25f:4f);
                gpu.upload(initial,n);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<100;step++)gpu.stepWorld(.05f,view,true,4);}
                var settled=read(gpu);var stats=probe.inspect(gpu);
                System.out.println("Support "+(fixture==2?"mixed mass":staggered?"staggered":"aligned")+" 1024 pairs="+stats.getInt(0)+" max="+stats.getFloat(4)+" fallbacks="+stats.getInt(12));
                check(stats.getInt(12)==0 && stats.getInt(16)==0,"support fixture hid failure with fallback/nonfinite");
                check(stats.getFloat(4)<.002,"support fixture retains visible inter-body penetration");
                for(int i=0;i<n;i++)check(Math.abs(settled.getFloat(i*64+4)-(1.5f+i/64))<.003,"support fixture height mismatch");
                // Remove the entire plane between generations while retaining GPU bodies.
                atlas.clear();cubeWorld(atlas,air,air,0,0,0);
                try(var view=atlas.view(0,0,0)){for(int step=0;step<10;step++)gpu.stepWorld(.05f,view,true,4);}
                var falling=read(gpu);stats=probe.inspect(gpu);
                System.out.println("Removed support moving="+stats.getInt(20)+" fallback="+stats.getInt(12)+" finite="+stats.getInt(16)+" root="+falling.getFloat(4)+" vy="+falling.getFloat(20)+" top="+falling.getFloat((n-1)*64+4)+" topVy="+falling.getFloat((n-1)*64+20));
                check(stats.getInt(12)==0 && stats.getInt(16)==0 && stats.getInt(20)==n,"removed support left frozen bodies");
                check(stats.getFloat(4)<.002,"removed support compressed stack");
                for(int i=0;i<n;i++)check(falling.getFloat(i*64+4)<settled.getFloat(i*64+4)-2,"removed support left a suspended body");
            }
        }
        // A body with invalid lifecycle must not be revived by a support edge.
        try(var atlas=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(2,2,PackageGpuValidation::source);var probe=new ContactProbe(2)) {
            cubeWorld(atlas,air,air,0,0,0);var b=bodies(2);body(b,0,4,4,4,1);body(b,1,4,4.9f,4,1);b.putFloat(60,-1);
            gpu.upload(b,2);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
            var r=read(gpu);check(r.getFloat(4)==4 && r.getFloat(60)==-1,"support projection revived a pause body");
            check(probe.inspect(gpu).getInt(12)==1,"contact probe omitted pause body");
        }
    }
    static void worldEntryFace() {
        var air=snapshot((s,i)->WORLD_AIR);
        var geometry=snapshot((s,i)->i>>>8==0 || i>>>8==2?shape(1,.6f):WORLD_AIR);
        int program=compute(source("packages/state.glsl")+source("packages/world_collision.glsl")+"""
            layout(local_size_x=64) in;
            void main() {
                uint i=gl_GlobalInvocationID.x;if(i>=uCount)return;Body b=src[i];
                vec3 correction,velocity=b.velocityGround.xyz;bool grounded=false;float friction;
                bool valid=solveWorld(b,correction,velocity,grounded,friction);
                b.positionMass.xyz+=correction;b.velocityGround=vec4(velocity,grounded?1:0);
                b.previousSleep.w=valid?0:-1;dst[i]=b;
            }
            """);
        int[] locations=new int[5];String[] names={"uWorldReady","uWorldOriginSection","uWorldTableMask","uWorldSlotWords","uWorldShapeCapacity"};
        for(int i=0;i<names.length;i++)locations[i]=GL20.glGetUniformLocation(program,names[i]);
        try(var atlas=new PackageCollisionGpu(27,1)) {
            cubeWorld(atlas,air,geometry,0,0,0);
            for(boolean ceiling:new boolean[]{false,true}) {
                int n=65;var b=bodies(n);
                for(int i=0;i<n;i++) {
                    float x=2+i%8*1.03125f,z=2+i/8*1.03125f;
                    body(b,i,x,ceiling?2.9f:.6f,z,1);b.putFloat(i*64+20,ceiling?20:-20);
                    b.putFloat(i*64+48,x).putFloat(i*64+52,1.5f).putFloat(i*64+56,z);
                }
                int input=buffer(b),output=buffer(bodies(n));
                try {
                    GL20.glUseProgram(program);GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uCount"),n);
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);
                    try(var view=atlas.view(0,0,0)){view.bind(locations,0,true);GL43.glDispatchCompute(2,1,1);}
                    var r=readBuffer(output,n*64);
                    for(int i=0;i<n;i++) {
                        check(r.getFloat(i*64+60)==0,"deep entry incorrectly treated voxel seams as opposing walls");
                        check(Math.abs(r.getFloat(i*64+4)-1.5f)<1e-4,"deep correction resolved through the wrong world face");
                        check(r.getFloat(i*64)==b.getFloat(i*64) && r.getFloat(i*64+8)==b.getFloat(i*64+8),"world entry changed lateral coordinates");
                        check(r.getFloat(i*64+20)==0,"world entry failed to clip incoming velocity");
                    }
                } finally{GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);}
            }
        } finally{GL20.glDeleteProgram(program);}
    }
    static void readbacks(){
        int source=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
        GL15.glBufferData(GL31.GL_COPY_READ_BUFFER,16,GL15.GL_DYNAMIC_DRAW);
        ByteBuffer data=BufferUtils.createByteBuffer(16);
        try(var ring=new PackageReadbackRing(16)) {
            for(int i=0;i<4;i++) {
                data.putInt(0,i);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
                GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER,0,data);
                check(ring.submit(source,1,i),"snapshot rejected free slot");
            }
            for(int i=0;i<8;i++)check(!ring.submit(source,1,4),"snapshot overwrote full ring");
            GL11.glFinish();List<Long> seen=new ArrayList<>();
            check(ring.poll(1,s->{seen.add(s.sequence());check(s.bytes().getInt(0)==s.sequence(),"snapshot source overwritten");})==4,"ring did not drain");
            check(seen.equals(List.of(0L,1L,2L,3L)),"snapshot order");
            check(ring.submit(source,1,4),"ring failed reuse");ring.invalidate();
            check(ring.submit(source,2,0),"new epoch sequence rejected");GL11.glFinish();
            check(ring.poll(2,s->check(s.epoch()==2,"stale epoch leaked"))==1,"reset snapshot missing");
            data.putInt(4,10).putInt(8,11);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
            GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER,0,data);
            check(ring.submit(source,4,8,2,1),"fragment snapshot rejected");GL11.glFinish();
            for(int i=0;i<8;i++) {
                check(ring.pollAvailable(2,s->{check(s.bytes().remaining()==8,"fragment copied unused bytes");return false;})==0,"full journal silently consumed snapshot");
                check(ring.pending()==1,"completed refused snapshot was overwritten");
            }
            check(ring.poll(2,s->{check(s.bytes().getInt(0)==10 && s.bytes().getInt(4)==11,"fragment offset contents");})==1,"deferred snapshot did not resume");
            data.putInt(4,12).putInt(8,13);putBuffer(source,data);
            check(ring.submit(source,4,8,2,2),"cached scratch blocked new snapshot");GL11.glFinish();
            check(ring.poll(2,s->check(s.bytes().getInt(0)==12 && s.bytes().getInt(4)==13,"new snapshot reused old scratch"))==1,"cached slot failed to retire");
        }finally{GL15.glDeleteBuffers(source);}
    }
    static int buffer(ByteBuffer bytes) {
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;
    }
    static void deltaMeta(ByteBuffer m,int i,long generation) {
        int p=i*32;m.putLong(p,0x1234567800000001L+i).putLong(p+8,generation);
        m.putInt(p+16,i).putInt(p+20,i*2+7).putInt(p+24,1);
    }
    static PackageDeltaCodec.Quantized quantizedBody(ByteBuffer b,int i) {
        int p=i*64;return PackageDeltaCodec.quantize(new PackageLease.Pose(b.getFloat(p),
                (float)(b.getFloat(p+4)-b.getFloat(p+36)),b.getFloat(p+8),b.getFloat(p+16),b.getFloat(p+20),b.getFloat(p+24),b.getFloat(p+44)),
                0,0,0,b.getFloat(p+28)>.5?1:0);
    }
    static ByteBuffer captureRecords(PackageDeltaGpu.Capture capture) {
        ByteBuffer header=readBuffer(capture.headerBuffer(),16);int accepted=header.getInt(4);
        check(accepted>=0 && accepted<=capture.capacity(),"unclamped delta capture count");
        return readBuffer(capture.recordBuffer(),accepted*64);
    }
    static void acknowledge(PackageDeltaGpu gpu,PackageDeltaGpu.Capture capture,ByteBuffer records) {
        for(int p=0;p<records.limit();p+=2048*64) {
            var chunk=records.duplicate().order(ByteOrder.nativeOrder());chunk.position(p).limit(Math.min(records.limit(),p+2048*64));
            gpu.acknowledge(capture.stamp(),chunk);
        }
    }
    static void deltas() {
        var empty=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        for(int n:new int[]{0,1,63,64,65,131072}) {
            ByteBuffer b=bodies(Math.max(1,n)),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++) {
                body(b,i,(i%128)*.25f+.125f,(i/128%128)*.25f+.625f,(i/16384)*3+.125f,1);
                int p=i*64;b.putFloat(p+16,i%2==0?.125f:-.5f).putFloat(p+20,(i%3-1)*.25f)
                        .putFloat(p+44,(i%721)-360+(i%4)*.0625f).putFloat(p+28,i%2);
                deltaMeta(meta,i,0x2345678900000001L);
            }
            int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(Math.max(1,n),PackageGpuValidation::source)) {
                gpu.upload(meta,baseline,n);var first=gpu.capture(state,n,0,0,0,Math.max(1,n));
                ByteBuffer out=captureRecords(first);check(out.remaining()==n*64,"full-capacity dirty set missing");
                BitSet seen=new BitSet(n);
                for(int p=0;p<out.limit();p+=64) {
                    int candidate=out.getInt(p+16);check(candidate>=0 && candidate<n && !seen.get(candidate),"duplicate/invalid dirty identity");seen.set(candidate);
                    check(out.getLong(p)==0x1234567800000001L+candidate && out.getLong(p+8)==0x2345678900000001L,"truncated delta identity");
                    check(out.getInt(p+20)==candidate*2+7,"pool index used as wire identity");
                    var expected=quantizedBody(b,candidate);
                    check(out.getInt(p+24)==PackageDeltaCodec.changes(empty,expected) && out.getInt(p+28)==0,"independent field mask/release status");
                    check(out.getInt(p+32)==expected.x() && out.getInt(p+36)==expected.y() && out.getInt(p+40)==expected.z()
                            && out.getInt(p+44)==expected.flags(),"position/flags quantization parity");
                    check(out.getInt(p+48)==expected.vx() && out.getInt(p+52)==expected.vy() && out.getInt(p+56)==expected.vz()
                            && out.getInt(p+60)==expected.yaw(),"velocity/negative yaw quantization parity");
                }
                var blocked=new PackageDeltaGpu.Capture[3];
                for(int i=0;i<3;i++){blocked[i]=gpu.capture(state,n,0,0,0,Math.max(1,n));check(captureRecords(blocked[i]).remaining()==0,"readback implicitly acknowledged delta");}
                check(gpu.capture(state,n,0,0,0,Math.max(1,n))==null,"immutable capture bank overwritten");
                for(var capture:blocked)gpu.finish(capture);
                if(n>0) {
                    gpu.acknowledge(first.stamp()+100,out.duplicate().limit(Math.min(out.limit(),2048*64)));
                    // A wrong-stamp ACK must leave the pending records suppressed, rather than ACKing them.
                    var ignored=gpu.capture(state,n,0,0,0,n);check(captureRecords(ignored).remaining()==0,"wrong ACK stamp released flights");gpu.finish(ignored);
                }
                gpu.cancel(first);var retry=gpu.capture(state,n,0,0,0,Math.max(1,n));
                ByteBuffer again=captureRecords(retry);check(again.remaining()==n*64,"cancel lost dirty state");
                acknowledge(gpu,retry,again);gpu.finish(retry);
                var clean=gpu.capture(state,n,0,0,0,Math.max(1,n));check(captureRecords(clean).remaining()==0,"stationary acknowledged state was resent");gpu.finish(clean);
            }finally{GL15.glDeleteBuffers(state);}
        }
    }
    static void deltaIncrementalIdentity() {
        ByteBuffer stateBytes=bodies(4),initial=BufferUtils.createByteBuffer(64),initialBaseline=BufferUtils.createByteBuffer(64);
        for(int i=0;i<4;i++)body(stateBytes,i,i*2,3,4,1);
        for(int i=0;i<2;i++)deltaMeta(initial,i,1);
        int state=buffer(stateBytes);
        try(var gpu=new PackageDeltaGpu(4,PackageGpuValidation::source)) {
            gpu.upload(initial,initialBaseline,2);
            ByteBuffer duplicate=BufferUtils.createByteBuffer(32),baseline=BufferUtils.createByteBuffer(32);
            deltaMeta(duplicate,0,1);duplicate.putInt(16,2).putInt(20,11);
            boolean rejected=false;
            try{gpu.append(duplicate,baseline,1);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"delta append reused an existing stable identity");
            duplicate.putLong(0,900001).putInt(16,1);
            rejected=false;
            try{gpu.append(duplicate,baseline,1);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"delta append reused an existing body index");
            duplicate.putInt(16,2);gpu.append(duplicate,baseline,1);
            var capture=gpu.capture(state,4,0,0,0,4);
            var records=captureRecords(capture);
            check(records.remaining()==3*64,"rejected delta append altered candidate range");
            BitSet candidates=new BitSet();for(int p=0;p<records.limit();p+=64)candidates.set(records.getInt(p+16));
            check(candidates.cardinality()==3,"incremental delta candidates lost uniqueness");
            gpu.cancel(capture);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static ClientboundPackagePacket freeCheckpoint(int action,PackageRegion region,long revision,PackageLease.Pose pose) {
        var baseline=new PackageAuthorityRegion.Baseline(17,new PackageLease.Identity(900001,700001),13,revision,
                new PackageAuthorityRegion.Snapshot(pose,PackageAuthorityRegion.GROUNDED));
        return new ClientboundPackagePacket(action,ResourceLocation.parse("minecraft:overworld"),region,19,23,0,baseline,
                42,new UUID(123,456),ResourceLocation.parse("create:cardboard"),.75f,1);
    }
    static void deltaPreparedBaselines() {
        var region=new PackageRegion(-2,32,3);
        var offer=freeCheckpoint(ClientboundPackagePacket.OFFER,region,1,
                new PackageLease.Pose(-120.25,2050.5,196.25,.125f,0,0,45));
        var finalPacket=freeCheckpoint(ClientboundPackagePacket.FINAL_BASELINE,region,2,
                new PackageLease.Pose(-118.875,2051.25,198.5,.25f,-.5f,.125f,-359.5f));
        // Nonzero offsets and reused scratch storage exercise the production upload contract.
        ByteBuffer bodyStorage=BufferUtils.createByteBuffer(80),poolStorage=BufferUtils.createByteBuffer(96);
        for(int i=0;i<80;i++)bodyStorage.put(i,(byte)0x5a);
        for(int i=0;i<96;i++)poolStorage.put(i,(byte)0x5a);
        var uploadedBody=bodyStorage.duplicate().position(8).limit(72);
        var poolMeta=poolStorage.duplicate().position(8).limit(88);
        var deltaMeta=BufferUtils.createByteBuffer(32);var baseline=BufferUtils.createByteBuffer(32);
        var style=new PackageModelCache.Style(0,1);
        PackageFreeUpload.prepared(offer,offer,style,1,0xf000f0,uploadedBody,poolMeta,deltaMeta,baseline);
        check(uploadedBody.position()==8 && poolMeta.position()==8,"upload consumed reusable scratch positions");
        check(bodyStorage.get(0)==0x5a && bodyStorage.get(79)==0x5a
                && poolStorage.get(0)==0x5a && poolStorage.get(95)==0x5a,"upload overwrote adjacent records");
        var b=uploadedBody.slice().order(ByteOrder.nativeOrder());
        check(b.getFloat(0)==7.75f && b.getFloat(4)==3 && b.getFloat(8)==4.25f,
                "server feet pose was not converted to region-local collider centre");
        check(b.getFloat(12)==1 && b.getFloat(28)==1 && b.getFloat(32)==.375f && b.getFloat(36)==.5f
                && b.getFloat(60)==PackagePhysicsGpu.PREPARED,
                "server collider or ground state lost during upload");
        check(poolMeta.order(ByteOrder.nativeOrder()).getInt(8+28)==PackagePoolGpu.HIDDEN
                && deltaMeta.getInt(24)==0,"prepared checkpoint enabled rendering or sync");
        ByteBuffer stateBytes=bodies(2),meta=BufferUtils.createByteBuffer(64),baselines=BufferUtils.createByteBuffer(64);
        body(stateBytes,0,1,2,3,1);stateBytes.position(64);stateBytes.put(b.duplicate());stateBytes.clear();
        deltaMeta(meta,0,1);meta.position(32);meta.put(deltaMeta.duplicate());meta.clear();
        baselines.position(32);baselines.put(baseline.duplicate());baselines.clear();
        int state=buffer(stateBytes);
        try(var gpu=new PackageDeltaGpu(2,PackageGpuValidation::source)) {
            gpu.upload(meta,baselines,2);
            var old=gpu.capture(state,2,0,0,0,2);var oldRecords=captureRecords(old);
            check(oldRecords.remaining()==64 && oldRecords.getInt(16)==0,"prepared candidate produced a delta");
            PackageFreeUpload.prepared(offer,finalPacket,style,1,0xf000f0,uploadedBody,poolMeta,deltaMeta,baseline);
            stateBytes.position(64);stateBytes.put(uploadedBody.slice());stateBytes.clear();putBuffer(state,stateBytes);
            gpu.rebasePrepared(1,baseline);
            var inactive=gpu.capture(state,2,0,0,0,2);
            check(captureRecords(inactive).remaining()==0,"final rebase enabled sync or reset unrelated flights");gpu.finish(inactive);
            gpu.activate(1);
            stateBytes.putFloat(64+60,0);putBuffer(state,stateBytes);
            var clean=gpu.capture(state,2,0,0,0,2);
            check(captureRecords(clean).remaining()==0,"final checkpoint immediately resent an unchanged pose");gpu.finish(clean);
            stateBytes.putFloat(64+16,.5f);putBuffer(state,stateBytes);
            var changed=gpu.capture(state,2,0,0,0,2);var records=captureRecords(changed);
            check(records.remaining()==64 && records.getInt(16)==1 && records.getInt(20)==17
                    && records.getLong(0)==900001 && records.getLong(8)==700001
                    && records.getInt(24)==PackageDeltaCodec.VELOCITY,"activation lost stable identity or independent fields");
            boolean rejected=false;try{gpu.rebasePrepared(1,baseline);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"active candidate overwrote an unacknowledged baseline");
            rejected=false;try{gpu.activate(1);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"repeated ACTIVE replaced a candidate flight");
            acknowledge(gpu,old,oldRecords);gpu.finish(old);acknowledge(gpu,changed,records);gpu.finish(changed);
            var done=gpu.capture(state,2,0,0,0,2);check(captureRecords(done).remaining()==0,"final rebase damaged another ACK baseline");gpu.finish(done);
            gpu.upload(meta,baselines,2);
            var release=BufferUtils.createByteBuffer(64);
            release.putLong(0,900001).putLong(8,700002).putInt(16,1).putInt(20,17).putInt(28,1);
            gpu.serverReleased(release);gpu.rebasePrepared(1,baseline);
            release.putLong(8,700001);gpu.serverReleased(release);
            rejected=false;try{gpu.activate(1);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"late ACTIVE resurrected a server-released prepared candidate");
            rejected=false;try{gpu.rebasePrepared(1,baseline);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"released candidate accepted a delayed final baseline");
        }finally{GL15.glDeleteBuffers(state);}
        ByteBuffer prior=BufferUtils.createByteBuffer(80);prior.put(bodyStorage.duplicate()).flip();boolean rejected=false;
        var stale=freeCheckpoint(ClientboundPackagePacket.FINAL_BASELINE,region,1,offer.baseline().snapshot().pose());
        try{PackageFreeUpload.prepared(offer,stale,style,1,0,uploadedBody,poolMeta,deltaMeta,baseline);}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected && bodyStorage.equals(prior),"stale final checkpoint changed upload storage");
    }
    static ClientboundPackagePacket acquisitionPacket(ClientboundPackagePacket template,int action,int index,long id,long epoch,long revision) {
        var b=template.baseline();var baseline=new PackageAuthorityRegion.Baseline(index,new PackageLease.Identity(id,b.identity().generation()),
                b.leaseEpoch(),revision,b.snapshot());
        return new ClientboundPackagePacket(action,template.dimension(),template.region(),epoch,template.regionRevision(),0,baseline,
                template.entityId(),template.entityUuid(),template.model(),template.width(),template.height());
    }
    static void acquisitionFrame(PackageMixedPhysicsGpu physics,PackagePoolGpu pool,PackageFreeAcquisitionGpu acquisition,
                                 int particles,int counter,double ox,double oy,double oz,long generation,boolean commit) {
        physics.publish();physics.source(pool,(float)ox,(float)oy,(float)oz);
        putBuffer(counter,BufferUtils.createByteBuffer(16));pool.stage(particles,counter,3,new float[24],0,0,0);
        if(commit){pool.commit();acquisition.committed(generation);}else pool.abort();
    }
    static void recycledFreeAcquisitions(){
        int capacity=4,particles=buffer(bodies(capacity)),counter=buffer(BufferUtils.createByteBuffer(16));
        var region=new PackageRegion(0,0,0);var template=freeCheckpoint(ClientboundPackagePacket.OFFER,region,1,new PackageLease.Pose(4,4,4,0,0,0,0));
        int[] activated={0},released={0};long frame=1;
        try(var physics=new PackageMixedPhysicsGpu(capacity,0,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(capacity,1,PackageGpuValidation::source);var detector=new PackageDeltaGpu(capacity,PackageGpuValidation::source);
            var acquisition=new PackageFreeAcquisitionGpu(region,19,23,0,0,0,physics,pool,detector,Map.of(template.model(),new PackageModelCache.Style(0,-1)),p->0,new PackageFreeAcquisitionGpu.Transport(){
                public void control(int action,ClientboundPackagePacket packet){check(action!=ServerboundPackagePacket.RELEASE,"recycled free pool unexpectedly exhausted");}
                public void activated(ClientboundPackagePacket offer,ClientboundPackagePacket active,int candidate,int slot){activated[0]++;}
                public void released(ClientboundPackagePacket offer,PackageAuthorityRegion.Baseline baseline){released[0]++;}
            });var channel=new PackageDeltaChannel(detector,capacity,19,23,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageDeltaChannel.Transport(){
                public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes){return true;}
                public void failed(String reason){throw new AssertionError(reason);}
            })){
            acquisition.attachChannel(channel);physics.enableEnvironment(PackageGpuValidation::source);
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            ClientboundPackagePacket previous=null;
            for(int life=0;life<64;life++){
                for(int phase=0;phase<3;phase++){
                    int action=phase==0?ClientboundPackagePacket.OFFER:phase==1?ClientboundPackagePacket.FINAL_BASELINE:ClientboundPackagePacket.ACTIVE;
                    var packet=acquisitionPacket(template,action,17,900001+life,19,phase==0?1:2);acquisition.receive(packet);
                    acquisition.pump(64,(o,c)->true);acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,frame++,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
                }
                check(acquisition.activeCount()==1&&physics.freeCount()==1&&pool.metadataCount()==1&&detector.metadataCount()==1,"recycling grew append-only indices");
                var active=acquisitionPacket(template,ClientboundPackagePacket.ACTIVE,17,900001+life,19,2);
                if(previous!=null){
                    acquisition.receive(previous);acquisition.receive(acquisitionPacket(previous,ClientboundPackagePacket.FINAL_BASELINE,17,previous.baseline().identity().id(),19,2));
                    var oldAck=new ClientboundPackagePacket(ClientboundPackagePacket.ENVIRONMENT_ACK,template.dimension(),region,19,23,20,previous.baseline(),-1,template.entityUuid(),template.model(),template.width(),template.height(),100,1,7);
                    acquisition.receive(oldAck);check(readBuffer(physics.environment().headerBuffer(),64).getInt(28)==0,"old environment ACK altered reused lifetime");
                }
                var h=readBuffer(physics.environment().headerBuffer(),64);check(h.getLong(0)==900001+life&&h.getInt(24)==0&&h.getInt(28)==0&&h.getInt(32)==0,"reused environment sidecars retained state");
                var release=acquisitionPacket(active,ClientboundPackagePacket.RELEASED,17,900001+life,19,2);acquisition.receive(release);acquisition.pump(64,(o,c)->true);
                acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,frame++,true);GL11.glFinish();acquisition.pump(64,(o,c)->true);GL11.glFinish();acquisition.pump(64,(o,c)->true);
                check(acquisition.activeCount()==0&&physics.freeCount()==0&&pool.metadataCount()==0&&detector.metadataCount()==0,"safe suffix was not reclaimed");previous=release;
            }
            check(activated[0]==64&&released[0]==64,"free lifetime transitions were lost/duplicated");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    /** Continuously add belt output while earlier bodies run, with multiple renders per step. */
    static void continuousFreeAcquisitions() {
        for(int[] cadence:new int[][]{{60,20},{60,10},{60,5},{5,20},{60,200},{5,200}})continuousFreeAcquisitions(cadence[0],cadence[1]);
    }
    static void continuousFreeAcquisitions(int fps,int tps) {
        var owner=new UUID(7,9);var region=new PackageRegion(0,0,0);
        double targetRate=Math.max(20,tps);
        var server=new PackageAuthorityRegion(region,owner,19,23,0,()->targetRate);
        var model=ResourceLocation.parse("create:cardboard_package_12x12");
        var dimension=ResourceLocation.parse("minecraft:overworld");
        var incoming=new ArrayDeque<ClientboundPackagePacket>();var targets=new ArrayList<ChannelTarget>();
        long[] tick={0},generation={0};int[] visible={0},packets={0};
        PackageDeltaChannel[] channelRef={null};
        java.util.function.BiFunction<Integer,PackageAuthorityRegion.Baseline,ClientboundPackagePacket> packet=(action,b)->
                new ClientboundPackagePacket(action,dimension,region,19,23,0,b,42,new UUID(1,b.identity().id()),model,.75f,.5f);
        var air=snapshot((s,i)->WORLD_AIR);
        var floor=snapshot((s,i)->(i>>>8)==0?new PackageCollisionCache.Cell(List.of(new PackageCollisionCache.Box(0,0,0,1,1,1)),.6f,0):WORLD_AIR);
        int particles=buffer(bodies(64)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var world=new PackageCollisionGpu(27,1);var physics=new PackageMixedPhysicsGpu(64,0,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(64,1,PackageGpuValidation::source);
            var detector=new PackageDeltaGpu(64,PackageGpuValidation::source,true,true);
            var acquisition=new PackageFreeAcquisitionGpu(region,19,23,0,0,0,physics,pool,detector,Map.of(model,new PackageModelCache.Style(0,-1)),p->0,new PackageFreeAcquisitionGpu.Transport(){
                public void control(int action,ClientboundPackagePacket p){
                    var b=p.baseline();
                    if(action==ServerboundPackagePacket.PREPARED){
                        var next=server.prepared(owner,19,b.index(),b.identity(),b.leaseEpoch(),b.revision(),tick[0]);
                        check(next!=null,"continuous PREPARED rejected");incoming.add(packet.apply(ClientboundPackagePacket.FINAL_BASELINE,next));
                    }else if(action==ServerboundPackagePacket.FINAL_READY){
                        check(server.finalReady(owner,19,b.index(),b.identity(),b.leaseEpoch(),b.revision(),tick[0]),"continuous FINAL_READY rejected");
                        incoming.add(packet.apply(ClientboundPackagePacket.ACTIVE,server.baseline(b.identity())));
                    }else throw new AssertionError("continuous acquisition revoked: "+action);
                }
                public void activated(ClientboundPackagePacket o,ClientboundPackagePacket a,int candidate,int slot){visible[0]++;}
                public void released(ClientboundPackagePacket o,PackageAuthorityRegion.Baseline b){throw new AssertionError("continuous admission retired "+b.index());}
            });
            var channel=new PackageDeltaChannel(detector,64,19,23,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageDeltaChannel.Transport(){
                public boolean batchEncoded(){return true;}public boolean relativePositions(){return true;}public boolean predictedPositions(){return true;}
                public Object prepare(long e,long r,long seq,long step,ByteBuffer bytes){return step;}
                public boolean send(long e,long r,long seq,ByteBuffer bytes){throw new AssertionError("missing step envelope");}
                public boolean sendPrepared(long e,long r,long seq,Object prepared,ByteBuffer bytes){
                    var changes=PackageBatchDeltaCodec.decode(bytes);
                    var result=server.deltaStepped(owner,e,r,seq,tick[0],changes,4,2,(Long)prepared);
                    check(result==PackageAuthorityRegion.Result.ACCEPTED,"continuous delta rejected: "+result+" tick="+tick[0]+" step="+prepared+" sequence="+seq+" changes="+changes);
                    check(channelRef[0].acknowledge(e,r,seq),"continuous ACK rejected");packets[0]++;return true;
                }
                public void failed(String reason){throw new AssertionError("continuous channel failed: "+reason);}
            })) {
            acquisition.attachChannel(channel);channelRef[0]=channel;cubeWorld(world,air,floor,0,0,0);
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            var simulationClock=new PackageSimulationClock();long lastOfferTick=-2,clientCaptures=0;
            var inputs=new com.iridium126.createmanaindustry.client.particles.packages.PackageInputTimeline();
            var captureForces=PackageForceScene.bake(0,List.of(),0,0,0);
            var capturedSources=new Object();
            try(var upload=new PackageForceGpu()){
            for(int frame=0;frame<=fps*20;frame++){
                long now=frame*1_000_000_000L/fps;tick[0]=now*tps/1_000_000_000L;
                long capturesDue=now*Math.min(20,tps)/1_000_000_000L;
                while(clientCaptures<capturesDue){clientCaptures++;inputs.advance(targetRate);}
                long availableInput=inputs.current().last();
                server.heartbeat(owner,19,tick[0]);server.tick(tick[0]);
                while(!incoming.isEmpty())acquisition.receive(incoming.removeFirst());
                if(tick[0]-lastOfferTick>=2&&targets.size()<48){
                    lastOfferTick=tick[0];
                    var target=new ChannelTarget(targets.size());
                    target.state=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(6.376,4.875,5.5,3.75f,3.75f,0,0),0);targets.add(target);
                    var offered=server.offer(target,tick[0]);check(offered!=null,"continuous offer refused");acquisition.receive(packet.apply(ClientboundPackagePacket.OFFER,offered));
                }
                GL11.glFinish();acquisition.pump(64,(o,c)->true);channel.pump(256);
                if(acquisition.activeCount()==0)simulationClock.reset();
                else simulationClock.sample(now,availableInput,false,targetRate,(int)Math.ceil(targetRate/20));
                for(int n=0;n<simulationClock.stepsPerFrame()&&simulationClock.due(availableInput);n++){
                    long inputTick=simulationClock.nextTick();
                    var exactForces=new PackageForceScene.Snapshot(inputTick,0,0,0,captureForces.data());
                    try(var forces=upload.tryView(exactForces,inputTick,capturedSources)){
                        check(forces!=null,"continuous immutable capture exhausted force banks");physics.applyFreeForces(forces,.05f);
                    try(var view=world.view(0,0,0)){physics.stepFreeMoving(view,4,List.of());}
                    simulationClock.commit(simulationClock.nextTick());
                    }
                }
                check(!simulationClock.historyGap(),"continuous clock revoked authority at FPS/TPS="+fps+"/"+tps);
                acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,generation[0]++,true);
                acquisition.captureCommitted(channel,generation[0]-1);
                check(!channel.closed(),"continuous channel closed");
                check(physics.freeSimulationStep()==simulationClock.step(),"continuous clock consumed an unsubmitted step");
                var state=readBuffer(physics.freeStateBuffer(),physics.freeCount()*64);
                for(int i=0;i<physics.freeCount();i++){
                    int offset=i*64;float status=state.getFloat(offset+60);
                    check(status>=0||status==PackagePhysicsGpu.PREPARED,"continuous test replaced active physics with local pauses");
                    for(int axis=0;axis<3;axis++)check(Float.isFinite(state.getFloat(offset+axis*4)),"continuous test produced a non-finite pose");
                }
            }
            }
            check(visible[0]==48&&acquisition.activeCount()==48&&packets[0]>30,"continuous acquisition stalled");
            for(var target:targets)check(target.releases==0,"continuous server released a package");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    static void acquisitions() {
        var region=new PackageRegion(-2,32,3);double ox=region.originX()-16,oy=region.originY(),oz=region.originZ();
        var offer=freeCheckpoint(ClientboundPackagePacket.OFFER,region,1,new PackageLease.Pose(-120.25,2050.5,196.25,.125f,0,0,45));
        var finalPacket=freeCheckpoint(ClientboundPackagePacket.FINAL_BASELINE,region,2,new PackageLease.Pose(-118.875,2051.25,198.5,.25f,-.5f,.125f,-359.5f));
        var active=acquisitionPacket(finalPacket,ClientboundPackagePacket.ACTIVE,17,900001,19,2);
        var controls=new ArrayList<Integer>();var checkpoints=new ArrayList<ClientboundPackagePacket>();
        var visible=new ArrayList<Integer>();var released=new ArrayList<Integer>();
        var deltaSequences=new ArrayList<Long>();
        int particles=buffer(bodies(8)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackageMixedPhysicsGpu(8,0,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(8,1,PackageGpuValidation::source);
            var detector=new PackageDeltaGpu(8,PackageGpuValidation::source);
            var acquisition=new PackageFreeAcquisitionGpu(region,19,23,ox,oy,oz,physics,pool,detector,
                    Map.of(offer.model(),new PackageModelCache.Style(0,-1)),p->0xf000f0,new PackageFreeAcquisitionGpu.Transport() {
                @Override public void control(int action,ClientboundPackagePacket p){controls.add(action);checkpoints.add(p);}
                @Override public void activated(ClientboundPackagePacket o,ClientboundPackagePacket a,int candidate,int slot){visible.add(slot);}
                @Override public void released(ClientboundPackagePacket o,PackageAuthorityRegion.Baseline b){released.add(b.index());}
            });
            var channel=new PackageDeltaChannel(detector,8,19,23,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageDeltaChannel.Transport() {
                @Override public boolean send(long e,long r,long sequence,ByteBuffer bytes){deltaSequences.add(sequence);return true;}
                @Override public void failed(String reason){throw new AssertionError(reason);}
            })) {
            acquisition.attachChannel(channel);
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);
            pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            check(acquisition.receive(offer) && acquisition.pendingCount()==1,"offer was not queued");
            acquisition.receive(offer);check(acquisition.pendingCount()==1,"duplicate offer allocated another transition");
            acquisition.pump(64,(o,c)->false);check(physics.freeCount()==0 && controls.isEmpty(),"missing coverage allocated or froze Create");
            acquisition.pump(64,(o,c)->true);
            check(physics.freeCount()==1 && detector.metadataCount()==1 && pool.metadataCount()==1,"acquisition did not append shared GPU records");
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,1,true);
            check(controls.isEmpty() && visible.isEmpty(),"acquisition trusted a submitted fence before polling");
            var body=readBuffer(physics.bodyBuffer(),64);
            check(body.getFloat(0)==23.75f && body.getFloat(60)==PackagePhysicsGpu.PREPARED,"shared physics origin or preparation freeze lost");
            check(readBuffer(pool.commandBuffer(),16).getInt(4)==0,"offered package was drawn before ACTIVE");
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(controls.equals(List.of(ServerboundPackagePacket.PREPARED)) && checkpoints.getFirst().baseline().revision()==1,
                    "PREPARED was not tied to actual hidden admission");
            acquisition.receive(acquisitionPacket(offer,ClientboundPackagePacket.ACTIVE,17,900001,19,1));
            acquisition.pump(64,(o,c)->true);check(acquisition.activeCount()==0,"premature ACTIVE bypassed final baseline");
            acquisition.receive(finalPacket);acquisition.receive(finalPacket);acquisition.pump(64,(o,c)->true);
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,2,true);
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(controls.equals(List.of(ServerboundPackagePacket.PREPARED,ServerboundPackagePacket.FINAL_READY))
                    && checkpoints.getLast().baseline().revision()==2,"final READY did not acknowledge final GPU checkpoint");
            check(!acquisition.receive(acquisitionPacket(active,ClientboundPackagePacket.ACTIVE,17,900001,20,2)),"wrong authority epoch accepted");
            acquisition.receive(acquisitionPacket(active,ClientboundPackagePacket.ACTIVE,17,900002,19,2));
            acquisition.pump(64,(o,c)->true);check(acquisition.activeCount()==0,"wrong stable identity activated package");
            acquisition.receive(active);acquisition.pump(64,(o,c)->true);
            check(visible.isEmpty(),"Create rendering suppressed before visible generation committed");
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,3,true);
            check(readBuffer(pool.commandBuffer(),16).getInt(4)==1 && visible.isEmpty(),"visible admission confirmation boundary lost");
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(visible.size()==1 && visible.getFirst()==1 && acquisition.activeCount()==1 && acquisition.pendingCount()==0,
                    "matching ACTIVE/visible admission did not publish ownership");
            boolean rejected=false;
            try{acquisition.captureCommitted(channel,2);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"delta capture accepted a failed/stale pool generation");
            check(!acquisition.captureCommitted(channel,3),"admission sent an invalid zero-step delta before simulation");
            check(!acquisition.captureCommitted(channel,3),"unchanged publication scheduled a duplicate full detection");
            GL11.glFinish();channel.pump(256);
            var clean=detector.capture(physics.bodyBuffer(),physics.bodyCount(),-16,0,0,8);
            check(captureRecords(clean).remaining()==0,"shared physics origin corrupted region delta baseline");detector.finish(clean);
            check(deltaSequences.isEmpty(),"unchanged acquisition sent a delta packet");
            // The newest publication can be skipped because an older state of this identity is
            // still in flight. Its exact ACK must trigger another detection even while paused.
            physics.stepFree(.05f);physics.publish();
            ByteBuffer changed=readBuffer(physics.bodyBuffer(),64);changed.putFloat(0,changed.getFloat(0)+.125f);
            physics.replaceFree(0,changed,bodies(1),1);
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,4,true);
            check(acquisition.captureCommitted(channel,4),"changed acquisition did not schedule detection");
            for(int i=0;i<4 && deltaSequences.isEmpty();i++){GL11.glFinish();channel.pump(256);}
            check(deltaSequences.equals(List.of(0L)),"changed acquisition was not journaled exactly once");
            changed.putFloat(0,changed.getFloat(0)+.125f);physics.replaceFree(0,changed,bodies(1),1);
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,5,true);
            check(acquisition.captureCommitted(channel,5),"newer in-flight state was not sampled");
            GL11.glFinish();channel.pump(256);
            check(!acquisition.captureCommitted(channel,5),"paused state repeated detection without an ACK");
            check(channel.acknowledge(19,23,0),"exact acquisition ACK refused");channel.pump(256);
            check(acquisition.captureCommitted(channel,5),"ACK lost newer paused state behind an older flight");
            for(int i=0;i<4 && deltaSequences.size()<2;i++){GL11.glFinish();channel.pump(256);}
            check(deltaSequences.equals(List.of(0L,1L)),"ACK retry did not send the newest paused state");
            check(channel.acknowledge(19,23,1),"newest paused state ACK refused");channel.pump(256);
            acquisition.receive(active);acquisition.pump(64,(o,c)->true);check(visible.size()==1,"duplicate ACTIVE republished ownership");
            acquisition.requestRelease(17);
            acquisition.pump(64,(o,c)->true);
            check(released.isEmpty() && readBuffer(pool.commandBuffer(),16).getInt(4)==1,"pause crossed the uncommitted draw boundary");
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,6,true);
            check(readBuffer(pool.commandBuffer(),16).getInt(4)==0,"retired package remained drawn");
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(released.equals(List.of(17)) && acquisition.pendingCount()==0 && acquisition.activeCount()==0,
                    "retired admission did not restore Create exactly once");
            body=readBuffer(physics.bodyBuffer(),64);check(body.getFloat(60)==PackagePhysicsGpu.RETIRED,"retired package left a contact ghost");
            acquisition.receive(active);acquisition.receive(finalPacket);acquisition.pump(64,(o,c)->true);
            check(visible.size()==1,"late ACTIVE/final baseline resurrected retired identity");
            var reused=acquisitionPacket(offer,ClientboundPackagePacket.OFFER,18,900001,19,1);
            acquisition.receive(reused);acquisition.pump(64,(o,c)->true);
            check(physics.freeCount()==1 && acquisition.pendingCount()==1,
                    "retired stable identity could not reuse its fenced slot");
            check(pool.reservesIdentity(reused.baseline().identity().id(),reused.baseline().identity().generation()),"new lease reservation missing");
            acquisition.receive(acquisitionPacket(reused,ClientboundPackagePacket.RELEASED,18,900001,19,1));
            acquisition.pump(64,(o,c)->true);
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,7,true);
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(released.equals(List.of(17,18)),"reacquired identity did not retire exactly once");
            var neverAllocated=acquisitionPacket(offer,ClientboundPackagePacket.OFFER,19,900003,19,1);
            acquisition.receive(neverAllocated);acquisition.receive(acquisitionPacket(neverAllocated,ClientboundPackagePacket.RELEASED,19,900003,19,1));
            acquisition.pump(64,(o,c)->true);check(physics.freeCount()==0 && released.equals(List.of(17,18,19)),"preflight release allocated a body");
            var aborted=acquisitionPacket(offer,ClientboundPackagePacket.OFFER,20,900004,19,1);
            acquisition.receive(aborted);acquisition.pump(64,(o,c)->true);int before=controls.size();
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,8,false);
            GL11.glFinish();acquisition.pump(64,(o,c)->true);check(controls.size()==before,"failed frame published PREPARED");
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,9,true);
            acquisition.receive(acquisitionPacket(aborted,ClientboundPackagePacket.RELEASED,20,900004,19,1));
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(controls.size()==before && released.size()==3,"superseded admission published a partial transition");
            acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,10,true);
            GL11.glFinish();acquisition.pump(64,(o,c)->true);
            check(released.equals(List.of(17,18,19,20)) && acquisition.pendingCount()==0,"release during pending admission was lost");
            var sparseIndex=acquisitionPacket(offer,ClientboundPackagePacket.OFFER,32768,900099,19,1);
            int allocated=physics.freeCount();acquisition.receive(sparseIndex);acquisition.pump(64,(o,c)->false);
            check(acquisition.pendingCount()==1 && physics.freeCount()<=allocated && physics.freeLiveCount()==0,
                    "sparse server local ID was confused with dense GPU candidate capacity");
            acquisition.requestRelease(32768);
            physics.stepFree(.05f);physics.publish();rejected=false;
            try{acquisition.committed(11);}catch(IllegalStateException expected){rejected=true;}
            check(rejected,"admission accepted physics buffers different from the committed draw input");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    /** Real admission fences feed the production queue across a workgroup tail. No control
     * can suppress native replication after an aborted/unconfirmed visible submission. */
    static void regionalRetirementIsolation() {
        int count=65,capacity=128,particles=buffer(bodies(capacity)),counter=buffer(BufferUtils.createByteBuffer(16));
        var region=new PackageRegion(0,0,0);var template=freeCheckpoint(ClientboundPackagePacket.OFFER,region,1,new PackageLease.Pose(4,4,4,0,0,0,0));
        var workerTasks=new java.util.ArrayDeque<Runnable>();int[] released={0};
        var air=snapshot((s,i)->WORLD_AIR);
        try(var world=new PackageCollisionGpu(27,1);var physics=new PackageMixedPhysicsGpu(capacity,0,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(capacity,1,PackageGpuValidation::source);var detector=new PackageDeltaGpu(capacity,PackageGpuValidation::source);
            var acquisition=new PackageFreeAcquisitionGpu(region,19,23,0,0,0,physics,pool,detector,Map.of(template.model(),new PackageModelCache.Style(0,-1)),p->0,new PackageFreeAcquisitionGpu.Transport(){
                public void control(int action,ClientboundPackagePacket packet){}
                public void activated(ClientboundPackagePacket offer,ClientboundPackagePacket active,int candidate,int slot){}
                public void released(ClientboundPackagePacket offer,PackageAuthorityRegion.Baseline baseline){released[0]++;}
            });var channel=new PackageDeltaChannel(detector,capacity,19,23,new PackageDeltaJournal.Encoder(workerTasks::addLast,4),new PackageDeltaChannel.Transport(){
                public boolean send(long e,long r,long sequence,ByteBuffer bytes){throw new AssertionError("Revoked namespace sent an old worker result");}
                public void failed(String reason){throw new AssertionError(reason);}
            })) {
            acquisition.attachChannel(channel);cubeWorld(world,air,air,0,0,0);var env=physics.enableEnvironment(PackageGpuValidation::source);
            var unrelated=bodies(1);body(unrelated,0,12,12,12,1);unrelated.putFloat(16,1);physics.uploadFree(unrelated,1);
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            long frame=1;
            for(int phase=0;phase<3;phase++) {
                int action=phase==0?ClientboundPackagePacket.OFFER:phase==1?ClientboundPackagePacket.FINAL_BASELINE:ClientboundPackagePacket.ACTIVE;
                for(int i=0;i<count;i++)acquisition.receive(acquisitionPacket(template,action,100+i,900001+i,19,phase==0?1:2));
                acquisition.pump(256,(o,c)->true);acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,frame++,true);GL11.glFinish();acquisition.pump(256,(o,c)->true);
            }
            check(acquisition.activeCount()==count,"regional fixture failed admission");
            try(var view=world.view(0,0,0)){physics.stepFreeWorld(.05f,view,true,4);}
            acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,frame++,true);
            check(acquisition.captureCommitted(channel,frame-1),"regional fixture did not capture old flights");
            for(int i=0;i<5&&workerTasks.isEmpty();i++){GL11.glFinish();channel.pump(256);}
            check(!workerTasks.isEmpty(),"regional fixture did not hold an encoder task");
            check(env.capture(physics.freeCount()),"regional fixture did not hold an environment readback");
            acquisition.beginClose();check(channel.closed()&&!acquisition.replacementReady(),"namespace was not stopped before retirement");
            float previousY=readBuffer(physics.freeStateBuffer(),64).getFloat(4);long previousStep=physics.freeSimulationStep();
            for(int n=0;n<40&&!acquisition.replacementReady();n++) {
                int before=acquisition.activeCount();acquisition.pump(8,(o,c)->true);
                check(before-acquisition.activeCount()<=8,"regional retirement exceeded its transition budget");
                try(var view=world.view(0,0,0)){physics.stepFreeWorld(.05f,view,true,4);}
                acquisitionFrame(physics,pool,acquisition,particles,counter,0,0,0,frame++,true);GL11.glFinish();
                var state=readBuffer(physics.freeStateBuffer(),64);check(state.getFloat(4)<previousY&&state.getFloat(60)>=0,"regional reset interrupted unrelated motion");previousY=state.getFloat(4);
                check(physics.freeSimulationStep()==++previousStep,"regional reset rewound the global step counter");
                if(n<2)check(!acquisition.replacementReady(),"regional slots reused before old environment readback drained");
                else env.poll(raw->{throw new AssertionError("Air fixture reported an environment event");});
            }
            check(acquisition.replacementReady()&&released[0]==count&&physics.freeLiveCount()==1,"regional retirement leaked/duplicated lifecycle members");
            acquisition.close();channel.close();check(!channel.acknowledge(19,23,0),"old ACK entered a closed namespace");
            int reused=physics.nextFreeBody();check(reused==1,"retirement failed to preserve the unrelated body's stable slot");
            var replacement=bodies(1);body(replacement,0,7,8,9,1);physics.writeFree(reused,replacement,bodies(1));
            var before=readBuffer(physics.freeStateBuffer(),128);while(!workerTasks.isEmpty())workerTasks.removeFirst().run();channel.pump(256);
            check(before.equals(readBuffer(physics.freeStateBuffer(),128)),"late worker result overwrote a reused body");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }
    static void batchedAcquisitions() {
        int count=65,capacity=128;
        var region=new PackageRegion(-2,32,3);double ox=region.originX(),oy=region.originY(),oz=region.originZ();
        var template=freeCheckpoint(ClientboundPackagePacket.OFFER,region,1,
                new PackageLease.Pose(ox+8,oy+4,oz+8,0,0,0,15));
        var queue=new PackageControlQueue();var ns=new PackageControlQueue.Namespace(region,19,23);
        var visible=new HashSet<Long>();var slots=new HashSet<Integer>();
        var delivered=new HashMap<Integer,Set<Long>>();int[] packets={0};
        PackageControlQueue.Sender sender=message->{
            check(message.single()==null,"GPU group unexpectedly split into single controls");
            check(message.namespace().equals(ns),"GPU control namespace mismatch");
            var wire=ServerboundPackagePacket.controls(region,19,23,message.body());
            int rows=PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(wire.changes()),(action,index,id,generation,lease,revision)->{
                long member=id-0x1234567800000001L;
                check(member>=0&&member<count&&index==32768+member*3,"GPU control lost full identity/sparse index");
                check(generation==700001&&lease==13&&revision==(action==ServerboundPackagePacket.PREPARED?1:2),"GPU control lost handshake version");
                if(action==ServerboundPackagePacket.VISIBLE_READY)check(visible.contains(id),"GPU visible control preceded matching claim");
                check(delivered.computeIfAbsent(action,k->new HashSet<>()).add(id),"GPU control duplicated a phase identity");
            });
            check(rows==count,"GPU group dropped workgroup tail");packets[0]++;return true;
        };
        int particles=buffer(bodies(capacity)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackageMixedPhysicsGpu(capacity,0,2,PackageGpuValidation::source);
            var pool=new PackagePoolGpu(capacity,1,PackageGpuValidation::source);
            var detector=new PackageDeltaGpu(capacity,PackageGpuValidation::source);
            var acquisition=new PackageFreeAcquisitionGpu(region,19,23,ox,oy,oz,physics,pool,detector,
                    Map.of(template.model(),new PackageModelCache.Style(0,-1)),p->0xf000f0,new PackageFreeAcquisitionGpu.Transport(){
                public void control(int action,ClientboundPackagePacket p){queue.offer(ns,action,p.baseline(),0);}
                public void activated(ClientboundPackagePacket offer,ClientboundPackagePacket active,int candidate,int slot){
                    check(visible.add(active.baseline().identity().id())&&slot>0&&slots.add(slot),"GPU visible identity/slot duplicated");
                    queue.offer(ns,ServerboundPackagePacket.VISIBLE_READY,active.baseline(),0);
                }
                public void released(ClientboundPackagePacket offer,PackageAuthorityRegion.Baseline baseline){}
            });
            var channel=new PackageDeltaChannel(detector,capacity,19,23,new PackageDeltaJournal.Encoder(Runnable::run,4),new PackageDeltaChannel.Transport(){
                public boolean send(long e,long r,long sequence,ByteBuffer bytes){throw new AssertionError("Control fixture emitted pose delta");}
                public void failed(String reason){throw new AssertionError(reason);}
            })) {
            acquisition.attachChannel(channel);
            var ranges=BufferUtils.createByteBuffer(16);ranges.putInt(4,3).putFloat(8,1);
            pool.uploadMeshes(BufferUtils.createByteBuffer(3*48),ranges,1);
            for(int phase=0;phase<3;phase++) {
                int action=phase==0?ClientboundPackagePacket.OFFER:phase==1?ClientboundPackagePacket.FINAL_BASELINE:ClientboundPackagePacket.ACTIVE;
                for(int i=count-1;i>=0;i--)check(acquisition.receive(acquisitionPacket(template,action,32768+i*3,0x1234567800000001L+i,19,phase==0?1:2)),"GPU batch packet rejected");
                acquisition.pump(256,(o,c)->true);
                check(queue.stats().pending()==0&&packets[0]==phase,"GPU phase queued before admission");
                acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,phase*2+1,false);
                GL11.glFinish();acquisition.pump(256,(o,c)->true);
                check(queue.stats().pending()==0&&visible.size()==0,"aborted GPU frame published control/claim");
                acquisitionFrame(physics,pool,acquisition,particles,counter,ox,oy,oz,phase*2+2,true);
                check(queue.stats().pending()==0,"GPU submission bypassed fence polling");
                GL11.glFinish();acquisition.pump(256,(o,c)->true);
                check(queue.stats().pending()==count,"GPU fence lost a control at workgroup boundary");
                check(queue.flush(0,sender)==PackageControlQueue.Result.SENT,"GPU batch did not flush in its pump");
            }
            check(packets[0]==3&&visible.size()==count&&slots.size()==count&&acquisition.activeCount()==count,"GPU batch ownership incomplete");
            check(readBuffer(pool.commandBuffer(),16).getInt(4)==count,"GPU batch visible draw count mismatch");
        }finally{GL15.glDeleteBuffers(particles);GL15.glDeleteBuffers(counter);}
    }

    static void deltaOverflowAndIdentity() {
        int n=65;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var limited=gpu.capture(state,n,0,0,0,31);
            ByteBuffer header=readBuffer(limited.headerBuffer(),16);
            check(header.getInt(0)==65 && header.getInt(4)==31 && header.getInt(8)==34,"overflow counts not independently clamped");
            ByteBuffer first=captureRecords(limited);var second=gpu.capture(state,n,0,0,0,n);ByteBuffer rest=captureRecords(second);
            check(rest.remaining()==34*64,"overflowed candidates silently dropped");
            BitSet seen=new BitSet();for(var records:List.of(first,rest))for(int p=0;p<records.limit();p+=64) {
                int i=records.getInt(p+16);check(!seen.get(i),"overflow retry duplicates in-flight identity");seen.set(i);
            }
            check(seen.cardinality()==65,"overflow retry lost identities");
            acknowledge(gpu,limited,first);acknowledge(gpu,second,rest);gpu.finish(limited);gpu.finish(second);
            // Only velocity changes, even if the feet position is identical.
            b.putFloat(16,.25f);putBuffer(state,b);var changed=gpu.capture(state,n,0,0,0,n);ByteBuffer old=captureRecords(changed);
            check(old.remaining()==64 && old.getInt(24)==2,"velocity-only change was omitted");gpu.finish(changed);
            deltaMeta(meta,0,2);gpu.upload(meta,baseline,n);var replacement=gpu.capture(state,n,0,0,0,n);
            ByteBuffer current=captureRecords(replacement);gpu.acknowledge(replacement.stamp(),old);
            var pending=gpu.capture(state,n,0,0,0,n);check(captureRecords(pending).remaining()==0,"old generation modified replacement flight");gpu.finish(pending);
            gpu.acknowledge(changed.stamp(),old);acknowledge(gpu,replacement,current);gpu.finish(replacement);
            var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"valid generation ACK did not commit");gpu.finish(clean);
            // Refused shader rebuild preserves the entire previous program set.
            boolean failed=false;try{gpu.rebuild(name->name.endsWith("delta_finalize.comp")?"invalid shader":source(name));}catch(RuntimeException expected){failed=true;}
            check(failed,"invalid delta rebuild accepted");
            b.putFloat(60,-1);putBuffer(state,b);var release=gpu.capture(state,n,0,0,0,n);var event=captureRecords(release);
            check(event.remaining()==64 && event.getInt(28)==1,"solver fallback did not emit ownership release");
            gpu.cancel(release);release=gpu.capture(state,n,0,0,0,n);event=captureRecords(release);
            check(event.remaining()==64 && event.getInt(28)==1,"unacknowledged release event was lost");
            acknowledge(gpu,release,event);gpu.finish(release);
            var retired=gpu.capture(state,n,0,0,0,n);check(captureRecords(retired).remaining()==0,"acknowledged release candidate remained active");gpu.finish(retired);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void deltaYawTies() {
        int n=129;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++) {
            body(b,i,1,2,3,1);deltaMeta(meta,i,1);
            b.putFloat(i*64+44,(float)((i-64+.5)*(360.0/65536)));
        }
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var capture=gpu.capture(state,n,0,0,0,n);var out=captureRecords(capture);
            check(out.remaining()==n*64,"yaw tie candidate missing");
            for(int p=0;p<out.limit();p+=64) {
                int i=out.getInt(p+16),expected=quantizedBody(b,i).yaw();
                check(out.getInt(p+60)==expected,"Java yaw tie "+i+": GPU "+out.getInt(p+60)+", expected "+expected);
            }
            gpu.cancel(capture);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void deltaRelativeBaselines() {
        for(boolean predicted:new boolean[]{false,true}) {
        int n=65;var b=bodies(n);var meta=BufferUtils.createByteBuffer(n*32);var baseline=BufferUtils.createByteBuffer(n*32);
        var initial=new PackageDeltaCodec.Quantized[n];var sent=new PackageDeltaCodec.Quantized[n];
        for(int i=0;i<n;i++) {
            body(b,i,2+(i%8)*4,3+(i/8)*2,4+(i%5)*3,1);deltaMeta(meta,i,1);var q=quantizedBody(b,i);initial[i]=q;
            int p=i*32;baseline.putInt(p,q.x()).putInt(p+4,q.y()).putInt(p+8,q.z()).putInt(p+12,q.flags())
                    .putInt(p+16,q.vx()).putInt(p+20,q.vy()).putInt(p+24,q.vz()).putInt(p+28,q.yaw());
            b.putFloat(i*64,b.getFloat(i*64)+(i%2==0?.125f:-.125f));
            b.putFloat(i*64+8,b.getFloat(i*64+8)-.25f);b.putFloat(i*64+16,i%3==0?-.5f:0);b.putFloat(i*64+44,i%4==0?-99:0);
            sent[i]=quantizedBody(b,i);
        }
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source,true,predicted)) {
            gpu.upload(meta,baseline,n);var first=gpu.capture(state,n,0,0,0,31);var a=captureRecords(first);
            var second=gpu.capture(state,n,0,0,0,n);var c=captureRecords(second);var seen=new BitSet();
            for(var records:List.of(a,c))for(int p=0;p<records.limit();p+=64) {
                int i=records.getInt(p+16);check(!seen.get(i),"relative overflow duplicate");seen.set(i);
                check(records.getInt(p+32)==sent[i].x()-initial[i].x() && records.getInt(p+36)==sent[i].y()-initial[i].y()
                        && records.getInt(p+40)==sent[i].z()-initial[i].z(),"GPU residual differs from confirmed baseline");
                check(records.getInt(p+48)==sent[i].vx() && records.getInt(p+60)==sent[i].yaw(),"relative encoding changed absolute velocity/yaw");
            }
            check(seen.cardinality()==n,"relative overflow lost identity");
            // Bodies keep moving while both captured baselines remain pinned.
            for(int i=0;i<n;i++)b.putFloat(i*64,b.getFloat(i*64)+.5f);putBuffer(state,b);
            acknowledge(gpu,first,a);acknowledge(gpu,second,c);gpu.finish(first);gpu.finish(second);
            var next=gpu.capture(state,n,0,0,0,n);var pending=captureRecords(next);check(pending.remaining()==n*64,"relative ACK swallowed newer motion");
            for(int p=0;p<pending.limit();p+=64) {
                int i=pending.getInt(p+16);
                check(pending.getInt(p+32)==2048-(predicted?sent[i].x()-initial[i].x():0)
                        && pending.getInt(p+36)==-(predicted?sent[i].y()-initial[i].y():0)
                        && pending.getInt(p+40)==-(predicted?sent[i].z()-initial[i].z():0),
                        "relative/predicted ACK used current bodies or wrong predictor");
            }
            acknowledge(gpu,first,a); // duplicate OLD stamp must not retire the new flight
            var blocked=gpu.capture(state,n,0,0,0,n);check(captureRecords(blocked).remaining()==0,"old ACK cleared a new relative flight");gpu.finish(blocked);
            boolean failed=false;try{gpu.rebuild(name->name.endsWith("delta_finalize.comp")?"invalid shader":source(name));}catch(RuntimeException expected){failed=true;}
            check(failed,"relative shader failure accepted");
            acknowledge(gpu,next,pending);gpu.finish(next);
            var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"relative bundle/ACK did not restore absolute baseline");gpu.finish(clean);
        }finally{GL15.glDeleteBuffers(state);}
        }
    }
    static void deltaPredictedMotion() {
        for(int n:new int[]{1,63,64,65,131072}) {
            var b=bodies(n);var meta=BufferUtils.createByteBuffer(n*32);var baseline=BufferUtils.createByteBuffer(n*32);
            var confirmed=new PackageDeltaCodec.Quantized[n];var initial=new PackageDeltaCodec.Quantized[n];var moves=new int[n*3];
            for(int i=0;i<n;i++) {
                body(b,i,16,16,16,1);deltaMeta(meta,i,1);var q=quantizedBody(b,i);initial[i]=confirmed[i]=q;
                int p=i*32;baseline.putInt(p,q.x()).putInt(p+4,q.y()).putInt(p+8,q.z()).putInt(p+12,q.flags());
            }
            int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source,true,true)) {
                gpu.upload(meta,baseline,n);
                ByteBuffer stale=null;PackageDeltaGpu.Capture staleCapture=null;
                for(int wave=0;wave<6;wave++) {
                    for(int i=0;i<n;i++) {
                        // Some members change only independent fields; the POSITION predictor
                        // must survive those ACKs. Others move different integer displacements.
                        if(i%7!=wave)for(int axis=0;axis<3;axis++) {
                            int p=i*64+axis*4,delta=(i*31+axis*19+wave*7)%65-32;
                            b.putFloat(p,b.getFloat(p)+delta/4096f);
                        }
                        b.putFloat(i*64+16,wave%2==0?.25f:-.25f);
                    }
                    putBuffer(state,b);
                    var a=gpu.capture(state,n,0,0,0,n/2);var rawA=captureRecords(a);
                    var c=gpu.capture(state,n,0,0,0,n);var rawC=captureRecords(c);
                    if(wave==2) {
                        // Cancellation discards only flight; no prediction state may advance.
                        gpu.cancel(a);gpu.cancel(c);a=gpu.capture(state,n,0,0,0,n);rawA=captureRecords(a);
                        c=gpu.capture(state,n,0,0,0,n);rawC=captureRecords(c);
                    }
                    if(stale!=null)acknowledge(gpu,staleCapture,stale);
                    var seen=new BitSet();
                    for(var raw:List.of(rawA,rawC))for(int p=0;p<raw.limit();p+=64) {
                        int i=raw.getInt(p+16),mask=raw.getInt(p+24);check(!seen.get(i),"predicted overflow duplicate");seen.set(i);
                        var q=quantizedBody(b,i);var old=confirmed[i];
                        check(raw.getInt(p+32)==q.x()-old.x()-moves[i*3] && raw.getInt(p+36)==q.y()-old.y()-moves[i*3+1]
                                && raw.getInt(p+40)==q.z()-old.z()-moves[i*3+2],"wire prediction advanced on cancel/old ACK/independent fields");
                        var wire=new PackageDeltaCodec.Quantized(raw.getInt(p+32),raw.getInt(p+36),raw.getInt(p+40),
                                (short)raw.getInt(p+48),(short)raw.getInt(p+52),(short)raw.getInt(p+56),(short)raw.getInt(p+60),raw.getInt(p+44));
                        check(PackageDeltaCodec.mergePredictedPosition(old,new PackageDeltaCodec.Entry(i,mask,wire),moves[i*3],moves[i*3+1],moves[i*3+2]).equals(q),
                                "GPU predictor and exact Java decoder disagree");
                        if((mask&1)!=0){moves[i*3]=q.x()-old.x();moves[i*3+1]=q.y()-old.y();moves[i*3+2]=q.z()-old.z();}
                        confirmed[i]=q;
                    }
                    check(seen.cardinality()==n,"predicted full-capacity capture lost members");
                    var flight=gpu.capture(state,n,0,0,0,n);check(captureRecords(flight).remaining()==0,"old prediction ACK cleared a new flight");gpu.finish(flight);
                    acknowledge(gpu,a,rawA);acknowledge(gpu,c,rawC);stale=rawA;staleCapture=a;gpu.finish(a);gpu.finish(c);
                    var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"prediction ACK changed exact absolute baseline");gpu.finish(clean);
                    if(wave==3)gpu.rebuild(PackageGpuValidation::source); // histories/flights survive successful replacement
                }
                gpu.upload(meta,baseline,n); // new initialization must not retain the previous predictor
                var reset=gpu.capture(state,n,0,0,0,n);var raw=captureRecords(reset);check(raw.remaining()==n*64,"prediction reset fixture lost dirty state");
                for(int p=0;p<raw.limit();p+=64){int i=raw.getInt(p+16);var q=quantizedBody(b,i);
                    check(raw.getInt(p+32)==q.x()-initial[i].x() && raw.getInt(p+36)==q.y()-initial[i].y() && raw.getInt(p+40)==q.z()-initial[i].z(),"epoch initialization retained displacement");}
                gpu.cancel(reset);
            }finally{GL15.glDeleteBuffers(state);}
        }
    }
    static void deltaQuantizationLimits() {
        int n=8;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){body(b,i,1,2,3,1);deltaMeta(meta,i,1);}
        b.putFloat(0,Math.nextDown(64f)); // crossing must carry its new position, not release
        b.putFloat(64+16,1600).putFloat(64+20,1600); // each component fits, speed exceeds 2048 blocks/s
        b.putFloat(128+16,4096); // negotiated velocity overflow, never saturated
        b.putFloat(192+44,-999999.9375f);
        b.putFloat(256+44,999999.9375f);
        b.putFloat(320+16,-.5f/PackageDeltaCodec.VELOCITY_SCALE).putFloat(320+20,.5f/PackageDeltaCodec.VELOCITY_SCALE); // Java's signed round ties
        b.putFloat(384,1+.5f/4096).putFloat(384+8,1+1.5f/4096); // ties-to-even positions
        meta.putInt(7*32+16,n); // invalid body index must not be dereferenced
        int state=buffer(b);
        try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
            gpu.upload(meta,baseline,n);var capture=gpu.capture(state,n,0,0,0,n);var out=captureRecords(capture);
            check(out.remaining()==n*64,"quantization boundary record missing");
            for(int p=0;p<out.limit();p+=64) {
                int i=out.getInt(p+16);boolean release=i==1 || i==2 || i==7;
                check(out.getInt(p+28)==(release?1:0),"out-of-range candidate did not pause individually");
                if(!release) {
                    var q=quantizedBody(b,i);
                    check(out.getInt(p+32)==q.x() && out.getInt(p+40)==q.z(),"position ties-to-even mismatch");
                    check(out.getInt(p+48)==q.vx() && out.getInt(p+52)==q.vy() && out.getInt(p+60)==q.yaw(),"signed velocity/large yaw rounding mismatch");
                }
            }
            gpu.cancel(capture);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static ByteBuffer readBuffer(int id,int bytes) {
        ByteBuffer result=BufferUtils.createByteBuffer(bytes);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,result);return result;
    }
    static final class ChannelTransport implements PackageDeltaChannel.Transport {
        PackageDeltaChannel channel;
        final BitSet seen=new BitSet();
        final List<Long> waiting=new ArrayList<>();
        Map<Long,BitSet> packetMembers;
        final PackageAuthorityRegion server;
        int records,failures,releases,notices;
        boolean accepted=true,automaticAck=true;
        boolean packed;
        boolean relative;
        boolean predicted;
        public boolean batchEncoded(){return packed;}
        public boolean relativePositions(){return relative;}
        public boolean predictedPositions(){return predicted;}
        long last=-1;
        ChannelTransport(PackageAuthorityRegion server){this.server=server;}
        public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes) {
            if(!accepted)return false;
            check(epoch==77 && revision==3,"channel lost namespace");check(sequence>last,"wire packet order regressed");last=sequence;
            check(bytes.remaining()<=24576,"wire packet exceeded negotiated limit");
            var changes=packed?PackageBatchDeltaCodec.decode(bytes):PackageDeltaCodec.decode(bytes);check(!bytes.hasRemaining(),"wire body trailing bytes");
            if(server!=null)check((predicted?server.deltaPredicted(new UUID(7,9),epoch,revision,sequence,0,changes,4):relative?server.deltaRelative(new UUID(7,9),epoch,revision,sequence,0,changes,4)
                    :server.delta(new UUID(7,9),epoch,revision,sequence,0,changes,4))==PackageAuthorityRegion.Result.ACCEPTED,"actual server rejected channel delta");
            for(var change:changes){seen.set(change.id());records++;if(change.mask()==16)releases++;}
            if(packetMembers!=null){var membership=new BitSet();for(var change:changes)membership.set(change.id());packetMembers.put(sequence,membership);}
            if(automaticAck)check(channel.acknowledge(epoch,revision,sequence),"loopback ACK rejected");else waiting.add(sequence);
            return true;
        }
        public void failed(String reason){failures++;}
        public void released(int localId,long id,long generation){notices++;}
    }
    static final class ChannelTarget implements PackageAuthorityRegion.Target {
        final PackageLease.Identity identity;
        PackageAuthorityRegion.Snapshot state;
        int releases;
        ChannelTarget(int i){identity=new PackageLease.Identity(0x1234567800000001L+i,1);state=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(1,2.5,4,0,0,0,0),1);}
        public PackageLease.Identity identity(){return identity;}
        public PackageAuthorityRegion.Snapshot snapshot(){return state;}
        public boolean eligible(){return true;}
        public void apply(PackageAuthorityRegion.Snapshot next){state=next;}
        public void released(PackageAuthorityRegion.Baseline baseline){releases++;}
    }
    static void channelRoundTrip() {
        for(int wireMode:new int[]{0,1,2,3})for(int n:new int[]{65,131072}) {
            var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            PackageAuthorityRegion server=n==65?new PackageAuthorityRegion(new PackageRegion(0,0,0),new UUID(7,9),77,3,0):null;
            ChannelTarget[] targets=n==65?new ChannelTarget[n]:null;
            for(int i=0;i<n;i++) {
                body(b,i,2,3,4,1);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);
                if(server!=null) {
                    var target=new ChannelTarget(i);targets[i]=target;var offered=server.offer(target,0);
                    var prepared=server.prepared(new UUID(7,9),77,offered.index(),target.identity,offered.leaseEpoch(),offered.revision(),0);
                    check(server.finalReady(new UUID(7,9),77,prepared.index(),target.identity,prepared.leaseEpoch(),prepared.revision(),0),"channel server baseline ready");
                    // Relative records must start with the SAME confirmed per-member baseline
                    // as the server, not the old absolute fixture's deliberately zero baseline.
                    if(wireMode>=2)baseline.putInt(i*32,4096).putInt(i*32+4,10240).putInt(i*32+8,16384).putInt(i*32+12,1);
                }
            }
            var transport=new ChannelTransport(server);transport.packed=wireMode!=0;transport.relative=wireMode>=2;transport.predicted=wireMode==3;transport.automaticAck=wireMode<2;int state=buffer(b);
            var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source,wireMode>=2,wireMode==3);
            try(var channel=new PackageDeltaChannel(gpu,n,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,clock::get,true)) {
                transport.channel=channel;channel.append(meta,baseline,n);check(channel.capture(state,n,0,0,0),"channel initial capture");
                for(int frame=0;frame<12;frame++) {
                    GL11.glFinish();channel.pump(256);
                    check(!channel.closed(),"deferred worker blocked/closed within processing budget");
                    check(tasks.size()<=4,"encoder work exceeded global budget");
                }
                check(transport.records==0,"unfinished encoder published records");
                // Work can finish out of order; transport still must publish increasing sequences.
                for(int frame=0;frame<20 && channel.stats().ackedPackets()<(n+511)/512;frame++) {
                    while(!tasks.isEmpty())tasks.removeLast().run();GL11.glFinish();channel.pump(256);
                    if(wireMode>=2 && transport.waiting.size()==(n+511)/512) {
                        var builder=new PackageAckRanges.Builder();for(long sequence:transport.waiting)check(builder.add(sequence),"ACK batch unexpected bound");
                        check(ackWire(channel,77,3,builder.snapshot())==transport.waiting.size(),"wire ACK batch lost a sent sequence");transport.waiting.clear();
                    }
                }
                check(!channel.closed() && transport.records==n && transport.seen.cardinality()==n,"fragmented full-capacity journal lost records");
                check(channel.stats().payloadBytes()==(long)n*64,"readback copied unused capacity");
                check(channel.stats().ackedPackets()==(n+511)/512,"ACK journal did not retire full capture");
                if(n==131072)check(channel.stats().ackDispatches()<=16,"ACK coalescing regressed to per-packet GL dispatch");
                var clean=gpu.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"server ACK did not advance GPU baseline");gpu.finish(clean);
                check(!channel.acknowledge(76,3,0) && !channel.acknowledge(77,4,0) && !channel.acknowledge(77,3,99999),"wrong namespace/unknown ACK accepted");
                if(server!=null) {
                    for(var target:targets)check(target.state.pose().x()==2,"end-to-end server pose mismatch");
                    transport.automaticAck=false;transport.seen.clear();
                    b.putFloat(60,-1);b.putFloat(64+16,.25f);putBuffer(state,b);
                    check(channel.capture(state,n,0,0,0),"mixed release capture");
                    for(int frame=0;frame<8 && transport.waiting.isEmpty();frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                    check(transport.releases==1 && targets[0].releases==1,"GPU release did not commit exactly once");
                    check(targets[1].state.pose().vx()==.25,"velocity-only state lost beside release");
                    check(channel.released(77,new PackageAuthorityRegion.Baseline(0,targets[0].identity,1,2,targets[0].state)),"server ownership notice rejected");
                    long sequence=transport.waiting.getFirst();check(channel.acknowledge(77,3,sequence),"delayed valid ACK rejected");channel.pump(8);
                    var noDuplicate=gpu.capture(state,n,0,0,0,n);check(captureRecords(noDuplicate).remaining()==0,"release/pose ACK repeated dirty state");gpu.finish(noDuplicate);
                    check(transport.notices==1,"authority release callback lost");
                    channel.released(77,new PackageAuthorityRegion.Baseline(0,targets[0].identity,1,2,targets[0].state));channel.pump(8);
                    check(transport.notices==1,"duplicate terminal notification repeated lifecycle callback");
                }
            }finally{GL15.glDeleteBuffers(state);}
            check(transport.failures==0,"healthy channel called fallback");
        }
    }
    static int ackWire(PackageDeltaChannel channel,long epoch,long revision,PackageAckRanges ranges) {
        var packet=new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageAckPacket(
                ResourceLocation.parse("minecraft:overworld"),new PackageRegion(0,0,0),epoch,revision,ranges);
        var bytes=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var codec=com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageAckPacket.STREAM_CODEC;
            codec.encode(bytes,packet);var received=codec.decode(bytes);check(bytes.readableBytes()==0,"ACK batch wire tail");
            return channel.acknowledge(received.epoch(),received.revision(),received.ranges());
        }finally{bytes.release();}
    }
    static void channelBatchAckHoles() {
        int n=1025;var b=bodies(n);var meta=BufferUtils.createByteBuffer(n*32);var baseline=BufferUtils.createByteBuffer(n*32);
        for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);}
        int state=buffer(b);var transport=new ChannelTransport(null);transport.automaticAck=false;transport.packed=true;transport.relative=true;
        transport.packetMembers=new HashMap<>();
        var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source,true);
        try(var channel=new PackageDeltaChannel(gpu,n,77,3,new PackageDeltaJournal.Encoder(Runnable::run,4),transport,()->0)) {
            transport.channel=channel;channel.append(meta,baseline,n);check(channel.capture(state,n,0,0,0),"batch ACK initial capture");
            for(int frame=0;frame<12 && transport.waiting.size()!=3;frame++){GL11.glFinish();channel.pump(8);}
            check(transport.waiting.equals(List.of(0L,1L,2L)),"batch ACK wire fixture incomplete");
            var sparse=new PackageAckRanges(0,0,2,2);
            check(ackWire(channel,76,3,sparse)==0 && ackWire(channel,77,4,sparse)==0,"wrong ACK batch namespace accepted");
            check(ackWire(channel,77,3,sparse)==2,"sparse ACK batch rejected");channel.pump(8);
            check(channel.stats().ackedPackets()==2,"ACK hole became a cumulative watermark");
            // GPU workgroups reserve output ranges in unspecified order. Sequence membership
            // comes from the actual encoded packet, never from candidate / 512 arithmetic.
            var acknowledged=(BitSet)transport.packetMembers.get(0L).clone();acknowledged.or(transport.packetMembers.get(2L));
            for(int i=0;i<n;i++)b.putFloat(i*64,2.25f);putBuffer(state,b);
            var changed=gpu.capture(state,n,0,0,0,n);var records=captureRecords(changed);
            check(records.remaining()==513*64,"unacknowledged hole released its GPU flight");
            for(int p=0;p<records.limit();p+=64){int candidate=records.getInt(p+16);check(acknowledged.get(candidate),"hole ACK altered a baseline");}
            gpu.cancel(changed);
            check(ackWire(channel,77,3,new PackageAckRanges(0,0,3,100))==0,"old/unsent ACK sequences entered mailbox");
            check(ackWire(channel,77,3,new PackageAckRanges(1,1))==1,"exact missing ACK rejected");channel.pump(8);
            check(channel.stats().ackedPackets()==3,"missing ACK did not retire journal");
            changed=gpu.capture(state,n,0,0,0,n);records=captureRecords(changed);check(records.remaining()==n*64,"late ACK lost later physical changes");
            for(int p=0;p<records.limit();p+=64)check(records.getInt(p+32)==1024,"ACK batch did not preserve exact residual baseline");gpu.cancel(changed);
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void channelLifecycle() {
        ByteBuffer b=bodies(2),meta=BufferUtils.createByteBuffer(64),baseline=BufferUtils.createByteBuffer(64);
        for(int i=0;i<2;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}
        int state=buffer(b);var tasks=new ArrayDeque<Runnable>();var clock=new java.util.concurrent.atomic.AtomicLong();
        var transport=new ChannelTransport(null);var gpu=new PackageDeltaGpu(2,PackageGpuValidation::source);
        try(var channel=new PackageDeltaChannel(gpu,2,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,clock::get)) {
            transport.channel=channel;
            var first=meta.duplicate().position(0).limit(32);var firstBase=baseline.duplicate().position(0).limit(32);
            channel.append(first,firstBase,1);check(channel.capture(state,2,0,0,0),"first append capture");GL11.glFinish();channel.pump(4);
            var next=meta.duplicate().position(32).limit(64);var nextBase=baseline.duplicate().position(32).limit(64);
            channel.append(next,nextBase,1);check(channel.capture(state,2,0,0,0),"append reset existing flights");
            for(int frame=0;frame<8 && channel.stats().ackedPackets()<2;frame++){GL11.glFinish();channel.pump(4);while(!tasks.isEmpty())tasks.remove().run();}
            check(transport.records==2 && channel.stats().ackedPackets()==2,"append during immutable readback lost or duplicated candidate");
            b.putFloat(0,3);putBuffer(state,b);channel.capture(state,2,0,0,0);
            clock.set(100_000_000L+1);GL11.glFinish();channel.pump(4);
            check(!channel.closed() && transport.failures==0,"worker delay revoked durable package authority");
            for(int frame=0;frame<8&&channel.stats().ackedPackets()<3;frame++){GL11.glFinish();channel.pump(4);while(!tasks.isEmpty())tasks.remove().run();}
            check(transport.records==3&&channel.stats().ackedPackets()==3,"delayed processing lost the pending update");
            channel.close();check(!channel.acknowledge(77,3,0),"old epoch ACK survived closed channel");
            channel.pump(4);check(transport.failures==0,"closing a healthy journal emitted failure");
        }finally{GL15.glDeleteBuffers(state);}
    }
    static void channelImmutableAndPartialTransport() {
        for(boolean batched:new boolean[]{false,true}) {
            int n=1025;ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);}
            int state=buffer(b);var tasks=new ArrayDeque<Runnable>();var time=new java.util.concurrent.atomic.AtomicLong();
            var transport=new ChannelTransport(null);transport.accepted=false;
            var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source);
            try(var channel=new PackageDeltaChannel(gpu,n,77,3,new PackageDeltaJournal.Encoder(tasks::add,4),transport,time::get,batched)) {
                transport.channel=channel;channel.append(meta,baseline,n);channel.capture(state,n,0,0,0);
                for(int frame=0;frame<5;frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                check(transport.records==0 && !channel.acknowledge(77,3,0),"unpublished transport packet accepted ACK");
                // The actual bodies move while their old immutable records wait for transport.
                for(int i=0;i<n;i++)b.putFloat(i*64,3);putBuffer(state,b);
                check(channel.capture(state,n,0,0,0),"in-flight detector prevented simulation");
                for(int frame=0;frame<5;frame++){GL11.glFinish();channel.pump(8);}
                check(transport.records==0 && !channel.closed(),"backpressure lost or published queued records");
                transport.accepted=true;
                for(int frame=0;frame<5 && channel.stats().ackedPackets()<3;frame++){GL11.glFinish();channel.pump(8);}
                check(channel.stats().ackedPackets()==3 && transport.records==n,"transport retry lost/duplicated capture");
                check(channel.stats().ackDispatches()==(batched?1:3),"ACK batching command count");
                var newer=gpu.capture(state,n,0,0,0,n);var records=captureRecords(newer);
                check(records.remaining()==n*64,"old capture ACK acknowledged newer unsubmitted bodies");
                for(int p=0;p<records.limit();p+=64)check(records.getInt(p+32)==12288,"newer dirty state was overwritten by readback");
                gpu.cancel(newer);
                var stale=new PackageAuthorityRegion.Baseline(0,new PackageLease.Identity(0x1234567800000001L,2),1,2,
                        new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(2,2.5,4,0,0,0,0),0));
                channel.released(77,stale);channel.pump(8);
                check(transport.notices==0,"stale lifecycle generation released live package");
                var afterNotice=gpu.capture(state,n,0,0,0,n);check(captureRecords(afterNotice).remaining()==n*64,"stale terminal notice disabled live identity");gpu.cancel(afterNotice);
                check(channel.capture(state,n,0,0,0),"wrapped journal capture");
                for(int frame=0;frame<8 && channel.stats().ackedPackets()<6;frame++){GL11.glFinish();channel.pump(8);while(!tasks.isEmpty())tasks.remove().run();}
                check(channel.stats().ackedPackets()==6 && transport.records==2*n,"wrapped journal lost/duplicated records");
                var wrapped=gpu.capture(state,n,0,0,0,n);check(captureRecords(wrapped).remaining()==0,"GPU journal wrapped ACK range corrupted baseline");gpu.finish(wrapped);
                channel.capture(state,n,0,0,0);channel.close(); // No fence wait; invalidate unread source banks.
                check(!channel.acknowledge(77,3,0) && !channel.released(77,stale),"closed epoch accepted terminal mailbox data");
            }finally{GL15.glDeleteBuffers(state);}
            check(transport.failures==0,"partial transport triggered unwarranted fallback");
        }
    }
    static void putBuffer(int id,ByteBuffer bytes) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes);
    }
    static void chainParentFrames() {
        for(int n:new int[]{0,1,63,64,65,131072}) {
            int capacity=Math.max(1,n);var initial=BufferUtils.createByteBuffer(capacity*64+128);
            for(int j=capacity*64;j<initial.capacity();j++)initial.put(j,(byte)0x5a);
            int particles=buffer(initial),nextParticles=buffer(initial),counter=buffer(BufferUtils.createByteBuffer(16));
            var state=bodies(capacity);var chains=bodies(capacity);var history=BufferUtils.createByteBuffer(capacity*32);
            var metadata=BufferUtils.createByteBuffer(n*80);var kinds=new int[n];int boxes=0,rigs=0,firstFramed=-1;
            for(int i=0;i<n;i++) {
                // Full-capacity case is entirely active, valid framed chains. Smaller cases
                // mix ordinary free bodies, static chains, framed chains and missing tracks.
                int kind=n==1||n==131072?2:i%4;kinds[i]=kind;
                int flags=kind==0?0:PackagePoolGpu.CHAIN;
                if(kind>=2)flags|=PackagePoolGpu.FRAMED|PackagePoolGpu.FLIPPED;
                body(state,i,i*3,2.5f,-.25f,1);state.putFloat(i*64+44,47);
                int c=i*64;
                chains.putFloat(c+12,kind==3?1:0).putFloat(c+32,42).putFloat(c+36,90).putFloat(c+40,3).putFloat(c+44,1)
                        .putFloat(c+48,i*3+.5f).putFloat(c+52,3).putFloat(c+56,-.125f).putFloat(c+60,47);
                int h=i*32;
                history.putFloat(h,i*3-.125f).putFloat(h+4,2.25f).putFloat(h+8,-.5f).putFloat(h+12,43)
                        .putFloat(h+16,i*3+.25f).putFloat(h+20,2.75f).putFloat(h+24,-.375f).putFloat(h+28,43);
                int m=i*80;
                metadata.putLong(m,0x100000001L+i).putLong(m+8,0x200000003L).putInt(m+16,i).putInt(m+20,0).putInt(m+24,1).putInt(m+28,flags)
                        .putFloat(m+56,23/16f).putInt(m+60,0xf000f0);
                if(kind!=3){boxes++;if(kind!=0)rigs++;}
                if(kind==2&&firstFramed<0)firstFramed=i;
            }
            var localOrigin=new net.minecraft.world.phys.Vec3(1000000.5,Integer.MAX_VALUE-64.5,-1000000.5);
            var parent=UUID.randomUUID();
            var renderFrame=new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainSpace.Frame(parent,localOrigin,
                    new net.minecraft.world.phys.Vec3(39,62,-43),new net.minecraft.world.phys.Vec3(0,0,-2),new net.minecraft.world.phys.Vec3(0,3,0),new net.minecraft.world.phys.Vec3(4,0,0));
            var logicalFrame=new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainSpace.Frame(parent,localOrigin,
                    new net.minecraft.world.phys.Vec3(82,70,-38),new net.minecraft.world.phys.Vec3(1.5/Math.sqrt(2),1.5/Math.sqrt(2),0),
                    new net.minecraft.world.phys.Vec3(-Math.sqrt(2),Math.sqrt(2),0),new net.minecraft.world.phys.Vec3(0,0,.5));
            var frameBytes=BufferUtils.createByteBuffer(96);
            new com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainGpuFrame(renderFrame,logicalFrame).write(frameBytes,32,64,-48);
            int bodies=buffer(state),links=buffer(chains),previous=buffer(history),parentRows=buffer(frameBytes),tiny=buffer(BufferUtils.createByteBuffer(16));
            try(var frameGpu=new PackageChainFramesGpu(capacity,PackageGpuValidation::source);
                var bridge=new PackagePoolGpu(capacity,2,PackageGpuValidation::source);
                var queries=new PackagePoseQueryGpu(capacity,511,PackageGpuValidation::source)) {
                var ranges=BufferUtils.createByteBuffer(32).putInt(4,3).putFloat(8,1).putInt(16,3).putInt(20,3).putFloat(24,1);
                bridge.uploadMeshes(BufferUtils.createByteBuffer(6*48),ranges,2);bridge.uploadMetadata(metadata,n);
                bridge.source(bodies,links,previous,n,32,64,-48);
                if(firstFramed>=0)frameGpu.append(0,0,0,0,0);
                var initialFrames=frameGpu.view(frameBytes,1);int frames=initialFrames.buffer();bridge.chainFrames(initialFrames);
                bridge.stage(particles,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==boxes,"framed chains did not share/cap ordinary slots "+n);
                ByteBuffer admitted=readBuffer(bridge.admissionBuffer(),capacity*32),pool=readBuffer(particles,initial.capacity());
                var attachment=readBuffer(bridge.attachmentBuffer(),capacity*PackagePoolGpu.ATTACHMENT_BYTES);var slots=new BitSet(capacity);
                var commands=readBuffer(bridge.commandBuffer(),32);
                check(commands.getInt(4)==boxes&&commands.getInt(20)==rigs,"framed box/rig grouping "+n);
                for(int i=0;i<n;i++) {
                    int a=i*32,slot=admitted.getInt(a+16)-1,kind=kinds[i];
                    check(admitted.getLong(a)==0x100000001L+i&&admitted.getLong(a+8)==0x200000003L,"framed stable admission identity");
                    if(kind==3){check(slot==-1,"missing chain parent silently became world space");continue;}
                    check(slot>=0&&slot<capacity&&!slots.get(slot),"framed duplicate/out-of-capacity slot");slots.set(slot);
                    int p=slot*64;
                    for(int j=0;j<3;j++) {
                        float lx=j==0?i*3:j==1?i*3-.125f:i*3+.5f;
                        float ly=j==0?2.5f:j==1?2.25f:3,lz=j==0?-.25f:j==1?-.5f:-.125f;
                        if(kind==0){if(j==2){ly=2.5f;lz=-.25f;lx=i*3;}else ly-=.5f;}
                        float x=kind==2?39+4*lz:32+lx,y=kind==2?62+3*ly:64+ly,z=kind==2?-43-2*lx:-48+lz;
                        check(pool.getFloat(p+j*16)==x&&pool.getFloat(p+j*16+4)==y&&pool.getFloat(p+j*16+8)==z,"framed current/history/target world projection");
                    }
                    check((pool.getInt(p+44)>>>24)==admitted.getInt(a+20),"framed pool/admission flags diverged");
                    if(kind==2) {
                        int e=capacity*32+i*144;
                        for(int j=0;j<24;j++)check(attachment.getFloat(e+j*4)==frameBytes.getFloat(j*4),"framed render/logical rows not copied into committed extension");
                        check(attachment.getFloat(e+96)==i*3&&attachment.getFloat(e+112)==i*3-.125f&&attachment.getFloat(e+128)==i*3+.5f
                                &&attachment.getFloat(i*32)==i*3+.25f,"framed native local interpolation state lost");
                    }
                }
                check(slots.cardinality()==boxes,"framed accepted count disagreed with unique slots");
                for(int j=capacity*64;j<initial.capacity();j++)check(pool.get(j)==(byte)0x5a,"framed pool sentinel overwritten");
                if(firstFramed>=0) {
                    int i=firstFramed;
                    var input=new PackagePoseQueryGpu.Input(bodies,links,bridge.metadataBuffer(),bridge.admissionBuffer(),previous,n,n,capacity,bridge.attachmentBuffer());
                    var target=new net.minecraft.world.phys.Vec3(i*3+.5,3,-.125);
                    // Logical and render frames deliberately differ. Picking must project the
                    // logical native target cube, not the displayed world AABB or local axes.
                    var from=logicalFrame.world(localOrigin.add(target).add(-2,0,0)).subtract(32,64,-48);
                    var to=logicalFrame.world(localOrigin.add(target).add(2,0,0)).subtract(32,64,-48);
                    var ray=new PackagePoseQueryGpu.Ray((float)from.x,(float)from.y,(float)from.z,(float)(to.x-from.x),(float)(to.y-from.y),(float)(to.z-from.z));
                    var results=new ArrayList<PackagePoseQueryGpu.Completed>();
                    check(queries.pick(input,ray,1,"logical"),"framed logical pick unavailable");GL11.glFinish();queries.poll(results::add);
                    var pick=results.removeFirst().results().getFirst();
                    check(pick.present()&&pick.id()==0x100000001L+i&&pick.candidate()==i&&pick.track()==0&&pick.x()==i*3&&pick.tx()==i*3+.5f,
                            "framed pick lost logical geometry or native local recovery coordinates");
                    for(int missing:new int[]{0,tiny}) {
                        var absent=new PackagePoseQueryGpu.Input(bodies,links,bridge.metadataBuffer(),bridge.admissionBuffer(),previous,n,n,capacity,missing);
                        check(queries.pick(absent,ray,1,"no frame"),"missing-frame query not submitted");GL11.glFinish();queries.poll(results::add);
                        check(!results.removeFirst().results().getFirst().present(),"missing/short query frame read as valid world pose");
                    }
                    if(n==1) {
                        var corner=logicalFrame.world(localOrigin.add(target)).subtract(32,64,-48).add(1.3,1.3,-1);
                        var falseHit=new PackagePoseQueryGpu.Ray((float)corner.x,(float)corner.y,(float)corner.z,0,0,2);
                        check(queries.pick(input,falseHit,1,"world AABB corner"),"framed rotated-box miss query unavailable");GL11.glFinish();queries.poll(results::add);
                        check(!results.removeFirst().results().getFirst().present(),"framed picking used expanded world AABB rather than exact local box");
                        var plane=new float[24];plane[0]=1;plane[3]=-pool.getFloat(0)-8;
                        for(var pass:PackagePoolGpu.DrawPass.values()) {
                            check(bridge.preparePass(pass,particles,plane,0,0,0),"framed scaled culling pass not prepared");
                            var draw=readBuffer(bridge.passCommandBuffer(pass),64);
                            check(draw.getInt(36)==1&&draw.getInt(52)==1,"nonuniform parent scale falsely culled chain box/rig");
                        }
                    }
                    int oldAdmission=bridge.admissionBuffer(),oldAttachment=bridge.attachmentBuffer();
                    var oldPose=readBuffer(particles,capacity*64);
                    frameBytes.putFloat(12,frameBytes.getFloat(12)+.125f);putBuffer(parentRows,frameBytes);
                    var nextFrames=frameGpu.view(frameBytes,1);putBuffer(counter,BufferUtils.createByteBuffer(16));
                    bridge.chainFrames(nextFrames);
                    bridge.stage(nextParticles,counter,7,new float[24],0,0,0);bridge.abort();
                    check(bridge.committedPoolBuffer()==particles&&bridge.admissionBuffer()==oldAdmission&&bridge.attachmentBuffer()==oldAttachment
                            &&oldPose.equals(readBuffer(particles,capacity*64))&&attachment.equals(readBuffer(oldAttachment,attachment.capacity())),"failed parent generation modified committed poses/frames");
                    check(queries.pick(input,ray,1,"old logical"),"committed query after parent abort unavailable");GL11.glFinish();queries.poll(results::add);
                    check(results.removeFirst().results().getFirst().id()==0x100000001L+i,"aborted parent frame leaked into picking");
                }
                int ordinary=0;for(int kind:kinds)if(kind<2)ordinary++;
                for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY}) {
                    frameBytes.putFloat(0,invalid);putBuffer(parentRows,frameBytes);var invalidFrames=frameGpu.view(frameBytes,1);
                    bridge.chainFrames(invalidFrames);putBuffer(counter,BufferUtils.createByteBuffer(16));
                    bridge.stage(nextParticles,counter,7,new float[24],0,0,0);bridge.commit();
                    check(readBuffer(counter,16).getInt(0)==ordinary,"invalid parent matrix published package slots");
                }
                frameBytes.putFloat(0,0);frameBytes.putFloat(20,0);putBuffer(parentRows,frameBytes);var singularFrames=frameGpu.view(frameBytes,1);
                bridge.chainFrames(singularFrames);putBuffer(counter,BufferUtils.createByteBuffer(16));
                bridge.stage(nextParticles,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==ordinary,"singular parent matrix reached inverse/normal shader path");
                frameBytes.putFloat(20,3);putBuffer(parentRows,frameBytes);
                // A declared count larger than its bound SSBO cannot admit a missing row.
                bridge.chainFrames(parentRows,2);putBuffer(counter,BufferUtils.createByteBuffer(16));
                bridge.stage(nextParticles,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==boxes,"declared frame count bypassed actual source capacity");
                bridge.chainFrames(0,0);putBuffer(counter,BufferUtils.createByteBuffer(16));
                bridge.stage(particles,counter,7,new float[24],0,0,0);bridge.commit();
                check(readBuffer(counter,16).getInt(0)==ordinary,"unavailable parents consumed particle slots");
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"framed chain GL error "+n);
            }finally{for(int b:new int[]{particles,nextParticles,counter,bodies,links,previous,parentRows,tiny})GL15.glDeleteBuffers(b);}
        }
        chainFrameRing();
    }
    static void chainFrameRing() {
        var parent=BufferUtils.createByteBuffer(PackageChainGpuFrame.BYTES);
        var outputBuffers=new int[4];
        try(var frames=new PackageChainFramesGpu(1,PackageGpuValidation::source,fence->GL32.GL_TIMEOUT_EXPIRED)) {
            frames.append(0,0,0,0,0);
            for(int generation=0;generation<4;generation++) {
                parent.clear();
                for(int pose=0;pose<2;pose++) {
                    int p=pose*48;float x=pose*10+generation;
                    parent.putFloat(p,1).putFloat(p+4,0).putFloat(p+8,0).putFloat(p+12,x);
                    parent.putFloat(p+16,0).putFloat(p+20,1).putFloat(p+24,0).putFloat(p+28,0);
                    parent.putFloat(p+32,0).putFloat(p+36,0).putFloat(p+40,1).putFloat(p+44,0);
                }
                var view=frames.view(parent,1);check(view!=null,"chain frame staging bank unavailable before four in-flight generations");
                outputBuffers[generation]=view.buffer();view.close();
            }
            check(frames.view(parent,1)==null&&frames.skipped()==1,"chain frame ring overwrote or waited on four in-flight banks");
            GL11.glFinish();
            for(int generation=0;generation<4;generation++) {
                var rows=readBuffer(outputBuffers[generation],PackageChainGpuFrame.BYTES);
                check(rows.getFloat(12)==generation&&rows.getFloat(60)==10+generation,
                        "chain frame ring reused an in-flight parent/output bank");
            }
        }
    }
    static void pool() {
        int n=65;
        ByteBuffer poolBytes=BufferUtils.createByteBuffer(n*64+128);
        for(int j=n*64;j<poolBytes.capacity();j++)poolBytes.put(j,(byte)0x5a);
        int pool=buffer(poolBytes),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(n,4,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*PackagePoolGpu.META_BYTES);
            for(int i=0;i<n;i++) {
                body(b,i,i*3,10,0,1);
                int p=i*PackagePoolGpu.META_BYTES;
                meta.putLong(p,(1L<<40)+i).putLong(p+8,(1L<<35)+1)
                        .putInt(p+16,i).putInt(p+20,0).putInt(p+24,1).putInt(p+28,i%2==0?1:0)
                        .putFloat(p+56,23/16f).putInt(p+60,0xf000f0);
            }
            physics.upload(b,n);physics.uploadChains(bodies(n));
            ByteBuffer vertex=BufferUtils.createByteBuffer(6*PackagePoolGpu.VERTEX_BYTES);
            ByteBuffer ranges=BufferUtils.createByteBuffer(32);
            ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
            ranges.putInt(16,3).putInt(20,3).putFloat(24,1);
            bridge.uploadMeshes(vertex,ranges,2);
            ByteBuffer prefix=meta.duplicate();prefix.limit(63*PackagePoolGpu.META_BYTES);
            bridge.uploadMetadata(prefix,63);
            boolean duplicate=false;
            try{bridge.appendMetadata(meta.duplicate().limit(PackagePoolGpu.META_BYTES),1);}
            catch(IllegalArgumentException expected){duplicate=true;}
            check(duplicate && bridge.metadataCount()==63,"duplicate identity/body entered incremental metadata");
            ByteBuffer reusedBody=BufferUtils.createByteBuffer(PackagePoolGpu.META_BYTES);
            reusedBody.putLong(0,999999).putLong(8,1).putInt(16,62);
            duplicate=false;
            try{bridge.appendMetadata(reusedBody,1);}catch(IllegalArgumentException expected){duplicate=true;}
            check(duplicate && bridge.metadataCount()==63,"existing body index was reused");
            ByteBuffer suffix=meta.duplicate();suffix.position(63*PackagePoolGpu.META_BYTES);
            bridge.appendMetadata(suffix,2);
            check(bridge.metadataCount()==65,"incremental metadata count");
            duplicate=false;
            try{bridge.appendMetadata(meta.duplicate().limit(PackagePoolGpu.META_BYTES),1);}
            catch(IllegalArgumentException expected){duplicate=true;}
            check(duplicate && bridge.metadataCount()==65,"metadata capacity overrun changed population");
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,123.5f,0,0);
            for(int ordinary:new int[]{0,63,65,93}) {
                ByteBuffer c=BufferUtils.createByteBuffer(16);c.putInt(0,ordinary);putBuffer(counter,c);
                bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
                int accepted=n;
                check(readBuffer(counter,16).getInt(0)==n,"package priority did not cap shared pool");
                ByteBuffer result=readBuffer(pool,poolBytes.capacity()),admission=readBuffer(bridge.admissionBuffer(),n*32);
                Set<Integer> slots=new HashSet<>();int rigs=0;
                for(int i=0;i<n;i++) {
                    int p=i*32;
                    check(admission.getLong(p)==(1L<<40)+i,"identity truncated/reordered");
                    check(admission.getLong(p+8)==(1L<<35)+1,"generation truncated");
                    int slot=admission.getInt(p+16)-1;if(slot<0)continue;
                    check(slot>=0 && slot<n,"priority package slot out of range");
                    check(slots.add(slot),"duplicate common slot");
                    check(result.getFloat(slot*64)==i*3+123.5f,"GPU physics pose not imported");
                    check(result.getInt(slot*64+60)==7,"emitter ABI offset");
                    if(i%2==0)rigs++;
                }
                check(slots.size()==accepted,"missing admission");
                for(int j=n*64;j<result.capacity();j++)check(result.get(j)==(byte)0x5a,"pool sentinel overwritten");
                ByteBuffer commands=readBuffer(bridge.commandBuffer(),32);
                check(commands.getInt(4)==accepted && commands.getInt(20)==rigs,"model partition counts");
                check(commands.getInt(12)==0 && commands.getInt(28)==accepted,"model prefix/baseInstance");
                ByteBuffer instances=readBuffer(bridge.instanceBuffer(),(accepted+rigs)*8);
                Set<Integer> boxes=new HashSet<>(),hooks=new HashSet<>();
                for(int j=0;j<accepted+rigs;j++) {
                    int slot=instances.getInt(j*8),part=instances.getInt(j*8+4)&1;
                    check(slots.contains(slot),"draw instance references rejected package");
                    check((part==0?boxes:hooks).add(slot),"duplicate mesh instance");
                }
                check(boxes.size()==accepted && hooks.size()==rigs,"box/rig partition uniqueness");
                if(ordinary==0) {
                    var expected=List.of(new PackageAdmissionTracker.Expected((1L<<40)+63,(1L<<35)+1,0),
                            new PackageAdmissionTracker.Expected((1L<<40)+64,(1L<<35)+1,PackagePoolGpu.CHAIN));
                    try(var tracker=new PackageAdmissionTracker(2,77)) {
                        for(int sequence=1;sequence<=4;sequence++)
                            check(tracker.submit(bridge,63,expected,sequence),"admission staging slot unavailable");
                        check(!tracker.submit(bridge,63,expected,5),"full admission ring overwrote a snapshot");
                        GL11.glFinish();List<PackageAdmissionTracker.Outcome> outcomes=new ArrayList<>();
                        check(tracker.poll(outcomes::add)==4 && tracker.pending()==0,"admission snapshots did not drain");
                        check(outcomes.size()==8,"admission snapshot length");
                        for(int j=0;j<outcomes.size();j++) {
                            var outcome=outcomes.get(j);
                            check(outcome.accepted() && outcome.submission()==j/2+1 && outcome.candidate()==63+j%2
                                    && outcome.id()==expected.get(j%2).id()
                                    && outcome.generation()==expected.get(j%2).generation()
                                    && outcome.flags()==expected.get(j%2).flags()
                                    && slots.contains(outcome.slotPlusOne()-1),"staged admission lost full identity or slot");
                        }
                        check(tracker.submit(bridge,63,expected,5),"drained admission ring could not be reused");
                        GL11.glFinish();check(tracker.poll(outcomes::add)==1,"reused admission slot lost snapshot");
                    }
                    try(var tracker=new PackageAdmissionTracker(2,78)) {
                        var wrong=List.of(expected.get(0),new PackageAdmissionTracker.Expected(999,(1L<<35)+1,PackagePoolGpu.CHAIN));
                        check(tracker.submit(bridge,63,wrong,1),"wrong-identity fixture did not submit");
                        GL11.glFinish();List<PackageAdmissionTracker.Outcome> outcomes=new ArrayList<>();
                        boolean rejected=false;
                        try{tracker.poll(outcomes::add);}catch(IllegalStateException e){rejected=true;}
                        check(rejected && outcomes.isEmpty(),"partial admission published before whole-batch validation");
                    }
                }
            }
            ByteBuffer originalAdmission=readBuffer(bridge.admissionBuffer(),64);
            var firstTwo=List.of(new PackageAdmissionTracker.Expected(1L<<40,(1L<<35)+1,PackagePoolGpu.CHAIN),
                    new PackageAdmissionTracker.Expected((1L<<40)+1,(1L<<35)+1,0));
            for(int fault=0;fault<3;fault++) {
                ByteBuffer damaged=BufferUtils.createByteBuffer(64);damaged.put(originalAdmission.duplicate()).flip();
                if(fault==0)damaged.putInt(16,n+1); // outside the particle pool
                if(fault==1)damaged.putInt(48,originalAdmission.getInt(16)); // duplicate slot
                if(fault==2)damaged.putInt(24,1); // nonzero reserved word
                putBuffer(bridge.admissionBuffer(),damaged);
                try(var tracker=new PackageAdmissionTracker(2,80+fault)) {
                    check(tracker.submit(bridge,0,firstTwo,1),"damaged admission fixture did not submit");
                    GL11.glFinish();List<PackageAdmissionTracker.Outcome> outcomes=new ArrayList<>();
                    boolean rejected=false;
                    try{tracker.poll(outcomes::add);}catch(IllegalStateException e){rejected=true;}
                    check(rejected && outcomes.isEmpty(),"damaged admission published a partial claim");
                }
                putBuffer(bridge.admissionBuffer(),originalAdmission.duplicate());
            }
            // Preparing a real Create object must prove a slot without drawing a duplicate.
            bridge.setHidden(0,true);
            ByteBuffer hiddenCounter=BufferUtils.createByteBuffer(16);putBuffer(counter,hiddenCounter);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            ByteBuffer hiddenAdmission=readBuffer(bridge.admissionBuffer(),n*32);
            int reservedSlot=hiddenAdmission.getInt(16)-1;
            check(reservedSlot>=0 && hiddenAdmission.getInt(20)==(PackagePoolGpu.CHAIN|PackagePoolGpu.HIDDEN)
                    && readBuffer(counter,16).getInt(0)==n,"hidden preparation lost common-slot admission");
            ByteBuffer hiddenCommands=readBuffer(bridge.commandBuffer(),32);
            check(hiddenCommands.getInt(4)==n-1 && hiddenCommands.getInt(20)==(n+1)/2-1,
                    "prepared Create-owned package entered draw commands");
            ByteBuffer hiddenInstances=readBuffer(bridge.instanceBuffer(),(n-1+(n+1)/2-1)*8);
            for(int i=0;i<hiddenInstances.capacity()/8;i++)
                check(hiddenInstances.getInt(i*8)!=reservedSlot,"hidden package entered box/rig draw stream");
            try(var tracker=new PackageAdmissionTracker(1,83)) {
                check(tracker.submit(bridge,0,List.of(new PackageAdmissionTracker.Expected(1L<<40,(1L<<35)+1,
                        PackagePoolGpu.CHAIN|PackagePoolGpu.HIDDEN)),1),"hidden admission readback unavailable");
                GL11.glFinish();List<PackageAdmissionTracker.Outcome> outcomes=new ArrayList<>();
                check(tracker.poll(outcomes::add)==1 && outcomes.size()==1 && outcomes.get(0).accepted()
                        && outcomes.get(0).slotPlusOne()==reservedSlot+1,"hidden candidate could not confirm GPU slot");
            }
            ByteBuffer ordinaryPrefix=readBuffer(pool,64);
            hiddenCounter.putInt(0,1);putBuffer(counter,hiddenCounter);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            check(readBuffer(bridge.admissionBuffer(),n*32).getInt(16)==0
                    && readBuffer(counter,16).getInt(0)==n && ordinaryPrefix.equals(readBuffer(pool,64)),
                    "new hidden package evicted an ordinary particle from a full shared pool");
            bridge.setHidden(0,false);hiddenCounter.putInt(0,0);
            putBuffer(counter,hiddenCounter);bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            ByteBuffer visibleCommands=readBuffer(bridge.commandBuffer(),32);
            check(visibleCommands.getInt(4)==n && visibleCommands.getInt(20)==(n+1)/2,
                    "confirmed package did not re-enter box/rig draws");
            // Static colliders and fallback bodies consume sidecar storage, never a particle slot.
            b.putFloat(12,0).putFloat(64+60,-1);physics.upload(b,n);
            ByteBuffer c=BufferUtils.createByteBuffer(16);putBuffer(counter,c);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,0,0,0);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==n-2,"rejected bodies left holes/count inflation");
            try(var tracker=new PackageAdmissionTracker(2,79)) {
                var expected=List.of(new PackageAdmissionTracker.Expected(1L<<40,(1L<<35)+1,PackagePoolGpu.CHAIN),
                        new PackageAdmissionTracker.Expected((1L<<40)+1,(1L<<35)+1,0));
                check(tracker.submit(bridge,0,expected,1),"rejected-body admission snapshot missing");
                GL11.glFinish();List<PackageAdmissionTracker.Outcome> outcomes=new ArrayList<>();
                check(tracker.poll(outcomes::add)==1 && outcomes.size()==2
                        && !outcomes.get(0).accepted() && !outcomes.get(1).accepted()
                        && outcomes.get(0).slotPlusOne()==0 && outcomes.get(1).slotPlusOne()==0,
                        "rejected body claimed common particle slot");
            }
            ByteBuffer prior=readBuffer(pool,128);
            c.putInt(0,4);putBuffer(counter,c);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            ByteBuffer preserved=readBuffer(pool,128);
            check(readBuffer(counter,16).getInt(0)==n && preserved.getFloat(0)==prior.getFloat(0)
                            && preserved.getFloat(64)==prior.getFloat(64),
                    "package priority failed to preserve the ordinary prefix");
            c.putInt(0,0);putBuffer(counter,c);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            ByteBuffer good=readBuffer(bridge.commandBuffer(),32),goodAdmission=readBuffer(bridge.admissionBuffer(),n*32);
            putBuffer(counter,c);float[] hidden=new float[24];hidden[0]=1;hidden[3]=-10000;
            bridge.stage(pool,counter,7,hidden,0,0,0);bridge.abort();
            check(good.equals(readBuffer(bridge.commandBuffer(),32)),"failed frame published partial draw commands");
            check(goodAdmission.equals(readBuffer(bridge.admissionBuffer(),n*32)),"failed frame published partial identities");
            putBuffer(counter,c);bridge.stage(pool,counter,-1,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==0 && bridge.admissionCount()==0,"missing emitter imported invalid records");
            bridge.uploadMetadata(BufferUtils.createByteBuffer(0),0);c.putInt(0,7);putBuffer(counter,c);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            check(readBuffer(counter,16).getInt(0)==7,"empty import changed ordinary live count");
            check(readBuffer(bridge.commandBuffer(),32).getInt(4)==0,"empty generation retained draw instances");
        }finally{GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
    }
    static int texture(int unit,int size) {
        int id=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        ByteBuffer pixels=BufferUtils.createByteBuffer(size*size*4);while(pixels.hasRemaining())pixels.put((byte)255);pixels.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return id;
    }
    static final class DrawPassFixture implements AutoCloseable {
        final int count,capacity,pool,counter;
        final PackagePhysicsGpu physics;
        final PackagePoolGpu bridge;
        DrawPassFixture(int n,boolean hidden) {
            this(n,hidden,false);
        }
        DrawPassFixture(int n,boolean hidden,boolean reference) {
            count=n;capacity=Math.max(1,n);pool=buffer(bodies(capacity));counter=buffer(BufferUtils.createByteBuffer(16));
            physics=new PackagePhysicsGpu(capacity,2,PackageGpuValidation::source);bridge=new PackagePoolGpu(capacity,2,name->{
                String text=source(name);
                if(reference && (name.endsWith("draw_count.comp") || name.endsWith("draw_scatter.comp"))) {
                    text=text.replace("s>uCapacity || ","").replace("drawGroup(box,result.y)","box").replace("drawGroup(rig,result.y)","rig");
                }
                if(reference && name.endsWith("draw_prefix.comp")) {
                    text=text.replace("m<uMeshCount*PACKAGE_DRAW_LAYERS","m<uMeshCount").replace("uint mesh=m<uMeshCount?m:m-uMeshCount;","uint mesh=m;");
                }
                return text;
            });
            var state=bodies(n);var chains=bodies(n);var meta=BufferUtils.createByteBuffer(n*80);
            for(int i=0;i<n;i++) {
                float x=i*3-n*1.5f;body(state,i,x,0,0,1);
                chains.putFloat(i*64+40,3).putFloat(i*64+44,i%4==0?1:0).putFloat(i*64+48,x);
                int flags=i%2==0?PackagePoolGpu.CHAIN|(i%4==0?PackagePoolGpu.FLIPPED:0):0;
                if(hidden && i%17==5)flags|=PackagePoolGpu.HIDDEN;
                meta.putLong(i*80,0x100000001L+i).putLong(i*80+8,0x200000003L).putInt(i*80+16,i)
                        .putInt(i*80+20,0).putInt(i*80+24,1).putInt(i*80+28,flags).putInt(i*80+60,0xf000f0);
            }
            physics.upload(state,n);physics.uploadChains(chains);
            var ranges=BufferUtils.createByteBuffer(32);ranges.putInt(4,3).putInt(16,3).putInt(20,3);
            bridge.uploadMeshes(BufferUtils.createByteBuffer(6*PackagePoolGpu.VERTEX_BYTES),ranges,2);bridge.uploadMetadata(meta,n);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,16,0,-32);
        }
        void stage(float[] frustum){putBuffer(counter,BufferUtils.createByteBuffer(16));bridge.stage(pool,counter,7,frustum,16,0,-32);bridge.commit();}
        public void close(){bridge.close();physics.close();GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
    }
    static void preparedDrawPasses() {
        float[] culled=new float[24];culled[3]=-1_000_000;
        for(int n:new int[]{0,1,63,64,65,131072})try(var f=new DrawPassFixture(n,true)) {
            var bridge=f.bridge;f.stage(culled);
            var poolBefore=readBuffer(f.pool,f.capacity*64);var admissionBefore=n==0?null:readBuffer(bridge.admissionBuffer(),n*32);
            var mainBefore=readBuffer(bridge.commandBuffer(),32);
            check(mainBefore.getInt(4)==0 && mainBefore.getInt(20)==0,"main camera fixture not culled");
            check(bridge.preparePass(PackagePoolGpu.DrawPass.GBUFFER,f.pool,culled,16,0,-32),"gbuffer pass failed preparation");
            var gbuffer=readBuffer(bridge.passCommandBuffer(PackagePoolGpu.DrawPass.GBUFFER),64);
            for(int g=0;g<4;g++)check(gbuffer.getInt(g*16+4)==0,"gbuffer used another camera's visibility");
            check(bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,new float[24],16,0,-32),"shadow pass failed preparation");
            var commands=readBuffer(bridge.passCommandBuffer(PackagePoolGpu.DrawPass.SHADOW),64);
            int ground=0,chain=0;
            for(int i=0;i<n;i++)if(i%17!=5){if(i%2==0)chain++;else ground++;}
            check(commands.getInt(4)==ground && commands.getInt(20)==0 && commands.getInt(36)==chain && commands.getInt(52)==chain,
                    "solid/cutout box/rig material partition "+n);
            check(commands.getInt(12)==0 && commands.getInt(28)==ground && commands.getInt(44)==ground && commands.getInt(60)==ground+chain,
                    "material prefix/baseInstance "+n);
            int total=ground+2*chain;var instances=total==0?null:readBuffer(bridge.passInstanceBuffer(PackagePoolGpu.DrawPass.SHADOW),total*8);
            var boxes=new BitSet(n);var rigs=new BitSet(n);
            for(int j=0;j<total;j++) {
                int slot=instances.getInt(j*8),packed=instances.getInt(j*8+4),candidate=packed>>>1,part=packed&1;
                check(candidate<n && candidate%17!=5 && admissionBefore.getInt(candidate*32+16)==slot+1,"draw instance leaked rejected/hidden/foreign slot");
                boolean isChain=j>=ground;check((candidate%2==0)==isChain && part==(j>=ground+chain?1:0),"draw instance in wrong material/part");
                var seen=part==0?boxes:rigs;check(!seen.get(candidate),"duplicate prepared draw instance");seen.set(candidate);
            }
            check(boxes.cardinality()==ground+chain && rigs.cardinality()==chain,"prepared draw omitted box/rig");
            check(mainBefore.equals(readBuffer(bridge.commandBuffer(),32)) && gbuffer.equals(readBuffer(bridge.passCommandBuffer(PackagePoolGpu.DrawPass.GBUFFER),64)),
                    "shadow modified main/gbuffer commands");
            check(poolBefore.equals(readBuffer(f.pool,f.capacity*64)) && (n==0 || admissionBefore.equals(readBuffer(bridge.admissionBuffer(),n*32))),
                    "draw-only preparation changed physics/admission");
            // A light-camera plane excludes the left side. It still starts from complete
            // admission instead of the main camera's empty command stream.
            float[] plane=new float[24];plane[0]=1;bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,plane,16,0,-32);
            int expected=0;for(int i=0;i<n;i++)if(i%17!=5 && i*3-n*1.5f>=-2)expected+=i%2==0?2:1;
            var clipped=readBuffer(bridge.passCommandBuffer(PackagePoolGpu.DrawPass.SHADOW),64);int sum=0;for(int g=0;g<4;g++)sum+=clipped.getInt(g*16+4);
            check(sum==expected,"shadow frustum did not select offscreen casters "+n);
            check(!bridge.bindPassTbos(PackagePoolGpu.DrawPass.SHADOW,10,.5f),"absent light source became confirmed");
            if(n>0) {
                int output=buffer(BufferUtils.createByteBuffer(64)),program=compute("""
                    layout(binding=10) uniform samplerBuffer positions;
                    layout(binding=11) uniform samplerBuffer attachments;
                    layout(binding=12) uniform usamplerBuffer lights;
                    layout(std430,binding=15) buffer Output { vec4 values[]; };
                    layout(local_size_x=1) in;
                    uniform int slot;
                    void main(){values[0]=texelFetch(positions,4*slot);values[1]=texelFetch(positions,4*slot+3);
                        values[2]=texelFetch(attachments,0);values[3]=uintBitsToFloat(texelFetch(lights,0));}
                    """);
                try {
                    int slot=admissionBefore.getInt(16)-1;GL20.glUseProgram(program);GL20.glUniform1i(GL20.glGetUniformLocation(program,"slot"),slot);
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,output);GL43.glDispatchCompute(1,1,1);
                    var tbo=readBuffer(output,64);
                    for(int i=0;i<16;i+=4)check(tbo.getInt(i)==poolBefore.getInt(slot*64+i) && tbo.getInt(16+i)==poolBefore.getInt(slot*64+48+i),"TBO pool view not same committed generation");
                    check(tbo.getInt(48)==0,"uninitialized sampled light TBO reused another source");
                }finally{GL20.glDeleteProgram(program);GL15.glDeleteBuffers(output);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,0);}
            }
            bridge.unbindPassTbos(10);
            boolean rejected=false;try{bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.counter,new float[24],16,0,-32);}catch(IllegalArgumentException expectedError){rejected=true;}
            check(rejected,"draw pass accepted foreign pool generation");
            // Abort must preserve a complete old generation; a new commit invalidates consumers.
            putBuffer(f.counter,BufferUtils.createByteBuffer(16));bridge.stage(f.pool,f.counter,7,culled,16,0,-32);bridge.abort();
            check(bridge.passCommandBuffer(PackagePoolGpu.DrawPass.SHADOW)>0,"failed frame revoked old complete draw pass");
            f.stage(new float[24]);rejected=false;try{bridge.passCommandBuffer(PackagePoolGpu.DrawPass.SHADOW);}catch(IllegalStateException expectedError){rejected=true;}
            check(rejected,"new generation silently reused stale shadow instance mapping");
            bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,new float[24],16,0,-32);bridge.reset();rejected=false;
            try{bridge.passInstanceBuffer(PackagePoolGpu.DrawPass.SHADOW);}catch(IllegalStateException expectedError){rejected=true;}
            check(rejected,"reset left a usable old pass");
        }
    }
    static void shadowCullingAndBoundary() {
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"before shadow boundary GL error");
        try(var f=new DrawPassFixture(4,false)) {
            float[] invisible=new float[24];invisible[3]=-1e6f;f.stage(invisible);
            var policy=new com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling();
            float[][] planes=new float[13][4];planes[12]=new float[]{2,0,0,-2};policy.set(planes,13);
            check(policy.planes[48]==1 && policy.planes[51]==-1,"normalised Iris planes");
            var before=readBuffer(f.pool,4*64);var admission=readBuffer(f.bridge.admissionBuffer(),4*32);
            for(int mode=0;mode<5;mode++) {
                if(mode==1)policy.safe=4;
                if(mode==2){policy.clear();policy.distance=1;}
                if(mode==3)policy.reject();
                if(mode==4)policy.clear();
                f.bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,policy.planes,policy.count,policy.distance,policy.safe,16,0,-32);
                var commands=readBuffer(f.bridge.passCommandBuffer(PackagePoolGpu.DrawPass.SHADOW),64);int sum=0;
                for(int i=0;i<4;i++)sum+=commands.getInt(i*16+4);
                check(sum==new int[]{3,6,4,0,6}[mode],"Iris shadow planes/box/safe/no-cull mode "+mode);
                check(readBuffer(f.pool,256).equals(before) && readBuffer(f.bridge.admissionBuffer(),128).equals(admission),"shadow policy mutated committed identities");
            }
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"shadow culling GL error");
            int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            int foreign=buffer(BufferUtils.createByteBuffer(alignment+256)),vao=GL30.glGenVertexArrays(),fb=GL30.glGenFramebuffers();
            int tex=texture(10,1),view=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,view);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,foreign);
            int program=feedbackProgram("#version 450 core\nvoid main(){gl_Position=vec4(0,0,0,1);}","gl_Position");
            var state=new com.iridium126.createmanaindustry.client.particles.packages.PackageRenderState();
            try {
                for(int attempt=0;attempt<3;attempt++) {
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,1,foreign,alignment,128);
                    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,foreign);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,foreign);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,foreign);
                    GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,foreign);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,foreign);
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fb);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,0);GL20.glUseProgram(program);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDepthMask(false);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,5);
                    GL30.glEnablei(GL11.GL_BLEND,1);GL40.glBlendFuncSeparatei(1,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL40.glBlendEquationSeparatei(1,GL14.GL_FUNC_SUBTRACT,GL14.GL_FUNC_REVERSE_SUBTRACT);
                    GL13.glActiveTexture(GL13.GL_TEXTURE10);GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,view);GL13.glActiveTexture(GL13.GL_TEXTURE3);
                    check(GL11.glGetError()==GL11.GL_NO_ERROR,"seed boundary GL error");
                    state.capture();check(GL11.glGetError()==GL11.GL_NO_ERROR,"capture boundary GL error");boolean failed=false;
                    try {
                        f.bridge.preparePass(PackagePoolGpu.DrawPass.GBUFFER,f.pool,new float[24],16,0,-32);
                        f.bridge.bindPassTbos(PackagePoolGpu.DrawPass.GBUFFER,10,1);
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glBindVertexArray(0);GL20.glUseProgram(0);
                        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,0);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,0);
                        GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glDepthMask(true);GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);
                        GL11.glDisable(GL11.GL_BLEND);GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
                        if(attempt==1)throw new IllegalStateException("draw failed");
                    }catch(IllegalStateException expected){failed=true;}finally{state.restore();}
                    int boundaryError=GL11.glGetError();check(boundaryError==GL11.GL_NO_ERROR,"restore boundary GL error "+boundaryError);
                    check(failed==(attempt==1),"boundary failure fixture");
                    check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==program && GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==vao,"program/VAO boundary");
                    check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==fb && GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)==0,"framebuffer boundary");
                    check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,1)==foreign && GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,1)==alignment
                            && GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,1)==128,"SSBO ranged binding boundary");
                    check(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,0)==f.pool
                            && GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,0)==0,"SSBO base binding semantics boundary");
                    check(GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)==foreign && GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER)==foreign
                            && GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER)==foreign,"generic buffer boundary");
                    check(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)==foreign && GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING)==foreign,"vertex/indirect boundary");
                    check(!GL11.glIsEnabled(GL11.GL_DEPTH_TEST) && !GL11.glIsEnabled(GL11.GL_CULL_FACE) && !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),"depth/cull boundary");
                    check(GL11.glGetInteger(GL40.GL_PATCH_VERTICES)==5,"patch vertices boundary");
                    check(GL30.glIsEnabledi(GL11.GL_BLEND,1) && GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,1)==GL11.GL_SRC_ALPHA
                            && GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,1)==GL14.GL_FUNC_SUBTRACT,"per-target blend boundary");
                    check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE3,"active texture boundary");
                    GL13.glActiveTexture(GL13.GL_TEXTURE10);
                    check(GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER)==view && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==tex,"TBO restoration destroyed foreign texture target");
                }
            }finally {
                state.restore();GL20.glUseProgram(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glBindVertexArray(0);
                GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);GL11.glDepthMask(true);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_BLEND);GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
                for(int i=0;i<12;i++)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,0);
                GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,0);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,0);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,0);
                GL20.glDeleteProgram(program);GL30.glDeleteVertexArrays(vao);GL30.glDeleteFramebuffers(fb);GL11.glDeleteTextures(view);GL11.glDeleteTextures(tex);GL15.glDeleteBuffers(foreign);GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
        }
    }
    static void packageDrawTelemetry() {
        try(var timing=new com.iridium126.createmanaindustry.client.particles.packages.PackageDrawTelemetry()) {
            timing.begin(false);timing.end();check(timing.pending()==0 && timing.cpuSamples()==0,"disabled draw timing issued samples");
            int foreign=GL15.glGenQueries();
            try {
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,foreign);
                for(int i=0;i<5;i++) {
                    try {
                        timing.begin(true);
                        check(GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)==foreign,"draw timestamp replaced foreign timer");
                        if(i==2)throw new IllegalStateException("failed package draw");
                    }catch(IllegalStateException expected){}finally{timing.end();}
                    check(timing.pending()==Math.min(i+1,4),"draw timestamp ring reused a pending bank");
                }
                check(timing.skipped()==1 && timing.cpuSamples()==5,"full draw timing ring lost CPU timing or overwrote GPU timing");
                check(GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)==foreign,"failed draw ended foreign timer");
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL11.glFinish();
                check(timing.poll()>=0 && timing.pending()==0 && timing.gpuSamples()==4,"completed draw timestamp samples were not consumed");
                check(timing.poll()==0 && timing.gpuSamples()==4,"draw timestamp sample was counted twice");
                timing.begin(true);timing.end();GL11.glFinish();timing.poll();check(timing.gpuSamples()==5,"draw timestamp ring did not recover");
                timing.close();check(timing.pending()==0 && timing.cpuSamples()==0 && timing.gpuSamples()==0,"draw timing close retained old world samples");
                timing.begin(true);timing.end();GL11.glFinish();timing.poll();check(timing.gpuSamples()==1,"draw timing did not recreate after close");
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"draw timestamp GL error");
            }finally{if(GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)==foreign)GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL15.glDeleteQueries(foreign);}
        }
    }
    static void drawPassBenchmark() throws Exception {
        drawPassBenchmark(false);
    }
    static void drawPassBenchmark(boolean irisBoundary) throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,instances");
        samples.add("count,run,sample,gpu_ms,cpu_submit_ms");
        String[] modes=irisBoundary?new String[]{"current_group","split_pass","split_shadow_13","split_boundary_13"}
                :new String[]{"legacy_group_reference","current_group","split_pass"};
        for(int n:new int[]{10000,65536,131072})for(String mode:modes) {
            try(var f=new DrawPassFixture(n,false,mode.equals("legacy_group_reference"))) {
                float[] frustum=new float[24];f.stage(frustum);int timer=GL15.glGenQueries();
                float[] shadow=new float[52];for(int i=0;i<13;i++){shadow[i*4+1]=i%2==0?1:-1;shadow[i*4+3]=1000;}
                var state=new com.iridium126.createmanaindustry.client.particles.packages.PackageRenderState();
                // Fixed GPU reset (no new host counter upload inside the measured loop).
                Runnable submit=mode.equals("split_boundary_13")?()->{
                    state.capture();try{f.bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,shadow,13,-1,-1,16,0,-32);}finally{state.restore();}
                }:mode.equals("split_shadow_13")?()->f.bridge.preparePass(PackagePoolGpu.DrawPass.SHADOW,f.pool,shadow,13,-1,-1,16,0,-32)
                :mode.equals("split_pass")?()->f.bridge.preparePass(PackagePoolGpu.DrawPass.GBUFFER,f.pool,frustum,16,0,-32):()->{
                    GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,f.counter);
                    GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
                    f.bridge.stage(f.pool,f.counter,7,frustum,16,0,-32);f.bridge.commit();
                };
                try {
                    long began=System.nanoTime();int warm=0;
                    while(warm<30 || System.nanoTime()-began<1_000_000_000L){submit.run();GL11.glFinish();warm++;}
                    for(int run=0;run<3;run++) {
                        double[] gpu=new double[120],cpu=new double[120];
                        for(int s=0;s<120;s++) {
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timer);long begin=System.nanoTime();submit.run();cpu[s]=(System.nanoTime()-begin)/1e6;
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);gpu[s]=GL33.glGetQueryObjectui64(timer,GL15.GL_QUERY_RESULT)/1e6;
                            samples.add(n+","+mode+","+run+","+s+","+gpu[s]+","+cpu[s]);
                        }
                        boolean split=mode.startsWith("split_");var pass=mode.endsWith("13")?PackagePoolGpu.DrawPass.SHADOW:PackagePoolGpu.DrawPass.GBUFFER;
                        var cmd=readBuffer(split?f.bridge.passCommandBuffer(pass):f.bridge.commandBuffer(),split?64:32);
                        int count=0;for(int g=0;g<(split?4:2);g++)count+=cmd.getInt(g*16+4);
                        check(count==n+n/2,"draw benchmark dropped/cpu-fell-back packages");
                        String row=n+","+mode+","+run+","+percentile(gpu,.5)+","+percentile(gpu,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+count;
                        String file=irisBoundary?"package-iris-boundary":"package-draw-pass";
                        rows.add(row);System.out.println(row);Files.write(Path.of("build/"+file+".csv"),rows);Files.write(Path.of("build/"+file+"-samples.csv"),samples);
                    }
                }finally{GL15.glDeleteQueries(timer);}
            }
        }
    }
    static void render() {
        int pool=buffer(bodies(2)),counter=buffer(BufferUtils.createByteBuffer(16)),atlas=texture(1,1),light=texture(2,16);
        try(var physics=new PackagePhysicsGpu(2,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(2,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(2),meta=BufferUtils.createByteBuffer(2*PackagePoolGpu.META_BYTES);
            for(int i=0;i<2;i++) {
                body(b,i,i-.5f,0,0,1);b.putFloat(i*64+44,-90);
                int m=i*PackagePoolGpu.META_BYTES;
                meta.putLong(m,i+1).putLong(m+8,1).putInt(m+16,i).putInt(m+20,i).putInt(m+24,-1).putInt(m+60,0xf000f0);
            }
            physics.upload(b,2);
            ByteBuffer vertices=BufferUtils.createByteBuffer(12*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(32);
            int[] corners={0,1,2,2,3,0};float[][] xy={{0,0},{1,0},{1,1},{0,1}};
            for(int mesh=0;mesh<2;mesh++)for(int corner:corners) {
                vertices.putFloat(xy[corner][0]).putFloat(xy[corner][1]).putFloat(.5f).putFloat(0).putFloat(0);
                vertices.putFloat(0).putFloat(0).putFloat(0);
                vertices.putFloat(mesh==0?1:0).putFloat(0).putFloat(mesh==1?1:0).putFloat(1);
            }
            vertices.flip();ranges.putInt(0,0).putInt(4,6).putFloat(8,1).putInt(16,6).putInt(20,6).putFloat(24,1);
            bridge.uploadMeshes(vertices,ranges,2);bridge.uploadMetadata(meta,2);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),2,0,0,0);
            bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
            for(int reload=0;reload<3;reload++) {
                if(reload==1) {
                    boolean failed=false;
                    try {bridge.rebuild(name->name.equals("packages/package.fsh")?"broken shader":source(name));}
                    catch(IllegalStateException expected){failed=true;}
                    check(failed,"invalid graphics reload accepted");
                } else if(reload==2)bridge.rebuild(PackageGpuValidation::source);
                GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);
                GL11.glClearColor(0,1,0,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                ByteBuffer pixels=BufferUtils.createByteBuffer(64*64*4);
                GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                int left=(32*64+16)*4,right=(32*64+48)*4;
                check((pixels.get(left)&255)==255 && (pixels.get(left+2)&255)==0,"first mesh indirect render/reload");
                check((pixels.get(right)&255)==0 && (pixels.get(right+2)&255)==255,"baseInstance selects wrong package/mesh");
                bridge.preparePass(PackagePoolGpu.DrawPass.GBUFFER,pool,new float[24],0,0,0);
                // Restore the ordinary vertex program after pass preparation, then exercise
                // the exact indirect per-layer submission API under that program.
                bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);bridge.drawPreparedLayer(PackagePoolGpu.DrawPass.GBUFFER,0);
                bridge.drawPreparedLayer(PackagePoolGpu.DrawPass.GBUFFER,1);
                var layered=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,layered);
                check(pixels.equals(layered),"layered indirect draw changed ordinary package framebuffer/reload result");
            }
            // Raw multi-draw bypasses Iris's vanilla drawElements tessellation
            // conversion. Exercise PATCHES with the production instance layout.
            int tess=graphicsProgram("#version 450 core\n"+source("packages/package.vsh"),"""
                    #version 450 core
                    layout(vertices=3) out;
                    in vec2 vUv[];in vec4 vColor[];flat in uint vCutout[];
                    out vec2 tcUv[];out vec4 tcColor[];out uint tcCutout[];
                    void main(){
                        gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
                        tcUv[gl_InvocationID]=vUv[gl_InvocationID];tcColor[gl_InvocationID]=vColor[gl_InvocationID];tcCutout[gl_InvocationID]=vCutout[gl_InvocationID];
                        if(gl_InvocationID==0){gl_TessLevelOuter[0]=1;gl_TessLevelOuter[1]=1;gl_TessLevelOuter[2]=1;gl_TessLevelInner[0]=1;}
                    }
                    ""","""
                    #version 450 core
                    layout(triangles,equal_spacing,ccw) in;
                    in vec2 tcUv[];in vec4 tcColor[];in uint tcCutout[];
                    out vec2 vUv;out vec4 vColor;flat out uint vCutout;
                    void main(){
                        gl_Position=gl_TessCoord.x*gl_in[0].gl_Position+gl_TessCoord.y*gl_in[1].gl_Position+gl_TessCoord.z*gl_in[2].gl_Position;
                        vUv=gl_TessCoord.x*tcUv[0]+gl_TessCoord.y*tcUv[1]+gl_TessCoord.z*tcUv[2];
                        vColor=gl_TessCoord.x*tcColor[0]+gl_TessCoord.y*tcColor[1]+gl_TessCoord.z*tcColor[2];vCutout=tcCutout[0];
                    }
                    ""","#version 450 core\n"+source("packages/package.fsh"));
            try {
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                var expected=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,expected);
                GL20.glUseProgram(tess);float[] identity=new Matrix4f().get(new float[16]);
                for(String name:new String[]{"ModelViewMat","ProjMat"})GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(tess,name),false,identity);
                GL20.glUniform1f(GL20.glGetUniformLocation(tess,"uPartialTick"),1);GL20.glUniform1i(GL20.glGetUniformLocation(tess,"uAtlas"),1);
                GL20.glUniform1i(GL20.glGetUniformLocation(tess,"uLightmap"),2);
                GL20.glUniform3f(GL20.glGetUniformLocation(tess,"uLight0"),.16169f,.80845f,-.56594f);
                GL20.glUniform3f(GL20.glGetUniformLocation(tess,"uLight1"),-.16169f,.80845f,.56594f);
                GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES,3);
                for(boolean combined:new boolean[]{false,true}) {
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    if(combined)bridge.drawPrepared(PackagePoolGpu.DrawPass.GBUFFER,0,2,true);
                    else{bridge.drawPrepared(PackagePoolGpu.DrawPass.GBUFFER,0,1,true);bridge.drawPrepared(PackagePoolGpu.DrawPass.GBUFFER,1,1,true);}
                    var pixels=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    check(pixels.equals(expected),"tessellation indirect combined="+combined+" framebuffer parity");
                }
            }finally{GL20.glUseProgram(0);GL20.glDeleteProgram(tess);}
            // Validate the production compute -> per-candidate light -> vertex -> indirect draw,
            // rather than merely linking the sampler or checking a detached SSBO.
            ByteBuffer lightPixels=BufferUtils.createByteBuffer(16*16*4);
            for(int sky=0;sky<16;sky++)for(int block=0;block<16;block++)lightPixels.put((byte)(block*17)).put((byte)(block*17)).put((byte)(block*17)).put((byte)255);
            lightPixels.flip();GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glBindTexture(GL11.GL_TEXTURE_2D,light);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,16,16,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,lightPixels);
            var beforeLighting=readBuffer(pool,128);
            try(var lights=new com.iridium126.createmanaindustry.client.particles.packages.PackageLightGpu(2)) {
                bridge.lightSource(lights);
                for(int block:new int[]{3,12}) {
                    byte[] data=new byte[4096];Arrays.fill(data,0,2048,(byte)(block|(block<<4)));Arrays.fill(data,2048,4096,(byte)0x88);
                    for(int x=-1;x<=0;x++)lights.offer(new PackageCollisionCache.Section(x,-1,0),new com.iridium126.createmanaindustry.client.particles.packages.PackageLightCache.Snapshot(block,data));
                    check(lights.pump(16384,Long.MAX_VALUE),"production light atlas publication");
                    if(block==12) {
                        boolean failed=false;try{bridge.rebuild(name->name.equals("packages/light_sample.comp")?"broken shader":source(name));}catch(IllegalStateException expected){failed=true;}
                        check(failed,"invalid light reload accepted");
                    }
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                    ByteBuffer pixels=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                    check((pixels.get((32*64+16)*4)&255)==block*17,"production box light did not update");
                    check((pixels.get((32*64+48)*4+2)&255)==block*17,"production instance/light mapping or reload retention");
                }
                lights.invalidate(new PackageCollisionCache.Section(-1,-1,0),13);lights.pump(16384,Long.MAX_VALUE);
                bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                ByteBuffer retained=BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,retained);
                check((retained.get((32*64+16)*4)&255)==12*17,"production invalidation lost confirmed brightness");
                bridge.pollLightRequests(section->{throw new AssertionError("known pending section should not emit repeated prefetch");});
                meta.putLong(0,3).putLong(PackagePoolGpu.META_BYTES,4);bridge.uploadMetadata(meta,2);
                putBuffer(counter,BufferUtils.createByteBuffer(16));bridge.stage(pool,counter,7,new float[24],0,0,0);bridge.commit();
                bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,retained);
                check((retained.get((32*64+16)*4)&255)==255,"replacement identity inherited previous candidate light");
                // A pending old source cannot survive a source/epoch replacement. The new
                // candidate starts with its own metadata, never another lifetime's light.
                bridge.lightSource(null);bridge.lightSource(lights);bridge.draw(pool,new Matrix4f(),new Matrix4f(),0,0,0,1);
                GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,retained);
                check((retained.get((32*64+16)*4)&255)==255,"new source inherited an old confirmed-light lifetime");
                bridge.lightSource(null);
            }
            check(readBuffer(pool,128).equals(beforeLighting),"drawing light modified the committed pool generation");
        }finally {GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);GL11.glDeleteTextures(atlas);GL11.glDeleteTextures(light);}
    }
    static void previewLoad() {
        // Exercise the reported 65,536-package workload through processed resources and pool import.
        // This validates submission, not whole-frame performance or actual Create model rendering.
        int n=65536;
        int pool=buffer(bodies(n)),counter=buffer(BufferUtils.createByteBuffer(16));
        try(var physics=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);
            var bridge=new PackagePoolGpu(n,1,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),chains=bodies(n),meta=BufferUtils.createByteBuffer(n*PackagePoolGpu.META_BYTES);
            for(int i=0;i<n;i++) {
                int p=i*64,m=i*PackagePoolGpu.META_BYTES;
                body(b,i,(i%256)*3,-9f/16f,(i/256)*3,1);
                chains.putFloat(p,(i%256)*3).putFloat(p+8,(i/256)*3).putFloat(p+12,.875f)
                        .putFloat(p+36,90).putFloat(p+40,1);
                meta.putLong(m,i+1L).putLong(m+8,1).putInt(m+16,i).putInt(m+20,0).putInt(m+24,0)
                        .putInt(m+28,PackagePoolGpu.CHAIN).putInt(m+60,0xf000f0);
            }
            physics.upload(b,n);physics.uploadChains(chains);physics.stepChains(.05f);
            ByteBuffer vertices=BufferUtils.createByteBuffer(3*PackagePoolGpu.VERTEX_BYTES),ranges=BufferUtils.createByteBuffer(16);
            ranges.putInt(0,0).putInt(4,3).putFloat(8,1);
            bridge.uploadMeshes(vertices,ranges,1);bridge.uploadMetadata(meta,n);
            bridge.source(physics.stateBuffer(),physics.chainBuffer(),physics.historyBuffer(),n,0,64,0);
            bridge.stage(pool,counter,7,new float[24],0,64,0);bridge.commit();
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            ByteBuffer c=BufferUtils.createByteBuffer(16);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,counter);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,c);
            check(c.getInt(0)==n,"65536 preview candidates missing from live count");
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bridge.commandBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,c);
            check(c.getInt(0)==3 && c.getInt(4)==2*n,"65536 preview box/rig draw commands missing");
            ByteBuffer a=BufferUtils.createByteBuffer(n*32);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bridge.admissionBuffer());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,a);
            BitSet slots=new BitSet(n);boolean valid=true;
            for(int i=0;i<n;i++) {
                int slot=a.getInt(i*32+16)-1;
                if(a.getLong(i*32)!=i+1L || slot<0 || slot>=n || slots.get(slot)){valid=false;break;}
                slots.set(slot);
            }
            check(valid && slots.cardinality()==n,"65536 preview admission identities/slots are not unique");
        } finally {GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(counter);}
    }
    static void packageFaceLighting() {
        int ordinary=feedbackProgram("#version 450 core\n"+source("packages/package.vsh"),"vColor");
        String mergedSource=source("packages/package_merged.vsh").replace("void main() {","void packageMain() {");
        int merged=feedbackProgram("#version 450 core\n"+mergedSource+"\nout vec4 faceTint;void main(){packageMain();gl_Position=cmi_VertexLevel;faceTint=cmi_Tint;}","faceTint");
        var data=bodies(1);data.putInt(44,0xf000f0).putFloat(48,-90).putFloat(52,-90);
        int pool=buffer(data),attachment=buffer(BufferUtils.createByteBuffer(PackagePoolGpu.ATTACHMENT_BYTES)),output=buffer(BufferUtils.createByteBuffer(16));
        int vao=GL30.glGenVertexArrays(),light=texture(2,16);int[] views=new int[2];GL11.glGenTextures(views);
        GL30.glBindVertexArray(vao);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachment);
        for(int i=0;i<2;i++){GL13.glActiveTexture(GL13.GL_TEXTURE10+i);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,views[i]);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,i==0?pool:attachment);}
        GL20.glVertexAttrib3f(0,.5f,.5f,.5f);GL20.glVertexAttrib4f(3,.7f,.5f,.3f,.8f);GL30.glVertexAttribI2ui(4,0,0);
        Vector3f[] normals={new Vector3f(1,0,0),new Vector3f(-1,0,0),new Vector3f(0,1,0),new Vector3f(0,-1,0),new Vector3f(0,0,1),new Vector3f(0,0,-1)};
        try {
            for(int flags:new int[]{0,PackagePoolGpu.ACTIVE_AUTHORITY})for(float pitch:new float[]{0,.7f,-1.2f})for(int face=0;face<6;face++)for(int program:new int[]{ordinary,merged}) {
                data.putInt(44,0xf000f0|(flags<<24));putBuffer(pool,data);GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
                GL20.glUseProgram(program);boolean nativePass=program==ordinary;
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,nativePass?"ModelViewMat":"cmi_ModelView"),false,new Matrix4f().rotateX(pitch).rotateY(.6f).get(new float[16]));
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,new Matrix4f().get(new float[16]));
                GL20.glUniform1i(GL20.glGetUniformLocation(program,nativePass?"uLightingMode":"cmi_LightingMode"),1);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,nativePass?"uLightmap":"cmi_PackagePool"),nativePass?2:10);
                if(!nativePass)GL20.glUniform1i(GL20.glGetUniformLocation(program,"cmi_PackageAttachment"),11);
                var n=normals[face];GL20.glVertexAttrib3f(2,n.x,n.y,n.z);GL20.glVertexAttrib3f(5,n.x,n.y,n.z);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                float diffuse=nativePass?(face<2?.6f:face==2?1:face==3?.5f:.8f):1;
                var actual=readBuffer(output,16);float[] color={.7f,.5f,.3f,.8f};
                for(int channel=0;channel<4;channel++)check(Math.abs(actual.getFloat(channel*4)-color[channel]*(channel<3?diffuse:1))<2e-5,
                        "package face lighting depends on authority/camera or duplicates pack diffuse face="+face+" pitch="+pitch+" merged="+!nativePass);
            }
        }finally{GL20.glUseProgram(0);GL20.glDeleteProgram(ordinary);GL20.glDeleteProgram(merged);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(attachment);GL15.glDeleteBuffers(output);GL11.glDeleteTextures(light);GL11.glDeleteTextures(views);}
    }
    static void poseParity() {
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(shader,"#version 450 core\n"+source("packages/package.vsh"));GL20.glCompileShader(shader);
        check(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)!=0,"pose vertex compilation");
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);
        GL30.glTransformFeedbackVaryings(program,new CharSequence[]{"gl_Position","vColor"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,"pose feedback link");GL20.glDeleteShader(shader);
        int pool=buffer(bodies(1)),attachment=buffer(BufferUtils.createByteBuffer(32)),output=buffer(BufferUtils.createByteBuffer(32)),light=texture(2,16);
        int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);GL20.glUseProgram(program);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,attachment);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        float[] identity=new float[16];new Matrix4f().get(identity);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ModelViewMat"),false,identity);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"ProjMat"),false,identity);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uCamPos"),0,0,0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"uLightmap"),2);GL20.glVertexAttrib4f(3,1,1,1,1);
        Vector3f l0=new Vector3f(.16169f,.80845f,-.56594f).normalize(),l1=new Vector3f(-.16169f,.80845f,.56594f).normalize();
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uLight0"),l0.x,l0.y,l0.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(program,"uLight1"),l1.x,l1.y,l1.z);
        Random random=new Random(79);
        try {
            for(boolean separate:new boolean[]{false,true})for(int flags:new int[]{0,1,3})for(int rig=0;rig<2;rig++)for(float freePt:new float[]{0,.25f,.5f,1})for(int run=0;run<32;run++) {
                float pt=separate&&(flags&1)!=0?1-freePt:freePt;
                Vector3f previous=new Vector3f(random.nextFloat()*2,random.nextFloat()*2,random.nextFloat()*2);
                Vector3f current=new Vector3f(previous).add(.2f,-.1f,.3f),oldTarget=new Vector3f(1,2,3),target=new Vector3f(1.2f,2.1f,3.2f);
                float yaw0=random.nextFloat()*720-360,yaw1=yaw0+210,hook=23/16f;
                ByteBuffer state=bodies(1),extra=BufferUtils.createByteBuffer(32);
                state.putFloat(0,current.x).putFloat(4,current.y).putFloat(8,current.z)
                        .putFloat(16,previous.x).putFloat(20,previous.y).putFloat(24,previous.z)
                        .putFloat(32,target.x).putFloat(36,target.y).putFloat(40,target.z).putInt(44,flags<<24)
                        .putFloat(48,yaw1).putFloat(52,yaw0).putFloat(56,hook);
                extra.putFloat(0,oldTarget.x).putFloat(4,oldTarget.y).putFloat(8,oldTarget.z)
                        .putFloat(16,.001f).putFloat(20,-.002f).putFloat(24,.003f);
                putBuffer(pool,state);putBuffer(attachment,extra);
                Vector3f vertex=new Vector3f(random.nextFloat(),random.nextFloat(),random.nextFloat());
                int lighting=run%2,ambient=(run/2)%2;boolean unshaded=run%5==0;
                Vector3f normal=unshaded?new Vector3f():new Vector3f(run%3==0?1:0,run%3==1?1:0,run%3==2?1:0);
                GL20.glVertexAttrib3f(2,normal.x,normal.y,normal.z);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uLightingMode"),lighting);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uConstantAmbient"),ambient);
                GL20.glVertexAttrib3f(0,vertex.x,vertex.y,vertex.z);GL30.glVertexAttribI2ui(4,0,rig);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"uPartialTick"),freePt);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"uChainPartialTick"),1-freePt);
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"uSeparateChainInterpolation"),separate?1:0);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                GL11.glDrawArrays(GL11.GL_POINTS,0,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                float yaw=yaw0+((yaw1-yaw0+180)%360-180)*pt;
                Matrix4f transform=new Matrix4f();Vector3f pos=new Vector3f(previous).lerp(current,pt);
                if((flags&1)!=0) {
                    Vector3f anchor=new Vector3f(oldTarget).lerp(target,pt),d=new Vector3f(anchor).add(0,.5f,0).sub(pos);
                    new Matrix4f().rotateY((float)Math.toRadians(-yaw)).transformDirection(d);
                    float zr=(float)Math.toDegrees(Math.atan2(-d.x,d.y)),xr=(float)Math.toDegrees(Math.atan2(d.z,d.y));
                    zr=((zr+180)%360-180)*.5f;xr=((xr+180)%360-180)*.5f;
                    transform.translate(anchor).translate(0,10/16f,0).rotateY((float)Math.toRadians(yaw))
                            .rotateZ((float)Math.toRadians(Math.clamp(zr,-25,25))).rotateX((float)Math.toRadians(Math.clamp(xr,-25,25)));
                    if(rig==1 && (flags&2)!=0)transform.rotateY((float)Math.PI);
                    transform.translate(-.5f,-.5f,-.5f).translate(0,-hook+7/16f,0);
                } else transform.translate(pos).translate(.001f,-.002f,.003f).translate(0,.5f,0)
                        .rotateY((float)Math.toRadians(-yaw-90)).translate(-.5f,-.5f,-.5f);
                Vector3f expected=transform.transformPosition(vertex);ByteBuffer result=readBuffer(output,32);
                check(Math.abs(result.getFloat(0)-expected.x)<2e-5,"Create pose x parity");
                check(Math.abs(result.getFloat(4)-expected.y)<2e-5,"Create pose y parity");
                check(Math.abs(result.getFloat(8)-expected.z)<2e-5,"Create pose z parity");
                check(result.getFloat(12)==1,"Create pose homogeneous coordinate");
                float diffuse;
                if(lighting==1) {
                    transform.transformDirection(normal);
                    float yf=ambient!=0?.9f:(3+normal.y)*.25f;
                    diffuse=unshaded?(ambient!=0?.9f:1):Math.min(1,normal.x*normal.x*.6f+normal.y*normal.y*yf+normal.z*normal.z*.8f);
                } else {
                    if(unshaded)normal.set(0,1,0);else transform.transformDirection(normal);
                    diffuse=Math.min(1,(Math.max(0,l0.dot(normal))+Math.max(0,l1.dot(normal)))*.6f+.4f);
                }
                for(int channel=0;channel<3;channel++)check(Math.abs(result.getFloat(16+channel*4)-diffuse)<2e-5,"Create/Flywheel diffuse parity");
                check(result.getFloat(28)==1,"package color alpha");
            }
        }finally {
            GL20.glUseProgram(0);GL20.glDeleteProgram(program);GL30.glDeleteVertexArrays(vao);
            GL15.glDeleteBuffers(pool);GL15.glDeleteBuffers(attachment);GL15.glDeleteBuffers(output);
            GL11.glDeleteTextures(light);
        }
    }
    static void benchmark() throws Exception {
        List<String> rows=new ArrayList<>();rows.add("count,scenario,run,gpu_ms");
        for(int n:new int[]{10000,65536,131072})try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
            ByteBuffer b=bodies(n),c=bodies(n);
            for(int i=0;i<n;i++) {
                int x=i%512,z=i/512;body(b,i,x*3,10,z*3,1);
                int p=i*64;c.putFloat(p,x*3).putFloat(p+4,10).putFloat(p+8,z*3).putFloat(p+12,.875f)
                        .putFloat(p+36,90).putFloat(p+40,1);
            }
            for(String scenario:List.of("separated_free","chain")) {
                gpu.upload(b,n);gpu.uploadChains(c);
                Runnable step=scenario.equals("chain")?()->gpu.stepChains(.05f):()->gpu.step(.05f);
                for(int warm=0;warm<20;warm++)step.run();GL11.glFinish();
                for(int run=1;run<=3;run++) {
                    int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
                    for(int j=0;j<30;j++)step.run();GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    double ms=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/30e6;GL15.glDeleteQueries(query);
                    rows.add(n+","+scenario+","+run+","+ms);
                }
            }
        }
        Files.write(Path.of("build/package-gpu-kernels.csv"),rows);
        for(String row:rows)System.out.println(row);
    }
    static double percentile(double[] values,double fraction) {
        var sorted=values.clone();Arrays.sort(sorted);return sorted[Math.max(0,(int)Math.ceil(fraction*sorted.length)-1)];
    }
    static void worldBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,atlas_bytes,upload_p50_ms,upload_p95_ms,upload_overruns,upload_calls,uploaded_bytes,fallbacks,moving,terrain_penetration_max,adjacent_overlap_max");
        samples.add("count,scenario,run,sample,gpu_ms,cpu_submit_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072}) {
            // 343 sections cover the complete 20 warm + 30 measured steps without dropping
            // any candidate. This is a kernel fixture, not the production residency setting.
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                for(String scenario:List.of("legacy_air","world_air_cells","world_air","world_dense_cells","world_dense_contacts")) {
                    boolean world=!scenario.equals("legacy_air"),contacts=scenario.startsWith("world_dense"),coarse=!scenario.endsWith("_cells");
                    try(var atlas=world?new PackageCollisionGpu(343,1):null) {
                        int uploadCalls=0;
                        if(world) {
                            for(int x=0;x<7;x++)for(int y=-3;y<4;y++)for(int z=0;z<7;z++)
                                atlas.offer(new PackageCollisionCache.Section(x,y,z),contacts && y==0?floor:air);
                            while(atlas.stats().pending()!=0) {
                                atlas.pump(PackageCollisionGpu.DEFAULT_UPLOAD_BYTES,PackageCollisionGpu.DEFAULT_UPLOAD_NANOS);
                                if(++uploadCalls>20000)throw new AssertionError("World benchmark upload starved");
                            }
                        }
                        ByteBuffer b=bodies(n);float spacing=contacts?1.03125f:1.5f;
                        for(int i=0;i<n;i++) {
                            body(b,i,2+i%64*spacing,(contacts?1.5f:4)+i/4096*spacing,2+i/64%64*spacing,1);
                            b.putFloat(i*64+16,.25f);if(contacts)b.putFloat(i*64+20,-1);
                        }
                        gpu.upload(b,n);
                        try(var view=world?atlas.view(0,0,0,coarse):null) {
                            for(int warm=0;warm<20;warm++){if(world)gpu.stepWorld(.05f,view);else gpu.step(.05f);}
                        }
                        GL11.glFinish();
                        for(int run=1;run<=3;run++) {
                            gpu.upload(b,n);int[] queries=new int[30];double[] cpu=new double[30],times=new double[30];
                            try(var view=world?atlas.view(0,0,0,coarse):null) {
                                for(int i=0;i<queries.length;i++) {
                                    queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);
                                    long start=System.nanoTime();if(world)gpu.stepWorld(.05f,view);else gpu.step(.05f);
                                    cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                                }
                            }
                            for(int i=0;i<queries.length;i++) {
                                times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);
                                samples.add(n+","+scenario+","+run+","+i+","+times[i]+","+cpu[i]);
                            }
                            var result=read(gpu);int fallback=0,moving=0;double penetration=0,overlap=0;
                            for(int i=0;i<n;i++) {
                                int p=i*64;
                                check(Float.isFinite(result.getFloat(p)) && Float.isFinite(result.getFloat(p+4)) && Float.isFinite(result.getFloat(p+8)),"world benchmark nonfinite");
                                if(result.getFloat(p+60)<0)fallback++;
                                if(Math.abs(result.getFloat(p+16))+Math.abs(result.getFloat(p+20))+Math.abs(result.getFloat(p+24))>.0001f)moving++;
                                if(contacts) {
                                    penetration=Math.max(penetration,1.5-result.getFloat(p+4));
                                    for(int offset:new int[]{1,64,4096})if(i+offset<n) {
                                        int q=(i+offset)*64;double dx=1-Math.abs(result.getFloat(p)-result.getFloat(q)),
                                                dy=1-Math.abs(result.getFloat(p+4)-result.getFloat(q+4)),dz=1-Math.abs(result.getFloat(p+8)-result.getFloat(q+8));
                                        if(dx>0 && dy>0 && dz>0)overlap=Math.max(overlap,Math.min(dx,Math.min(dy,dz)));
                                    }
                                }
                            }
                            check(fallback==0,"world benchmark hid missing coverage with fallback");
                            var stats=world?atlas.stats():null;
                            long bytes=world?343L*2*(PackageCollisionGpu.CELL_BYTES+32)+4L*1024*PackageCollisionGpu.HEAD_BYTES:0;
                            String row=n+","+scenario+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                                    +","+bytes+","+(world?stats.p50Nanos()/1e6:0)+","+(world?stats.p95Nanos()/1e6:0)+","+(world?stats.overruns():0)
                                    +","+uploadCalls+","+(world?stats.uploadedBytes():0)+","+fallback+","+moving+","+penetration+","+overlap;
                            rows.add(row);System.out.println(row);
                        }
                    }
                }
            }
        }
        Files.write(Path.of("build/package-world-kernels.csv"),rows);Files.write(Path.of("build/package-world-kernel-samples.csv"),samples);
    }
    static void stackBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,scenario,solver,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,support_extra_required_bytes,penetrating_pairs,penetrating_bodies,all_pair_overlap_max,terrain_penetration_max,fallbacks,moving,quality_pass");
        samples.add("count,scenario,solver,run,sample,gpu_ms,cpu_submit_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072}) {
            // The complete trajectory includes negative X/Z section guards under the
            // alternating drive. Missing coverage is a correct pause, not a solver pass.
            try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
                for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
                    check(atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air),"stack benchmark world admission");
                uploadWorld(atlas);
                for(String scenario:List.of("aligned_still","staggered_still","staggered_driven")) {
                    var b=stackBodies(n,64,64,!scenario.equals("aligned_still"));boolean driven=scenario.endsWith("driven");
                    for(String policy:List.of("jacobi4","jacobi16","support4"))for(int run=1;run<=3;run++) {
                        boolean support=policy.equals("support4");int iterations=policy.equals("jacobi16")?16:4;
                        gpu.upload(b,n);
                        try(var view=atlas.view(0,0,0)) {
                            for(int warm=0;warm<50;warm++){if(driven)probe.drive(gpu,warm);gpu.stepWorld(.05f,view,support,iterations);}
                        }
                        GL11.glFinish();int[] queries=new int[40];double[] times=new double[40],cpu=new double[40];
                        try(var view=atlas.view(0,0,0)) {
                            for(int i=0;i<queries.length;i++) {
                                queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();
                                if(driven)probe.drive(gpu,50+i);gpu.stepWorld(.05f,view,support,iterations);
                                cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            }
                        }
                        for(int i=0;i<queries.length;i++) {
                            times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);
                            samples.add(n+","+scenario+","+policy+","+run+","+i+","+times[i]+","+cpu[i]);
                        }
                        var stats=probe.inspect(gpu,1.5f);float terrain=stats.getFloat(24);
                        check(stats.getInt(16)==0,"stack benchmark nonfinite results");
                        check(stats.getInt(12)==0,"stack benchmark did not cover complete trajectory: "+n+" "+scenario+" "+policy);
                        boolean quality=stats.getFloat(4)<.002 && terrain<1e-4 && stats.getInt(12)==0;
                        var row=n+","+scenario+","+policy+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)
                                +","+(support?32L*n+32:0)+","+stats.getInt(0)+","+stats.getInt(8)+","+stats.getFloat(4)+","+terrain+","+stats.getInt(12)+","+stats.getInt(20)+","+(quality?1:0);
                        rows.add(row);System.out.println(row);
                        Files.write(Path.of("build/package-stack-kernels.csv"),rows);Files.write(Path.of("build/package-stack-kernel-samples.csv"),samples);
                    }
                }
            }
        }
    }
    static void stackStress() {
        int n=65536;var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
            uploadWorld(atlas);gpu.upload(stackBodies(n,64,64,true),n);
            for(int step=0;step<120;step++) {
                probe.drive(gpu,step);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
                if(step%10==9) {
                    var stats=probe.inspect(gpu);var r=read(gpu);float top=-1e30f;int printed=0;
                    for(int i=0;i<n;i++) {
                        top=Math.max(top,r.getFloat(i*64+4));
                        if(r.getFloat(i*64+60)<0 && printed++<2)System.out.println("Rejected "+i+" xyz="+r.getFloat(i*64)+","+r.getFloat(i*64+4)+","+r.getFloat(i*64+8));
                    }
                    System.out.println("Stress step="+step+" top="+top+" max="+stats.getFloat(4)+" rejected="+stats.getInt(12));
                }
            }
        }
    }
    static void supportSustainedMotion() {
        int n=131072;var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
                check(atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air),"sustained motion world admission");
            uploadWorld(atlas);gpu.upload(stackBodies(n,64,64,true),n);
            for(var mode:List.of("linked")) {
              gpu.upload(stackBodies(n,64,64,true),n);
              for(int step=0;step<120;step++) {
                probe.drive(gpu,step);try(var view=atlas.view(0,0,0)){gpu.stepWorld(.05f,view,true,4);}
                if(step%20==19) {
                    var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n && stats.getInt(12)==0 && stats.getInt(16)==0,"sustained motion omitted or rejected bodies");
                    check(stats.getFloat(4)<.002 && stats.getFloat(24)<1e-4,"sustained motion contact quality failed");
                    check(stats.getInt(20)>n*.99,"sustained motion replaced activity with frozen bodies");
                }
              }
            }
        }
    }
    static void deltaBenchmark() throws Exception {
        var rows=new ArrayList<String>();rows.add("count,scenario,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms");
        for(int n:new int[]{10000,65536,131072}) {
            ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++){body(b,i,2,3,4,1);deltaMeta(meta,i,1);}int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source)) {
                for(String scenario:List.of("dirty_capture_cancel","unchanged_capture")) {
                    gpu.upload(meta,baseline,n);
                    if(scenario.equals("unchanged_capture")) {
                        var initial=gpu.capture(state,n,0,0,0,n);acknowledge(gpu,initial,captureRecords(initial));gpu.finish(initial);
                    }
                    Runnable frame=()->{var capture=gpu.capture(state,n,0,0,0,n);if(scenario.equals("dirty_capture_cancel"))gpu.cancel(capture);else gpu.finish(capture);};
                    for(int warm=0;warm<20;warm++)frame.run();GL11.glFinish();
                    for(int run=1;run<=3;run++) {
                        int[] queries=new int[60];double[] cpu=new double[60],times=new double[60];
                        for(int i=0;i<queries.length;i++) {
                            queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);
                            long begin=System.nanoTime();frame.run();cpu[i]=(System.nanoTime()-begin)/1e6;
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        }
                        for(int i=0;i<queries.length;i++){times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);}
                        rows.add(n+","+scenario+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95));
                    }
                }
            }finally{GL15.glDeleteBuffers(state);}
        }
        Files.write(Path.of("build/package-gpu-deltas.csv"),rows);for(String row:rows)System.out.println(row);
    }
    static volatile com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket benchmarkPacket;
    static void predictionKernelBenchmark() throws Exception {
        var rows=new ArrayList<String>();var samples=new ArrayList<String>();
        rows.add("count,encoding,scenario,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,predictor_storage_bytes");
        samples.add("count,encoding,scenario,run,sample,gpu_ms,cpu_submit_ms");
        for(int n:new int[]{10000,65536,131072})for(int run=1;run<=3;run++)for(boolean predicted:
                run%2==0?new boolean[]{true,false}:new boolean[]{false,true}) {
            var b=bodies(n);var meta=BufferUtils.createByteBuffer(n*32);var baseline=BufferUtils.createByteBuffer(n*32);
            for(int i=0;i<n;i++){body(b,i,16,16,16,1);deltaMeta(meta,i,1);var q=quantizedBody(b,i);
                int p=i*32;baseline.putInt(p,q.x()).putInt(p+4,q.y()).putInt(p+8,q.z()).putInt(p+12,q.flags());}
            int state=buffer(b);
            try(var gpu=new PackageDeltaGpu(n,PackageGpuValidation::source,true,predicted)) {
                gpu.upload(meta,baseline,n);
                for(int i=0;i<n;i++)b.putFloat(i*64,16.125f);putBuffer(state,b);
                var initial=gpu.capture(state,n,0,0,0,n);acknowledge(gpu,initial,captureRecords(initial));gpu.finish(initial);
                for(int i=0;i<n;i++)b.putFloat(i*64,16.25f);putBuffer(state,b);
                for(String scenario:List.of("dirty_capture_cancel","unchanged_capture")) {
                    if(scenario.equals("unchanged_capture")) {
                        initial=gpu.capture(state,n,0,0,0,n);acknowledge(gpu,initial,captureRecords(initial));gpu.finish(initial);
                    }
                    Runnable frame=()->{var capture=gpu.capture(state,n,0,0,0,n);if(scenario.equals("dirty_capture_cancel"))gpu.cancel(capture);else gpu.finish(capture);};
                    for(int warm=0;warm<30;warm++)frame.run();GL11.glFinish();
                    int[] queries=new int[60];double[] cpu=new double[60],times=new double[60];
                    for(int i=0;i<60;i++) {
                        queries[i]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[i]);long start=System.nanoTime();frame.run();
                        cpu[i]=(System.nanoTime()-start)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    }
                    String mode=predicted?"predicted":"relative";
                    for(int i=0;i<60;i++){times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[i]);
                        samples.add(n+","+mode+","+scenario+","+run+","+i+","+times[i]+","+cpu[i]);}
                    rows.add(n+","+mode+","+scenario+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+(predicted?(long)n*16:0));
                }
            }finally{GL15.glDeleteBuffers(state);}
        }
        Files.write(Path.of("build/package-prediction-kernels.csv"),rows);Files.write(Path.of("build/package-prediction-kernel-samples.csv"),samples);
        for(var row:rows)System.out.println(row);
    }
    static final class PipelineTransport implements PackageDeltaChannel.Transport {
        static final PackageRegion REGION=new PackageRegion(0,0,0);
        PackageDeltaChannel channel;
        String failure;
        final boolean workerPackets;
        final int wireMode;
        PipelineTransport(boolean workerPackets){this(workerPackets,0);}
        PipelineTransport(boolean workerPackets,int wireMode){this.workerPackets=workerPackets;this.wireMode=wireMode;}
        public boolean batchEncoded(){return wireMode!=0;}
        public boolean relativePositions(){return wireMode>=2;}
        public boolean predictedPositions(){return wireMode==3;}
        Object packet(long epoch,long revision,long sequence,ByteBuffer bytes) {
            // Match production's immutable packet copy. No Netty, network, server entity update or render.
            byte[] body=new byte[bytes.remaining()];bytes.get(body);
            int action=wireMode==0?ServerboundPackagePacket.DELTA:wireMode==1?ServerboundPackagePacket.BATCH_DELTA:wireMode==2?ServerboundPackagePacket.RELATIVE_DELTA:ServerboundPackagePacket.PREDICTED_DELTA;
            return new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket(action,0,
                    REGION,epoch,0,null,0,revision,sequence,body);
        }
        public Object prepare(long epoch,long revision,long sequence,ByteBuffer bytes){return workerPackets?packet(epoch,revision,sequence,bytes):null;}
        public boolean send(long epoch,long revision,long sequence,ByteBuffer bytes){return sendPrepared(epoch,revision,sequence,packet(epoch,revision,sequence,bytes),bytes);}
        public boolean sendPrepared(long epoch,long revision,long sequence,Object prepared,ByteBuffer bytes) {
            if(prepared==null)prepared=packet(epoch,revision,sequence,bytes);
            benchmarkPacket=(com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket)prepared;
            if(!channel.acknowledge(epoch,revision,sequence))throw new AssertionError("Benchmark loopback ACK rejected");return true;
        }
        public void failed(String reason){failure=reason;}
    }
    static void pipelineBenchmark() throws Exception {
        pipelineBenchmark(false);
    }
    static void pipelineBenchmark(boolean wireComparison) throws Exception {
        pipelineBenchmark(wireComparison,false);
    }
    static void pipelineBenchmark(boolean wireComparison,boolean predictionComparison) throws Exception {
        long setup=System.nanoTime();
        benchmarkPacket=com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket.capabilities(0);
        System.out.println("Pipeline packet class setup (excluded from warmed samples): "+(System.nanoTime()-setup)/1e6+" ms");
        var summaries=new ArrayList<String>();var samples=new ArrayList<String>();
        summaries.add("count,scenario,run,samples,cpu_total_p50_ms,cpu_total_p95_ms,cpu_peak_call_p50_ms,cpu_peak_call_p95_ms,latency_p50_ms,latency_p95_ms,encoder_work_mean_ms,render_alloc_mean_kib,readback_mean_bytes,wire_body_mean_bytes,packets_mean,ack_dispatch_mean");
        samples.add("count,scenario,run,sample,cpu_total_ms,cpu_peak_call_ms,latency_ms,encoder_work_ms,render_alloc_bytes,readback_bytes,wire_body_bytes,packets,ack_dispatches");
        var threadBean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        boolean allocations=threadBean.isThreadAllocatedMemorySupported();if(allocations)threadBean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var workerNanos=new java.util.concurrent.atomic.AtomicLong();
            var encoder=new PackageDeltaJournal.Encoder(task->executor.execute(()->{
                long start=System.nanoTime();try{task.run();}finally{workerNanos.addAndGet(System.nanoTime()-start);}
            }),4);
            for(int n:new int[]{10000,65536,131072}) {
                ByteBuffer b=bodies(n),meta=BufferUtils.createByteBuffer(n*32),baseline=BufferUtils.createByteBuffer(n*32);
                for(int i=0;i<n;i++) {
                    body(b,i,.5f+(i%128)*.25f,1+(i/128%64)*.5f,.5f+(i/8192)*2,1);
                    b.putFloat(i*64+16,.25f);deltaMeta(meta,i,1);meta.putInt(i*32+20,i);
                }
                int state=buffer(b);
                try {
                    // Alternate order by repetition to reduce fixed warmup/temperature order bias.
                    for(int run=1;run<=3;run++)for(int mode:predictionComparison?(run%2==0?new int[]{5,4}:new int[]{4,5}):wireComparison?
                            (run%2==0?new int[]{4,3,1}:new int[]{1,3,4}):(run%2==0?new int[]{2,1,0}:new int[]{0,1,2})) {
                        boolean batched=mode==2;
                        String scenario=mode==0?"direct_render_packet":mode==1?"direct_worker_packet":mode==2?"gpu_journal_worker_packet"
                                :mode==3?"batch_absolute_worker_packet":mode==4?"batch_relative_worker_packet":"batch_predicted_worker_packet";
                        var transport=new PipelineTransport(mode!=0,mode>=3?mode-2:0);
                        var detector=new PackageDeltaGpu(n,PackageGpuValidation::source,mode>=4,mode==5);
                        try(var channel=new PackageDeltaChannel(detector,n,77,3,encoder,transport,System::nanoTime,batched)) {
                            transport.channel=channel;channel.append(meta,baseline,n);int packets=(n+511)/512;
                            double[] cpu=new double[30],peak=new double[30],latency=new double[30];
                            double workerTotal=0,allocatedTotal=0,readbackTotal=0,wireTotal=0,packetTotal=0,dispatchTotal=0;
                            for(int sample=-15;sample<30;sample++) {
                                for(int i=0;i<n;i++) {
                                    if(predictionComparison) {
                                        float vx=((i%1025)-512)/512f,vy=((i*31+19)%1025-512)/512f,vz=((i*17+37)%1025-512)/512f;
                                        float t=(sample+15)*.05f;int p=i*64;
                                        b.putFloat(p,4.5f+(i%128)*.25f+t*vx).putFloat(p+4,4+(i/128%64)*.5f+t*vy)
                                                .putFloat(p+8,4.5f+(i/8192)*2+t*vz).putFloat(p+16,vx).putFloat(p+20,vy).putFloat(p+24,vz);
                                    }else b.putFloat(i*64,.5f+(i%128)*.25f+((sample&1)==0?.03125f:0));
                                }
                                putBuffer(state,b); // Synthetic motion upload excluded; production solver writes on GPU.
                                var before=channel.stats();long workerStart=workerNanos.get();
                                long allocated=allocations?threadBean.getThreadAllocatedBytes(thread):0;
                                long start=System.nanoTime(),call=start;
                                check(channel.capture(state,n,0,0,0),"benchmark capture skipped");
                                long elapsed=System.nanoTime()-call,total=elapsed,maximum=elapsed;
                                int pumps=0;
                                while(channel.stats().ackedPackets()<before.ackedPackets()+packets && !channel.closed()) {
                                    call=System.nanoTime();channel.pump(256);elapsed=System.nanoTime()-call;total+=elapsed;maximum=Math.max(maximum,elapsed);
                                    if(++pumps>2000)throw new AssertionError("Benchmark did not drain");
                                    if(channel.stats().ackedPackets()<before.ackedPackets()+packets)Thread.sleep(1); // Harness polling cadence, outside CPU totals.
                                }
                                long end=System.nanoTime();var after=channel.stats();
                                check(!channel.closed(),"pipeline benchmark fallback count="+n+", run="+run+", sample="+sample+", elapsed_ms="+(end-start)/1e6+": "+transport.failure);
                                check(after.payloadBytes()-before.payloadBytes()==(long)n*64,"pipeline benchmark lost dirty bodies");
                                long allocationBytes=allocations?threadBean.getThreadAllocatedBytes(thread)-allocated:-1;
                                long worker=workerNanos.get()-workerStart,readback=after.payloadBytes()-before.payloadBytes(),wire=after.wireBytes()-before.wireBytes();
                                long sent=after.sentPackets()-before.sentPackets(),dispatches=after.ackDispatches()-before.ackDispatches();
                                if(sample>=0) {
                                    cpu[sample]=total/1e6;peak[sample]=maximum/1e6;latency[sample]=(end-start)/1e6;
                                    workerTotal+=worker/1e6;allocatedTotal+=allocationBytes/1024.0;readbackTotal+=readback;wireTotal+=wire;packetTotal+=sent;dispatchTotal+=dispatches;
                                    samples.add(n+","+scenario+","+run+","+sample+","+cpu[sample]+","+peak[sample]+","+latency[sample]+","+worker/1e6+","+allocationBytes+","+readback+","+wire+","+sent+","+dispatches);
                                }
                            }
                            var clean=detector.capture(state,n,0,0,0,n);check(captureRecords(clean).remaining()==0,"pipeline benchmark baseline mismatch");detector.finish(clean);
                            String summary=n+","+scenario+","+run+",30,"+percentile(cpu,.5)+","+percentile(cpu,.95)+","+percentile(peak,.5)+","+percentile(peak,.95)+","+percentile(latency,.5)+","+percentile(latency,.95)+","+workerTotal/30+","+allocatedTotal/30+","+readbackTotal/30+","+wireTotal/30+","+packetTotal/30+","+dispatchTotal/30;
                            summaries.add(summary);System.out.println(summary);
                        }
                    }
                }finally{GL15.glDeleteBuffers(state);}
            }
        }
        String stem=predictionComparison?"package-delta-predicted-pipeline":wireComparison?"package-delta-batch-pipeline":"package-delta-pipeline";
        Files.write(Path.of("build/"+stem+".csv"),summaries);Files.write(Path.of("build/"+stem+"-samples.csv"),samples);
    }

    static PackageMovingGeometry.Pose movingPose(double x,double y,double z,double angle,double sx,double sy,double sz) {
        double c=Math.cos(angle),s=Math.sin(angle);
        return new PackageMovingGeometry.Pose(c*sx,0,-s*sx,0,sy,0,s*sz,0,c*sz,x,y,z);
    }
    static final class MovingSource implements PackageMovingCollisionCache.Source {
        final PackageMovingGeometry.Key key=
            new PackageMovingGeometry.Key(0,UUID.randomUUID());
        long revision=1;boolean alive=true;
        List<PackageMovingGeometry.Box> boxes;
        PackageMovingGeometry.Pose previous=movingPose(0,0,0,0,1,1,1),current=previous;
        MovingSource(float x0,float y0,float z0,float x1,float y1,float z1){boxes=List.of(new PackageMovingGeometry.Box(x0,y0,z0,x1,y1,z1,.6f,0));}
        public PackageMovingGeometry.Key key(){return key;}
        public long revision(){return revision;}public boolean alive(){return alive;}
        public PackageMovingGeometry.Bounds bounds(){
            var b=boxes.getFirst();return new PackageMovingGeometry.Bounds(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());
        }
        public PackageMovingGeometry.Pose pose(boolean old){return old?previous:current;}
        public PackageMovingCollisionCache.Cursor open(){
            check(Thread.currentThread()==captureOwner,"moving world fixture off owner");var rows=boxes.iterator();
            return new PackageMovingCollisionCache.Cursor(){public boolean hasNext(){return rows.hasNext();}public List<PackageMovingGeometry.Box> next(){return List.of(rows.next());}};
        }
        final Thread captureOwner=Thread.currentThread();
    }
    static PackageMovingCollisionCache movingCache(MovingSource...sources){
        var cache=new PackageMovingCollisionCache(Runnable::run,64,()->0L);
        for(var source:sources)check(cache.offer(source),"moving source admission");
        for(int i=0;i<3;i++)cache.tick(1);
        for(var entry:cache.entries())check(entry.snapshot()!=null,"moving geometry ready");return cache;
    }
    static void movingStep(PackagePhysicsGpu gpu,PackageCollisionGpu world,
                           PackageMovingCollisionGpu atlas,
                           PackageMovingCollisionCache cache) {
        cache.tick(1);atlas.sync(cache.entries());atlas.pump(262144,Long.MAX_VALUE);
        var moving=atlas.views(cache.entries(),cache.posesReady(),0,0,0);
        try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,moving);}
        finally{atlas.endViews(moving);}
        // Fixture-only synchronization: the production atlas must never wait.
        GL11.glFinish();
    }
    static void movingGeometryContract() {
        var slabs=new ArrayList<PackageMovingGeometry.Box>();
        for(int z=0;z<256;z++)for(int x=0;x<256;x++)slabs.add(new PackageMovingGeometry.Box(x,0,z,x+1,1,z+1,.6f,0));
        var merged=PackageMovingGeometry.bake(1,slabs);
        check(merged.count()==1,"large exact platform not merged");
        var data=merged.nodes();check(data.isReadOnly()&&data.getInt(12)==1&&data.getInt(28)==1,"moving node layout");
        slabs.clear();slabs.add(new PackageMovingGeometry.Box(0,0,0,1,1,1,.6f,0));
        slabs.add(new PackageMovingGeometry.Box(2,0,0,3,1,1,.6f,0));
        check(PackageMovingGeometry.bake(2,slabs).count()==3,"exact merge filled a hole");
        boolean invalid=false;try{new PackageMovingGeometry.Pose(1,0,0,.1,1,0,0,0,1,0,0,0);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"shear must request pause");
        var pose=movingPose(30000000.25,-30000000.5,30000000.75,.7,2,3,4);var bytes=BufferUtils.createByteBuffer(64);
        pose.put(bytes,30000000,-30000000,30000000);
        check(bytes.getFloat(48)==.25f&&bytes.getFloat(52)==-.5f&&bytes.getFloat(56)==.75f,"double local origin precision");
        var jobs=new ArrayList<Runnable>();var source=new MovingSource(2,1,2,14,2,14);
        var cache=new PackageMovingCollisionCache(jobs::add,1,()->0L);
        cache.offer(source);cache.tick(1);check(jobs.size()==1,"moving immutable bake scheduling");
        source.revision++;cache.invalidate(source.key);jobs.removeFirst().run();cache.tick(1);
        check(cache.entries().iterator().next().snapshot()==null,"obsolete moving bake published");check(jobs.size()==1,"moving replacement bake not scheduled");
        jobs.removeFirst().run();cache.tick(1);check(cache.entries().iterator().next().snapshot()!=null,"moving replacement missing");
        cache.tick(0);check(!cache.posesReady(),"zero capture budget retained old pose coverage");
        cache.clear();check(cache.offer(source),"moving clear");cache.tick(1);
        check(cache.entries().iterator().next().identity==2,"moving identities reused");
    }
    static void movingContacts() {
        movingGeometryContract();var air=snapshot((s,i)->WORLD_AIR);
        // This tree has more than the old 8192-node shader visit budget. A valid
        // maximum-sized Sable-style plot must not freeze a body that is inside its
        // conservative coarse bounds while the rotating sweep culls the whole BVH.
        var denseBoxes=new ArrayList<PackageMovingGeometry.Box>(4097);
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            float x0=-.8f+x*.1f,y0=-.8f+y*.1f,z0=-.8f+z*.1f;
            denseBoxes.add(new PackageMovingGeometry.Box(x0,y0,z0,x0+.04f,y0+.04f,z0+.04f,.6f,0));
        }
        denseBoxes.add(new PackageMovingGeometry.Box(.9f,0,0,.94f,.04f,.04f,.6f,0));
        var denseSource=new MovingSource(0,0,0,1,1,1);denseSource.boxes=List.copyOf(denseBoxes);
        denseSource.previous=movingPose(4,4,4,0,1,1,1);denseSource.current=movingPose(4,4,4,.01,1,1,1);
        var denseCache=movingCache(denseSource);
        check(denseCache.entries().iterator().next().snapshot().count()>8192,"dense moving BVH did not exceed the legacy traversal limit");
        try(var atlas=new PackageMovingCollisionGpu();var world=new PackageCollisionGpu(27,1);
            var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            atlas.sync(denseCache.entries());atlas.pump(1_000_000,Long.MAX_VALUE);
            cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,5.4f,4,4,1);gpu.upload(body,1);
            movingStep(gpu,world,atlas,denseCache);var result=read(gpu);
            check(result.getFloat(60)>=0,"valid dense moving BVH froze a touching body at the old traversal limit");
            check(Float.isFinite(result.getFloat(0))&&Float.isFinite(result.getFloat(4))&&Float.isFinite(result.getFloat(8)),"dense moving BVH produced a non-finite body");
        }
        var dynamicBoxes=PackageMovingDynamicBoxes.capture(sink->sink.add(0,0,0,1,.5,1),0,0,0,0,0,0,.6f);
        var dynamicSource=new MovingSource(0,0,0,1,.5f,1);dynamicSource.boxes=dynamicBoxes;
        dynamicSource.previous=dynamicSource.current=movingPose(4,4,4,0,1,1,1);var dynamicCache=movingCache(dynamicSource);
        try(var atlas=new PackageMovingCollisionGpu();var world=new PackageCollisionGpu(27,1);
            var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)) {
            cubeWorld(world,air,air,0,0,0);var b=bodies(1);body(b,0,4.5f,5,4.5f,1);gpu.upload(b,1);
            movingStep(gpu,world,atlas,dynamicCache);var result=read(gpu);
            check(result.getFloat(60)>=0,"validated custom dynamic voxel unexpectedly requested pause");
            check(result.getFloat(4)>=5-.001f,"custom dynamic voxel box was not supplied to GPU contacts: "+result.getFloat(4));
        }
        for(var mode:List.of("linked"))for(int n:new int[]{1,63,64,65}) {
            var source=new MovingSource(-2,1,-2,32,2,32);var cache=movingCache(source);
            try(var atlas=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source)) {
                cubeWorld(world,air,air,0,0,0);var bodies=bodies(n);
                for(int i=0;i<n;i++)body(bodies,i,2+i%9*3,2.5f,2+i/9*3,1);gpu.upload(bodies,n);
                movingStep(gpu,world,atlas,cache);
                for(int step=0;step<10;step++){
                    source.previous=source.current;source.current=movingPose((step+1)*.1,(step+1)*.02,0,0,1,1,1);
                    movingStep(gpu,world,atlas,cache);
                }
                var result=read(gpu);for(int i=0;i<n;i++){
                    check(Math.abs(result.getFloat(i*64)-(bodies.getFloat(i*64)+1))<.003,"moving carry x / tail / index "+i+" "+mode+" x="+result.getFloat(i*64)+" y="+result.getFloat(i*64+4)+" vx="+result.getFloat(i*64+16)+" flag="+result.getFloat(i*64+60));
                    check(Math.abs(result.getFloat(i*64+4)-2.7)<.003,"moving carry y "+i);
                    check(result.getFloat(i*64+60)>=0&&result.getFloat(i*64+28)==1,"moving platform contact fallback "+i);
                    check(Math.abs(result.getFloat(i*64+16)-2)<.003,"moving surface velocity "+i);
                }
                // A dirty source must revoke a view that was already opened.
                var views=atlas.views(cache.entries(),true,0,0,0);cache.invalidate(source.key);
                try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,views);}finally{atlas.endViews(views);}
                check(read(gpu).getFloat(60)<0,"old geometry view survived CPU invalidation");
            }
        }
        // Translating wall crosses a body even though both endpoint boxes miss it.
        for(float speed:new float[]{4,8}) {
            var source=new MovingSource(-.25f,-2,-2,.25f,2,2);
            source.previous=movingPose(2,5,5,0,1,1,1);source.current=movingPose(2+speed,5,5,0,1,1,1);var cache=movingCache(source);
            try(var atlas=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
                cubeWorld(world,air,air,0,0,0);var bodies=bodies(1);body(bodies,0,4,5,5,1);gpu.upload(bodies,1);
                movingStep(gpu,world,atlas,cache);var result=read(gpu);
                check(result.getFloat(60)>=0,"moving CCD unexpected pause");
                check(result.getFloat(0)>=2+speed+.749f,"moving wall tunneled "+result.getFloat(0));
            }
        }
        // OBB SAT: a rotated long box's AABB corner must stay empty.
        var source=new MovingSource(-3,-1,-.1f,3,1,.1f);source.previous=source.current=movingPose(6,5,6,Math.PI/4,1,1,1);var cache=movingCache(source);
        try(var atlas=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var bodies=bodies(1);body(bodies,0,7.5f,5,7.5f,1);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache);var result=read(gpu);
            check(result.getFloat(60)>=0&&Math.abs(result.getFloat(0)-7.5)<1e-5&&Math.abs(result.getFloat(8)-7.5)<1e-5,"OBB empty corner collision");
            // An unknown scene always hands back rather than simulating against air.
            gpu.upload(bodies,1);var views=atlas.unavailableViews();
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,views);}finally{atlas.endViews(views);}
            check(read(gpu).getFloat(60)<0,"unknown moving scene treated as air");
        }
        System.out.println("Moving collision fixtures passed");
    }


    static PackageMovingGeometry.Pose arbitraryPose(double ax,double ay,double az,double sx,double sy,double sz) {
        var r=new org.joml.Matrix3d().rotateXYZ(ax,ay,az).scale(sx,sy,sz);
        return new PackageMovingGeometry.Pose(r.m00,r.m01,r.m02,r.m10,r.m11,r.m12,r.m20,r.m21,r.m22,6,6,6);
    }
    static double referenceMovingGap(float x,float y,float z,float ex,float ey,float ez,MovingSource source) {
        var p=source.current;var b=source.boxes.getFirst();var centre=p.transform((b.x0()+b.x1())*.5,(b.y0()+b.y1())*.5,(b.z0()+b.z1())*.5);
        var delta=new org.joml.Vector3d(x,y,z).sub(centre);var columns=new org.joml.Vector3d[]{new org.joml.Vector3d(p.xx(),p.xy(),p.xz()),new org.joml.Vector3d(p.yx(),p.yy(),p.yz()),new org.joml.Vector3d(p.zx(),p.zy(),p.zz())};
        var world=new org.joml.Vector3d[]{new org.joml.Vector3d(0,1,0),new org.joml.Vector3d(1,0,0),new org.joml.Vector3d(0,0,1)};
        var axes=new ArrayList<org.joml.Vector3d>();Collections.addAll(axes,world);Collections.addAll(axes,columns);
        for(var a:world)for(var c:columns)axes.add(new org.joml.Vector3d(a).cross(c));
        double gap=-Double.MAX_VALUE;double[] half={(b.x1()-b.x0())*.5,(b.y1()-b.y0())*.5,(b.z1()-b.z0())*.5};
        for(var axis:axes){if(axis.lengthSquared()<1e-10)continue;var a=new org.joml.Vector3d(axis).normalize();double radius=Math.abs(a.x)*ex+Math.abs(a.y)*ey+Math.abs(a.z)*ez;
            for(int k=0;k<3;k++)radius+=Math.abs(a.dot(columns[k]))*half[k];gap=Math.max(gap,Math.abs(a.dot(delta))-radius);}
        return gap;
    }
    static void movingReference() {
        var air=snapshot((s,i)->WORLD_AIR);var random=new Random(628142);
        var source=new MovingSource(-2,-1.1f,-.25f,2,1.1f,.25f);var cache=movingCache(source);
        try(var atlas=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);
            for(int test=0;test<150;test++){
                source.previous=source.current=arbitraryPose(random.nextDouble()*6.28,random.nextDouble()*6.28,random.nextDouble()*6.28,.5+random.nextDouble()*2,.5+random.nextDouble()*2,.5+random.nextDouble()*2);
                var bodies=bodies(1);float x=6+(random.nextFloat()-.5f)*7,y=6+(random.nextFloat()-.5f)*7,z=6+(random.nextFloat()-.5f)*7;
                body(bodies,0,x,y,z,1);bodies.putFloat(32,.25f).putFloat(36,.3f).putFloat(40,.4f);gpu.upload(bodies,1);
                double before=referenceMovingGap(x,y-.0784f,z,.25f,.3f,.4f,source);
                movingStep(gpu,world,atlas,cache);var result=read(gpu);
                check(result.getFloat(60)>=0,"random OBB unexpected fallback "+test);
                check(referenceMovingGap(result.getFloat(0),result.getFloat(4),result.getFloat(8),.25f,.3f,.4f,source)>-3e-4,"random OBB remained penetrated "+test);
                if(before>.001)check(Math.abs(result.getFloat(0)-x)<3e-5&&Math.abs(result.getFloat(8)-z)<3e-5,"random OBB false positive "+test);
            }
            source.previous=movingPose(6,6,6,0,1,1,1);source.current=movingPose(6,6,6,Math.PI/2,1,1,1);
            var bodies=bodies(1);body(bodies,0,7.3f,6,4.7f,1);bodies.putFloat(32,.15f).putFloat(36,.2f).putFloat(40,.15f);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache);var result=read(gpu);
            check(result.getFloat(60)>=0,"rotating CCD pause");
            check(Math.abs(result.getFloat(0)-7.3)>1e-3||Math.abs(result.getFloat(8)-4.7)>1e-3,"rotating wall missed intermediate pose");
            source.boxes=List.of(new PackageMovingGeometry.Box(-4,0,-4,4,1,4,.6f,0));source.revision++;cache.invalidate(source.key);cache.tick(1);cache.tick(1);
            source.previous=source.current=movingPose(6,4,6,0,1,1,1);body(bodies,0,8,5.5f,6,1);bodies.putFloat(32,.5f).putFloat(36,.5f).putFloat(40,.5f);gpu.upload(bodies,1);
            movingStep(gpu,world,atlas,cache);
            for(int step=1;step<=12;step++){source.previous=source.current;source.current=movingPose(6,4,6,step*.025,1+step*.01,1,1);movingStep(gpu,world,atlas,cache);}
            result=read(gpu);var expected=source.current.transform(2,1,0);
            check(result.getFloat(60)>=0,"rotating carried body fallback");
            check(Math.abs(result.getFloat(0)-expected.x)<.015&&Math.abs(result.getFloat(8)-expected.z)<.015,"rotating/scaled support drift");
        }
    }


    static void movingWorld(PackageCollisionGpu world,boolean staticFloor) {
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)
            check(world.offer(new PackageCollisionCache.Section(x,y,z),staticFloor&&y==0?floor:air),"moving full world admission");
        uploadWorld(world);
    }
    static MovingSource[] movingPlatforms(int count) {
        var sources=new MovingSource[count];
        for(int i=0;i<count;i++){int width=count==16?4:1;float span=72f/width;
            sources[i]=new MovingSource(i%width*span-1,0,i/width*span-1,(i%width+1)*span-1,1,(i/width+1)*span-1);}
        return sources;
    }
    static void movingCapacity() {
        int n=131072;var source=movingPlatforms(1);var cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(448,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)){
            movingWorld(world,false);gpu.upload(stackBodies(n,64,64,true),n);
            for(int frame=0;frame<60;frame++){
                source[0].previous=source[0].current;source[0].current=movingPose((frame+1)*.003,0,0,0,1,1,1);
                probe.drive(gpu,frame);movingStep(gpu,world,moving,cache);
                if(frame%10==9){var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n&&stats.getInt(12)==0&&stats.getInt(16)==0,"moving capacity omitted/nonfinite/fallback");
                    check(stats.getFloat(4)<.002&&stats.getFloat(24)<1e-4,"moving capacity overlap/plane frame="+frame+" overlap="+stats.getFloat(4)+" plane="+stats.getFloat(24));
                    check(stats.getInt(20)>n*.99,"moving capacity simulated sleeping stand-ins");
                }
            }
            check(moving.uploadedBytes()==48,"rigid motion rebuilt local geometry");
        }
    }
    static void movingBenchmark() throws Exception {
        var rows=new ArrayList<String>();rows.add("count,structures,run,gpu_p50_ms,gpu_p95_ms,cpu_submit_p50_ms,cpu_submit_p95_ms,pose_capture_p50_ms,pose_capture_p95_ms,geometry_uploaded_bytes,fallbacks,moving,all_pair_overlap_max,plane_penetration_max");
        var samples=new ArrayList<String>();samples.add("count,structures,run,sample,gpu_ms,cpu_submit_ms,pose_capture_ms");
        for(int n:new int[]{10000,65536,131072})for(int structures:new int[]{0,1,16}) {
            var sources=movingPlatforms(structures);var cache=movingCache(sources);
            try(var moving=new PackageMovingCollisionGpu();
                var world=new PackageCollisionGpu(448,1);var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var probe=new ContactProbe(n)){
                movingWorld(world,structures==0);moving.sync(cache.entries());moving.pump(262144,Long.MAX_VALUE);
                for(int run=0;run<=3;run++){
                    gpu.upload(stackBodies(n,64,64,true),n);int count=run==0?20:30;
                    double[] cpu=new double[count],times=new double[count],capture=new double[count];int[] queries=new int[count];
                    for(int frame=0;frame<count;frame++){
                        for(var source:sources){source.previous=frame==0?movingPose(0,0,0,0,1,1,1):source.current;source.current=movingPose((frame+1)*.003,0,0,0,1,1,1);}
                        long captureStart=System.nanoTime();cache.tick(1);capture[frame]=(System.nanoTime()-captureStart)/1e6;
                        probe.drive(gpu,frame);queries[frame]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[frame]);long submit=System.nanoTime();
                        var views=moving.views(cache.entries(),cache.posesReady(),0,0,0);
                        try(var view=world.view(0,0,0)){
                            if(structures==0)gpu.stepWorld(.05f,view,true,4);
                            else gpu.stepWorldMoving(view,4,views);
                        }finally{moving.endViews(views);}
                        cpu[frame]=(System.nanoTime()-submit)/1e6;GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                        // Timing reads occur after the submitted interval. They keep
                        // synthetic pose banks available, never enter runtime code.
                        times[frame]=GL33.glGetQueryObjectui64(queries[frame],GL15.GL_QUERY_RESULT)/1e6;GL15.glDeleteQueries(queries[frame]);
                        if(run>0)samples.add(n+","+structures+","+run+","+frame+","+times[frame]+","+cpu[frame]+","+capture[frame]);
                    }
                    if(run==0)continue;var stats=probe.inspect(gpu,1.5f);
                    check(stats.getInt(28)==n&&stats.getInt(12)==0&&stats.getInt(16)==0,"moving benchmark omitted/fallback/nonfinite n="+n+" structures="+structures+" rejected="+stats.getInt(12));
                    check(stats.getFloat(4)<.002&&stats.getFloat(24)<1e-4,"moving benchmark quality n="+n+" structures="+structures+" overlap="+stats.getFloat(4)+" plane="+stats.getFloat(24));
                    check(stats.getInt(20)>n*.99,"moving benchmark frozen stand-ins");
                    var row=n+","+structures+","+run+","+percentile(times,.5)+","+percentile(times,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+percentile(capture,.5)+","+percentile(capture,.95)+","+moving.uploadedBytes()+","+stats.getInt(12)+","+stats.getInt(20)+","+stats.getFloat(4)+","+stats.getFloat(24);
                    rows.add(row);System.out.println(row);
                }
            }
        }
        Files.write(Path.of("build/package-moving-kernels.csv"),rows);Files.write(Path.of("build/package-moving-kernel-samples.csv"),samples);
    }


    static void movingLifecycle() {
        var air=snapshot((s,i)->WORLD_AIR);var source=new MovingSource(2,1,2,14,2,14);var cache=movingCache(source);
        var complete=new java.util.concurrent.atomic.AtomicBoolean();
        try(var moving=new PackageMovingCollisionGpu(fence->complete.get()?GL32.GL_ALREADY_SIGNALED:GL32.GL_TIMEOUT_EXPIRED);
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,4,2.5f,4,1);
            for(int frame=0;frame<5;frame++){
                gpu.upload(body,1);movingStep(gpu,world,moving,cache);
                check((read(gpu).getFloat(60)<0)==(frame==4),"four moving banks overwritten or waited");
            }
            check(moving.skippedViews()==1,"moving full-ring accounting");
            moving.tickRate(200);
            for(int frame=0;frame<36;frame++){gpu.upload(body,1);movingStep(gpu,world,moving,cache);check(read(gpu).getFloat(60)>=0,"200 TPS pose bank growth stalled a valid interval");}
            gpu.upload(body,1);movingStep(gpu,world,moving,cache);check(read(gpu).getFloat(60)<0,"grown moving ring overwrote one of forty in-flight intervals");
            complete.set(true);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache);check(read(gpu).getFloat(60)>=0,"completed moving bank did not recover");
            var views=moving.views(cache.entries(),true,0,0,0);boolean rejected=false;
            try(var view=world.view(0,0,0)){try{gpu.stepWorldMoving(view,4,views);}catch(IllegalStateException duplicate){rejected=true;}}finally{moving.endViews(views);}
            check(rejected,"same tick applied twice through a new view");
            gpu.upload(body,1);views=moving.views(cache.entries(),true,0,0,0);cache.clear();
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,views);}finally{moving.endViews(views);}
            check(read(gpu).getFloat(60)<0,"world clear retained an old moving identity");
        }
        source=new MovingSource(2,1,2,14,2,14);cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,8,2.5f,8,1);gpu.upload(body,1);moving.sync(cache.entries());
            // No pump: source geometry exists on CPU but was never made visible.
            var views=moving.views(cache.entries(),true,0,0,0);
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,views);}finally{moving.endViews(views);}
            var unuploaded=read(gpu);
            check(unuploaded.getFloat(60)<0,"unuploaded shape treated as bounded air: sentinel="+unuploaded.getFloat(60)
                    +" position="+unuploaded.getFloat(0)+","+unuploaded.getFloat(4)+","+unuploaded.getFloat(8)
                    +" velocity="+unuploaded.getFloat(16)+","+unuploaded.getFloat(20)+","+unuploaded.getFloat(24));
            // Missing geometry is local to the source's swept bounds; distant bodies
            // must keep simulating while an unrelated plot's BVH upload is pending.
            cache.tick(1);body(body,0,20,5,20,1);gpu.upload(body,1);moving.sync(cache.entries());
            views=moving.views(cache.entries(),true,0,0,0);
            try(var view=world.view(0,0,0)){gpu.stepWorldMoving(view,4,views);}finally{moving.endViews(views);}
            check(read(gpu).getFloat(60)>=0,"distant body paused by an unuploaded moving source");
            source.boxes=List.of(new PackageMovingGeometry.Box(2,1,2,14,2,14,.6f,PackageCollisionCache.UNSUPPORTED));
            source.revision++;cache.invalidate(source.key);cache.tick(1);cache.tick(1);body(body,0,4,2.5f,4,1);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache);check(read(gpu).getFloat(60)<0,"callback/hazard box accepted");
        }
        var ceiling=snapshot((s,i)->i>>>8==4?shape(1,.6f):WORLD_AIR);
        source=new MovingSource(2,0,2,14,1,14);cache=movingCache(source);
        try(var moving=new PackageMovingCollisionGpu();
            var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
            cubeWorld(world,air,ceiling,0,0,0);var body=bodies(1);body(body,0,4,1.5f,4,1);gpu.upload(body,1);
            movingStep(gpu,world,moving,cache);
            source.previous=source.current;source.current=movingPose(0,4,0,0,1,1,1);
            movingStep(gpu,world,moving,cache);var crushed=read(gpu);
            check(crushed.getFloat(60)>=0&&crushed.getFloat(4)+.5f<4,
                    "a blocked moving platform must leave its package active and outside static terrain: sentinel="+crushed.getFloat(60)
                            +" position="+crushed.getFloat(0)+","+crushed.getFloat(4)+","+crushed.getFloat(8));
        }
    }
    static void movingFriction() {
        var air=snapshot((s,i)->WORLD_AIR);
        for(float friction:new float[]{0,.6f,.98f,1.2f})for(boolean seam:new boolean[]{false,true}){
            var first=new MovingSource(-1,0,-1,seam?5:15,1,15);first.boxes=List.of(new PackageMovingGeometry.Box(-1,0,-1,seam?5:15,1,15,friction,0));
            var second=new MovingSource(5,0,-1,15,1,15);second.boxes=List.of(new PackageMovingGeometry.Box(5,0,-1,15,1,15,friction,0));
            var cache=seam?movingCache(first,second):movingCache(first);
            try(var moving=new PackageMovingCollisionGpu();var world=new PackageCollisionGpu(27,1);var gpu=new PackagePhysicsGpu(1,2,PackageGpuValidation::source)){
                cubeWorld(world,air,air,0,0,0);var body=bodies(1);body(body,0,4.7f,1.5f,4,1);body.putFloat(16,1);gpu.upload(body,1);
                movingStep(gpu,world,moving,cache);var result=read(gpu);
                check(result.getFloat(60)>=0,"moving friction pause");check(Math.abs(result.getFloat(16)-.98f*friction)<1e-5,"moving friction applied more than once or clamped: "+friction+" seam="+seam+" vx="+result.getFloat(16));
            }
        }
    }

    /** Same initial state, impulses, frequency and quality checks as native/packages.
     * A completed-step wall measurement is distinct from GPU timer and CPU submit.
     * This explicit benchmark may wait; no production render/main thread does. */
    static void backendBenchmark() throws Exception {
        var rows=new ArrayList<String>();var raw=new ArrayList<String>();
        rows.add("backend,count,scenario,hz,iterations,run,step_wall_p50_ms,step_wall_p95_ms,gpu_p50_ms,gpu_p95_ms,submit_cpu_p50_ms,submit_cpu_p95_ms,overlap_max,ground_max,penetrating_pairs,moving,nonfinite,fallbacks,quality_pass");
        raw.add("backend,count,scenario,hz,iterations,run,sample,step_wall_ms,gpu_ms,submit_cpu_ms");
        var air=snapshot((s,i)->WORLD_AIR);var floor=snapshot((s,i)->i>>>8==0?shape(1,.6f):WORLD_AIR);
        for(int n:new int[]{10000,65536,131072})try(var gpu=new PackagePhysicsGpu(n,2,PackageGpuValidation::source);var atlas=new PackageCollisionGpu(448,1);var probe=new ContactProbe(n)) {
            for(int x=-1;x<7;x++)for(int y=-3;y<4;y++)for(int z=-1;z<7;z++)atlas.offer(new PackageCollisionCache.Section(x,y,z),y==0?floor:air);
            uploadWorld(atlas);
            for(int hz:new int[]{20,30,60})for(int run=1;run<=3;run++) {
                var modes=new String[]{"linked"};
                for(int order=0;order<modes.length;order++) {
                    var mode=modes[(order+run-1)%modes.length];String backend="compute_"+mode.toLowerCase(Locale.ROOT);
                    gpu.upload(stackBodies(n,64,64,true),n);float dt=1f/hz;
                    try(var view=atlas.view(0,0,0)){for(int warm=0;warm<50;warm++){probe.drive(gpu,warm);gpu.stepWorld(dt,view,true,4);}}
                    GL11.glFinish();double[] wall=new double[40],timing=new double[40],cpu=new double[40];int query=GL15.glGenQueries();
                    var first=probe.inspect(gpu,1.5f);int pairs=first.getInt(0),fallbacks=first.getInt(12),nonfinite=first.getInt(16),moving=first.getInt(20),valid=first.getInt(28);
                    float overlap=first.getFloat(4),ground=first.getFloat(24);
                    try(var view=atlas.view(0,0,0)) {
                        for(int i=0;i<40;i++) {
                            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);long begin=System.nanoTime();
                            probe.drive(gpu,50+i);gpu.stepWorld(dt,view,true,4);cpu[i]=(System.nanoTime()-begin)/1e6;
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL11.glFinish();wall[i]=(System.nanoTime()-begin)/1e6;
                            timing[i]=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT)/1e6;
                            raw.add(backend+","+n+",staggered_driven,"+hz+",4,"+run+","+i+","+wall[i]+","+timing[i]+","+cpu[i]);
                            if((i+1)%8==0) {
                                // Outside timed samples; preserve transient failures.
                                var stats=probe.inspect(gpu,1.5f);
                                pairs=Math.max(pairs,stats.getInt(0));fallbacks=Math.max(fallbacks,stats.getInt(12));nonfinite=Math.max(nonfinite,stats.getInt(16));
                                moving=Math.min(moving,stats.getInt(20));valid=Math.min(valid,stats.getInt(28));
                                overlap=Math.max(overlap,stats.getFloat(4));ground=Math.max(ground,stats.getFloat(24));
                            }
                        }
                    }finally{GL15.glDeleteQueries(query);}
                    boolean quality=fallbacks==0&&nonfinite==0&&valid==n&&overlap<.002&&ground<1e-4&&moving>n*.99;
                    var row=backend+","+n+",staggered_driven,"+hz+",4,"+run+","+percentile(wall,.5)+","+percentile(wall,.95)+","+percentile(timing,.5)+","+percentile(timing,.95)+","+percentile(cpu,.5)+","+percentile(cpu,.95)+","+overlap+","+ground+","+pairs+","+moving+","+nonfinite+","+fallbacks+","+(quality?1:0);
                    rows.add(row);System.out.println(row);
                    Files.write(Path.of("build/package-backend-compute.csv"),rows);Files.write(Path.of("build/package-backend-compute-samples.csv"),raw);
                }
            }
        }
    }

    public static void main(String[] args)throws Exception {
        irisNativeTransform=Arrays.asList(args).contains("--iris-transform");
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(GLFW.glfwInit(),"GLFW init");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        long window=GLFW.glfwCreateWindow(64,64,"Package GPU validation",0,0);check(window!=0,"GL context");
        org.lwjgl.system.Callback debug=null;
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            if(Arrays.asList(args).contains("--prediction-pipeline-only")) {
                predictionKernelBenchmark();pipelineBenchmark(true,true);
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"prediction benchmark GL error");
                System.out.println("Package prediction pipeline: "+checks+" assertions passed");return;
            }
            if(Arrays.asList(args).contains("--gl-debug")) {
                debug=GLUtil.setupDebugMessageCallback(System.err);
                GL43.glDebugMessageControl(GL43.GL_DONT_CARE,GL43.GL_DONT_CARE,GL43.GL_DONT_CARE,(IntBuffer)null,false);
                GL43.glDebugMessageControl(GL43.GL_DONT_CARE,GL43.GL_DEBUG_TYPE_ERROR,GL43.GL_DONT_CARE,(IntBuffer)null,true);
            }
            System.out.println(GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            if(Arrays.asList(args).contains("--world-prefetch-only")) {
                worldPrefetch();check(GL11.glGetError()==GL11.GL_NO_ERROR,"world prefetch GL error");
                System.out.println("Package world prefetch GPU: "+checks+" assertions passed");return;
            }
            if(Arrays.asList(args).contains("--world-prefetch-benchmark")) {
                worldPrefetchBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"world prefetch benchmark GL error");return;
            }
            if(Arrays.asList(args).contains("--repair-only")){
                continuousFreeAcquisitions();machineOutputProgress();machineEnvironmentConfirmations();regionalRetirementIsolation();worldFrictionOncePerStep();sectionSeamProgress();sectionDemandAndPause();fallingSectionConfirmations();packageFaceLighting();asymmetricSweepRegression();environmentRegression();environmentThroughput();recycledFreeAcquisitions();recycledChainAcquisitions();historicalGeometryIsolation();localBudgetRetry();
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"repair regression GL error");System.out.println("Package repair GPU: "+checks+" assertions passed");return;
            }
            if(Arrays.asList(args).contains("--physics-only")) {
                sourceContract();boundaries();contact();sweep();dynamicPackageSweep();environmentThroughput();
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"physics GL error");
                System.out.println("Package physics GPU: "+checks+" assertions passed");return;
            }
            if(Arrays.asList(args).contains("--dynamic-ccd-benchmark")) {
                sourceContract();dynamicPackageBenchmark();
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"dynamic CCD benchmark GL error");
                System.out.println("Package dynamic CCD benchmark: "+checks+" assertions passed");return;
            }
            if(Arrays.asList(args).contains("--chain-frames-only")){sourceContract();chainParentFrames();mergedPackageVertices();check(GL11.glGetError()==GL11.GL_NO_ERROR,"chain frames GL error");System.out.println("Package chain frames GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--shaderpack-only")){sourceContract();mergedPackageVertices();shadowCullingAndBoundary();packageDrawTelemetry();render();packageFaceLighting();poseParity();check(GL11.glGetError()==GL11.GL_NO_ERROR,"shaderpack GL error");System.out.println("Package shaderpack GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--draw-pass-only")){sourceContract();preparedDrawPasses();shadowCullingAndBoundary();packageDrawTelemetry();render();packageFaceLighting();poseParity();if(Arrays.asList(args).contains("--draw-pass-benchmark"))drawPassBenchmark();if(Arrays.asList(args).contains("--iris-boundary-benchmark"))drawPassBenchmark(true);check(GL11.glGetError()==GL11.GL_NO_ERROR,"draw pass GL error");System.out.println("Package draw pass GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--checkpoint-only")){sourceContract();chainCheckpoints();if(Arrays.asList(args).contains("--checkpoint-benchmark"))chainCheckpointBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"checkpoint GL error");System.out.println("Package checkpoint GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--backend-only")){sourceContract();contactProbeReference();backendBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"backend GL error");return;}
            if(Arrays.asList(args).contains("--mixed-benchmark")){sourceContract();mixedPhysics();mixedBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"mixed benchmark GL error");return;}
            if(Arrays.asList(args).contains("--chain-benchmark")){sourceContract();chainReference();trackedChains();chainTrackBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"chain benchmark GL error");System.out.println("Package chain GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--query-only")){sourceContract();poseQueries();freePoseQueries();parallelFreeChainQueries();if(Arrays.asList(args).contains("--query-benchmark"))poseQueryBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"query GL error");System.out.println("Package query GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--forces-only")){sourceContract();externalForces();framedForces();if(Arrays.asList(args).contains("--forces-benchmark"))forceBenchmark();check(GL11.glGetError()==GL11.GL_NO_ERROR,"force GL error");System.out.println("Package forces GPU: "+checks+" assertions passed");return;}
            if(Arrays.asList(args).contains("--chain-only")){sourceContract();chainReference();trackedChains();chainEventChannels();chainAcquisitions();check(GL11.glGetError()==GL11.GL_NO_ERROR,"chain GL error");System.out.println("Package chain GPU: "+checks+" assertions passed");return;}
            movingContacts();movingReference();movingLifecycle();movingFriction();movingCapacity();if(Arrays.asList(args).contains("--moving-benchmark"))movingBenchmark();if(Arrays.asList(args).contains("--moving-only")){check(GL11.glGetError()==GL11.GL_NO_ERROR,"moving GL error");System.out.println("Package moving GPU: "+checks+" assertions passed");return;}
            continuousFreeAcquisitions();machineOutputProgress();machineEnvironmentConfirmations();regionalRetirementIsolation();worldFrictionOncePerStep();sectionSeamProgress();sectionDemandAndPause();fallingSectionConfirmations();asymmetricSweepRegression();environmentRegression();environmentThroughput();recycledFreeAcquisitions();recycledChainAcquisitions();historicalGeometryIsolation();localBudgetRetry();sourceContract();externalForces();framedForces();chainParentFrames();mergedPackageVertices();poseQueries();freePoseQueries();parallelFreeChainQueries();chainCheckpoints();boundaries();contact();sweep();dynamicPackageSweep();worldUploadVersions();worldAtlasLru();worldShapes();worldSweepsAndMaterials();historicalGeometryIsolation();worldMissingAndInvalidated();worldFullCapacity();worldRigidSupportAndReplacement();supportProjection();contactProbeReference();supportContactCases();worldEntryFace();supportSustainedMotion();chain();chainReference();trackedChains();chainEventChannels();chainAcquisitions();incrementalPhysics();preparedPhysics();mixedPhysics();mixedFullReservation();readbacks();pool();preparedDrawPasses();shadowCullingAndBoundary();packageDrawTelemetry();render();previewLoad();poseParity();deltas();deltaIncrementalIdentity();deltaPreparedBaselines();acquisitions();batchedAcquisitions();deltaOverflowAndIdentity();deltaQuantizationLimits();deltaYawTies();deltaRelativeBaselines();deltaPredictedMotion();channelRoundTrip();channelBatchAckHoles();channelLifecycle();channelImmutableAndPartialTransport();if(Arrays.asList(args).contains("--benchmark")){benchmark();deltaBenchmark();}
            if(Arrays.asList(args).contains("--delta-pipeline-benchmark"))pipelineBenchmark();
            if(Arrays.asList(args).contains("--delta-batch-pipeline-benchmark"))pipelineBenchmark(true);
            if(Arrays.asList(args).contains("--world-benchmark"))worldBenchmark();


            if(Arrays.asList(args).contains("--stack-benchmark"))stackBenchmark();
            if(Arrays.asList(args).contains("--stack-stress"))stackStress();
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");System.out.println("Package GPU: "+checks+" assertions passed");
        }finally {if(debug!=null)debug.free();GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();}
    }
}
