package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import java.util.*;
import java.util.regex.Pattern;

/** Extracts the active leaf branch from the preprocessed terrain program, including its live uniforms. */
public final class FeatherLeafSway {
    public record Plan(String pack,int material,String source) {
        public boolean matches(int id){
            return material==16896?id>=0&&(id&0x4e00)==16896:id==material;
        }
    }
    private record Declaration(int start,String name,String text,boolean uniform) {}
    private static final Pattern WORD=Pattern.compile("[A-Za-z_]\\w*");
    private static final String TYPE="(?:void|float|double|int|uint|bool|[biud]?vec[234]|mat[234](?:x[234])?|[iu]?sampler\\w+)";
    private static final Pattern FUNCTION=Pattern.compile(TYPE+"[ \\t]++(\\w++)\\s*+\\([^{};]*?\\)\\s*+\\{");
    private static final Pattern GLOBAL=Pattern.compile("(uniform[ \\t]++|const[ \\t]++)?"+TYPE+"[ \\t]++([^;{}]++);");
    private static Plan compiled;
    private static long generation=-1;
    private FeatherLeafSway(){}
    static void compiled(Plan plan,long session){compiled=plan;generation=session;}
    public static Plan current(){
        var session=ShaderPackSession.current();
        return compiled!=null&&generation==session.generation()
                &&session.ownsPipeline(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable())?compiled:null;
    }

    public static Plan resolve(ShaderPackInventory pack,String terrain){
        String body=ShaderPackInventory.code(terrain);
        try{
            if(!pack.source("lib/materials/materialMethods/wavingBlocks.glsl").isEmpty()){
                String wave=function(body,"DoWave");
                require(wave,"DoWave_Leaves\\s*\\([^;]*?,\\s*1\\.0\\s*\\)");
                return make("Complementary",10009,body.replace("lmCoord.y","cmiLeafSky"),
                        "vec3 p=world; DoWave_Leaves(p,world,1.0); return p-world;");
            }
            if(!pack.source("dimensions/all_solid.vsh").isEmpty()){
                String main=function(body,"main");
                String plants=match(main,"worldpos\\s*\\+=\\s*(calcMovePlants\\s*\\([^;]+);")
                        .replaceFirst("worldpos\\s*\\+\\s*cameraPosition","world").replace("lmtexcoord.w","cmiLeafSky");
                String call=match(main,"(calcMoveLeaves\\s*\\(\\s*worldpos\\s*\\+\\s*cameraPosition[^;]+)\\*\\s*lmtexcoord\\.w")
                        .replaceFirst("worldpos\\s*\\+\\s*cameraPosition","leafSample");
                return make("Bliss",56,body,"if(abs((gbufferModelView*vec4(world-cmi_CameraPos,1)).z)>=64.)return vec3(0); vec3 leafSample=world+"
                        +plants+"; return "+call+"*cmiLeafSky;");
            }
            if(!pack.source("lib/vertex/waving.glsl").isEmpty()){
                String call=match(body,"blockID\\s*==\\s*105\\b[^;]+?(CalcMove\\s*\\([^;]+);");
                return make("BSL",105,body,"return "+call.replaceFirst("\\bworldPos\\b","world")+";");
            }
            if(!pack.source("Lib/Programs/Gbuffers/Terrain_VS.glsl").isEmpty()){
                String leaf=block(body,"if\\s*\\(\\s*mc_Entity\\.x\\s*==\\s*7030(?:\\.0)?\\s*\\)");
                String time=match(body,"float\\s+tick\\s*=\\s*([^;]+);");
                String weight=match(body,"float\\s+lightWeight\\s*=\\s*([^;]+);").replace("v_blockLight.y","cmiLeafSky");
                return make("iterationT",7030,body,"vec3 worldPos=world; float tick="+time+
                        "; float lightWeight="+weight+"; lightWeight*=lightWeight; lightWeight*=lightWeight;"+leaf+" return worldPos-world;");
            }
            if(!pack.source("include/vertex/displacement.glsl").isEmpty()){
                String animate=function(body,"animate_vertex");
                require(animate,"case\\s+5\\s*:[\\s\\S]*?get_wind_displacement\\s*\\(");
                return make("Photon",10005,body,"return get_wind_displacement(world,0.3,cmiLeafSky*cmiLeafSky*(0.25+0.66*rainStrength)*0.5,false);");
            }
            if(pack.source("gbuffers_textured.vsh").contains("ENTITY_LEAVES")){
                String call=match(function(body,"main"),"mc_Entity\\.x\\s*==\\s*10018(?:\\.0)?\\s*\\)\\s*position\\.xyz\\s*\\+=\\s*(funMove\\s*\\([^;]+);");
                return make("Sildur’s",10018,body,"return "+call.replace("worldpos.xyz","world")+";");
            }
            if(!pack.source("lib/pbr/waving.glsl").isEmpty()){
                String call=match(function(body,"getWavingBlocks"),"mc_Entity\\.x\\s*==\\s*10314\\s*\\)\\s*wave\\s*\\+=\\s*(calculateMovement\\s*\\([^;]+);");
                return make("Solas",10314,body,"vec3 worldPos=world-cmi_CameraPos; return "+call+"*cmiLeafSky;");
            }
            if(!pack.source("programs/gbuffers/Terrain.vert").isEmpty()){
                String wave=match(function(body,"main"),"(vec2\\s+waveNoise\\s*=\\s*vec2\\s*\\(\\s*smooth3DNoise[^;]+;)");
                String offset=match(body,"vec3\\s+waveOffset\\s*=\\s*([^;]+);");
                return make("Sundial Lite",16896,body,"vec3 mcPos=world; "+wave+" return -("+offset+");");
            }
        }catch(IllegalArgumentException inactive){return null;}
        return null;
    }
    private static Plan make(String pack,int material,String terrain,String body){
        List<Declaration> declarations=declarations(terrain);
        Set<String> needed=new HashSet<>();
        words(body,needed);
        Set<Declaration> selected=new LinkedHashSet<>();
        boolean added;
        do {
            added=false;
            for(var declaration:declarations){
                if(!declaration.name().equals("main")&&needed.contains(declaration.name())&&selected.add(declaration)){
                    words(declaration.text(),needed);
                    added=true;
                }
            }
        }while(added);
        Map<String,String> renamed=new HashMap<>();
        for(var declaration:selected)if(!declaration.uniform())
            renamed.put(declaration.name(),"cmiLeaf_"+declaration.name());
        StringBuilder glsl=new StringBuilder("#define CMI_LEAF_SWAY\nuniform bool cmi_FeatherSwayEnabled;\nfloat cmiLeafSky;\n");
        selected.stream().sorted(Comparator.comparingInt(Declaration::start)).forEach(d->
                glsl.append(rename(d.text(),renamed)).append('\n'));
        glsl.append("vec3 cmiLeafOffset(vec3 world,float sky){ cmiLeafSky=sky; ")
                .append(rename(body,renamed)).append(" }\n");
        return new Plan(pack,material,glsl.toString());
    }
    private static void words(String text,Set<String> into){
        var tokens=WORD.matcher(text);
        while(tokens.find())into.add(tokens.group());
    }
    private static String rename(String text,Map<String,String> names){
        return WORD.matcher(text).replaceAll(m->names.getOrDefault(m.group(),m.group()));
    }
    private static List<Declaration> declarations(String text){
        int[] depth=new int[text.length()+1];
        for(int i=0;i<text.length();i++)depth[i+1]=depth[i]+(text.charAt(i)=='{'?1:text.charAt(i)=='}'?-1:0);
        var result=new ArrayList<Declaration>();
        var functions=FUNCTION.matcher(text);
        var globals=GLOBAL.matcher(text);
        for(int line=0;line<text.length();){
            int start=line,endLine=text.indexOf('\n',line);
            line=endLine<0?text.length():endLine+1;
            if(depth[start]!=0)continue;
            while(start<line&&(text.charAt(start)==' '||text.charAt(start)=='\t'))start++;
            if(start>=line||!Character.isLetter(text.charAt(start)))continue;
            if(functions.region(start,text.length()).lookingAt()){
                int end=closing(text,functions.end()-1);
                result.add(new Declaration(start,functions.group(1),text.substring(start,end+1),false));
                continue;
            }
            if(!globals.region(start,text.length()).lookingAt())continue;
            String definition=globals.group(2);
            if(definition.indexOf('(')>=0&&!definition.contains("="))continue;
            var name=WORD.matcher(definition);
            if(name.find())result.add(new Declaration(globals.start(),name.group(),globals.group(),globals.group(1)!=null&&globals.group(1).startsWith("uniform")));
        }
        return result;
    }
    private static String function(String source,String name){
        var matcher=Pattern.compile("\\b"+TYPE+"[ \\t]++"+Pattern.quote(name)+"\\s*+\\([^{};]*?\\)\\s*+\\{").matcher(source);
        if(matcher.find())return source.substring(matcher.end(),closing(source,matcher.end()-1));
        throw new IllegalArgumentException(name);
    }
    private static String block(String source,String pattern){
        var matcher=Pattern.compile(pattern+"\\s*\\{").matcher(source);
        if(!matcher.find())throw new IllegalArgumentException(pattern);
        return source.substring(matcher.end(),closing(source,matcher.end()-1));
    }
    private static int closing(String source,int start){
        int depth=1;
        for(int i=start+1;i<source.length();i++){
            char c=source.charAt(i);
            if(c=='{')depth++;
            if(c=='}'&&--depth==0)return i;
        }
        throw new IllegalArgumentException("Unclosed terrain block");
    }
    private static String match(String text,String pattern){
        var m=Pattern.compile(pattern).matcher(text);
        if(!m.find())throw new IllegalArgumentException(pattern);
        return m.group(1);
    }
    private static void require(String text,String pattern){
        if(!Pattern.compile(pattern).matcher(text).find())throw new IllegalArgumentException(pattern);
    }
}
