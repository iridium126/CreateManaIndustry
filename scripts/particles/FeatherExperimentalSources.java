import java.util.function.Function;

/** Withdrawn thin-adapter candidate; validation only, never packaged. */
final class FeatherExperimentalSources {
    static String buildFeatherSource(Function<String,String> mergedSources,boolean withEntityColor) {
        return "#version 400 core\n"+com.iridium126.createmanaindustry.client.particles.engine.ParticleAtlasLayout.shaderPrelude()
                +mergedSources.apply("chunks/feather_record.glsl")+"\n"
                +"uniform samplerBuffer cmi_Pool; uniform usamplerBuffer cmi_Sorted;\n"
                +"uniform int cmi_FeatherWordBase; uniform vec3 cmi_CameraPos,cmi_FeatherRight,cmi_FeatherUp;\n"
                +"vec4 cmi_VertexLevel,cmi_TexCoord0v,cmi_LightCoordv,cmi_Tint; vec3 cmi_NormalLevel;\n"
                +(withEntityColor?"out vec4 entityColor;\n":"")
                +"void main(){uvec2 pair=texelFetch(cmi_Sorted,(cmi_FeatherWordBase+"
                +com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.VISIBLE+")/2+gl_InstanceID/2).xy;"
                +"int base=int(pair[gl_InstanceID&1])*4;vec3 vertex,normal;vec2 uv;"
                +"cmiFeatherVertex(texelFetch(cmi_Pool,base),texelFetch(cmi_Pool,base+1),texelFetch(cmi_Pool,base+2),texelFetch(cmi_Pool,base+3),cmi_FeatherRight,cmi_FeatherUp,gl_VertexID,vertex,normal,uv);"
                +"cmi_VertexLevel=vec4(vertex-cmi_CameraPos,1);cmi_NormalLevel=normal;cmi_TexCoord0v=vec4(uv,0,1);cmi_LightCoordv=vec4(240,240,0,1);cmi_Tint=vec4(1);"
                +(withEntityColor?"entityColor=vec4(0);":"")+"}";
    }
}
