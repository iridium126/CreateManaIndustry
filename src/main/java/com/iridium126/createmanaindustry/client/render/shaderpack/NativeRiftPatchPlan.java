package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.*;

/** Whole-pack, full-path native sky candidates. Neither archive names nor version strings participate. */
public final class NativeRiftPatchPlan {
    public record Program(String array,int index,String marker,boolean required) {}
    private final String family,reason;
    private final Map<String,String> originals,edits;
    private final List<Program> programs;
    private NativeRiftPatchPlan(String family,String reason,Map<String,String> originals,Map<String,String> edits,List<Program> programs) {
        this.family=family;this.reason=reason;this.originals=Map.copyOf(originals);this.edits=Map.copyOf(edits);this.programs=List.copyOf(programs);
    }
    public static NativeRiftPatchPlan disabled(String reason) { return new NativeRiftPatchPlan("unknown",reason,Map.of(),Map.of(),List.of()); }
    public boolean enabled(){return !edits.isEmpty();}
    public String family(){return family;}
    public String reason(){return reason;}
    public Map<String,String> edits(){return edits;}
    public List<Program> programs(){return programs;}
    public String transform(String path,String original) {
        path=ShaderPackInventory.normalize(path);
        if(!edits.containsKey(path))return original;
        if(!Objects.equals(originals.get(path),original))throw new IllegalStateException("Rift source changed during load: "+path);
        return edits.get(path);
    }
    public static NativeRiftPatchPlan prepare(ShaderPackInventory inventory) {
        var matches=new ArrayList<String>();
        if(inventory.containsCode("program/d4_deferred_shading.fsh","read_clouds_and_aurora")&&inventory.containsCode("program/d1_clouds.fsh","CLOUDS_SCALE"))matches.add("photon");
        if(inventory.containsCode("program/deferred1.glsl","GetClouds")&&inventory.containsCode("lib/atmospherics/clouds/mainClouds.glsl","GetClouds"))matches.add("complementary");
        if(inventory.containsCode("program/deferred1.glsl","DrawCloudVolumetric")&&inventory.containsCode("lib/atmospherics/clouds.glsl","DrawCloudSkybox"))matches.add("bsl");
        if(inventory.containsCode("programs/deferred1.glsl","computeVolumetricClouds")&&inventory.containsCode("lib/atmosphere/volumetricClouds.glsl","sampleTotalLength"))matches.add("solas");
        if(inventory.containsCode("deferred.fsh","funClouds3D")&&inventory.containsCode("deferred.fsh","funPanoramaSky"))matches.add("sildur");
        if(inventory.containsCode("dimensions/composite1.fsh","skyFromTex")&&inventory.containsCode("lib/volumetricClouds.glsl","renderLayer"))matches.add("bliss");
        if(inventory.containsCode("deferred3.fsh","NubisCumulus")&&inventory.containsCode("Lib/IndividualFounctions/NUBIS.glsl","marchingPos"))matches.add("iteration");
        if(inventory.containsCode("programs/composite/Composite4.frag","sampleClouds")&&inventory.containsCode("libs/Cloud.glsl","realisticCloud"))matches.add("sundial-lite");
        if(matches.size()!=1)return disabled(matches.isEmpty()?"no native sky contract":"ambiguous native sky contracts: "+matches);
        String family=matches.getFirst();var builder=new Builder(inventory);
        try {
            switch(family) {
                case "photon" -> photon(builder);
                case "complementary" -> complementary(builder);
                case "bsl" -> bsl(builder);
                case "solas" -> solas(builder);
                case "sildur" -> sildur(builder);
                case "bliss" -> bliss(builder);
                case "iteration" -> iteration(builder);
                case "sundial-lite" -> sundial(builder);
                default -> throw new IllegalStateException(family);
            }
            stormClouds(builder,family);
            return new NativeRiftPatchPlan(family,"complete sky/celestial/cloud source preflight",inventory.sources(),builder.edits,builder.programs);
        } catch(RuntimeException error) {
            return new NativeRiftPatchPlan(family,"disabled: "+error.getMessage(),Map.of(),Map.of(),List.of());
        }
    }
    private static final class Builder {
        final ShaderPackInventory inventory;final Map<String,String> edits=new LinkedHashMap<>();final List<Program> programs=new ArrayList<>();
        Builder(ShaderPackInventory inventory){this.inventory=inventory;}
        void edit(String path,UnaryOperator<String> transform){String input=edits.getOrDefault(path,inventory.source(path));ShaderPatchPlan.require(!input.isEmpty(),"missing "+path);edits.put(path,transform.apply(input));}
        void main(String path,String array,int sampleIndex,String marker){
            edit(path,s->mainPrelude(s,marker));
            register(path,array,marker,marker.contains("Main"));
        }
        void producer(String path,String array,String anchor) {
            ShaderPatchPlan.require(inventory.containsCode(path,anchor),"missing native sky producer contract "+path);
            register(path,array,"",false);
        }
        void register(String path,String array,String marker,boolean required) {
            String prefix=array.toLowerCase(Locale.ROOT);
            List<String> entries=inventory.sources().keySet().stream().filter(p->p.matches("world0/"+prefix+"\\d*\\.fsh")).toList();
            if(entries.isEmpty())entries=inventory.sources().keySet().stream().filter(p->p.matches(prefix+"\\d*\\.fsh")).toList();
            int found=0;
            for(String entry:entries)if(includes(entry,path,new HashSet<>())) {
                String name=entry.substring(entry.lastIndexOf('/')+1,entry.length()-4).substring(prefix.length());
                programs.add(new Program(array,name.isEmpty()?0:Integer.parseInt(name),marker,required));found++;
            }
            ShaderPatchPlan.require(found>0,"no actual "+array+" entry includes "+path);
        }
        boolean includes(String file,String target,Set<String> seen) {
            if(file.equals(target))return true;if(!seen.add(file))return false;
            Matcher matcher=Pattern.compile("(?m)^\\s*#include\\s+[\"<]([^\">]+)[\">]").matcher(ShaderPackInventory.code(inventory.source(file)));
            while(matcher.find()) {
                String include=matcher.group(1);
                String resolved=include.startsWith("/")?include.substring(1):java.nio.file.Path.of(file).getParent()==null?include:java.nio.file.Path.of(file).getParent().resolve(include).normalize().toString().replace('\\','/');
                if(includes(resolved,target,seen))return true;
            }
            return false;
        }
        void helper(String path,String function){edit(path,s->before(s,"\\b(?:vec[234]|float|bool|void)\\s+"+function+"\\s*\\(",RiftShaderSources.source()+"\n"));}
        void wrap(String path,String name,String body) {
            edit(path,source->{
                String code=ShaderPackInventory.code(source);
                var match=ShaderPatchPlan.unique(code,"\\b(?:float|bool|vec[234])\\s+"+name+"\\s*\\([^)]*\\)\\s*\\{");
                int open=match.end()-1,end=open,depth=1;
                while(++end<code.length()&&depth>0){char c=code.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
                ShaderPatchPlan.require(depth==0,"unbalanced cloud function "+name);end--;
                String header=source.substring(match.start(),open+1);
                String stock=header.replaceFirst("\\b"+name+"\\b","cmiStock_"+name);
                return source.substring(0,match.start())+RiftShaderSources.source()+"\n"+stock+source.substring(open+1,end+1)
                        +"\n"+header+"\n"+body+"\n}\n"+source.substring(end+1);
            });
        }
    }

    private static void stormClouds(Builder b,String family) {
        switch(family) {
            case "complementary" -> {
                String main="lib/atmospherics/clouds/mainClouds.glsl";b.helper(main,"GetClouds");
                b.edit(main,s->after(s,"cameraPos\\s*\\+=\\s*cameraPosOffset\\s*;","cmiCloudCamera = cameraPos;\n"));
                b.wrap("lib/atmospherics/clouds/unboundClouds.glsl","GetCloudNoise",
                        "vec3 relative=tracePos-cmiCloudCamera;\nreturn cmiStock_GetCloudNoise(cmiStormCloudPosition(relative)+cmiCloudCamera,cloudAltitude,lTracePosXZ,cloudPlayerPosY)*cmiStormCloudDensity(relative.xz);");
                String reimagined="lib/atmospherics/clouds/reimaginedClouds.glsl";
                b.helper(reimagined,"GetCloudNoise");
                b.edit(reimagined,s->after(s,"bool\\s+GetCloudNoise\\s*\\([^)]*\\)\\s*\\{","vec3 cmiPoint=tracePos-cmiCloudCamera;\ntracePos=cmiStormCloudPosition(cmiPoint)+cmiCloudCamera;\n"));
                b.edit(reimagined,s->replace(s,"return\\s+noise\\s*>\\s*threshold\\s*\\*\\s*0\\.5\\s*\\+\\s*0\\.25\\s*;","return noise * cmiStormCloudDensity(cmiPoint.xz) > threshold * 0.5 + 0.25;"));
            }
            case "bsl" -> b.wrap("lib/atmospherics/clouds.glsl","CloudSample",
                    "vec2 relative=coord*CLOUD_SCALE-cameraPosition.xz;\ncoord=(cmiStormCloudPosition(relative)+cameraPosition.xz)/CLOUD_SCALE;\nreturn cmiStock_CloudSample(coord,wind,cloudGradient,sunCoverage,dither)*cmiStormCloudDensity(relative);");
            case "bliss" -> {
                String path="lib/volumetricClouds.glsl";
                b.wrap(path,"GetAltostratusDensity","vec2 relative=pos.xz-cameraPosition.xz;\npos.xz=cmiStormCloudPosition(relative)+cameraPosition.xz;\nreturn cmiStock_GetAltostratusDensity(pos)*cmiStormCloudDensity(relative);");
                b.wrap(path,"GetCumulusDensity","vec2 relative=pos.xz-cameraPosition.xz;\npos.xz=cmiStormCloudPosition(relative)+cameraPosition.xz;\nreturn cmiStock_GetCumulusDensity(layer,pos,LoD,minHeight,maxHeight)*cmiStormCloudDensity(relative);");
            }
            case "solas" -> {
                String path="lib/atmosphere/volumetricClouds.glsl";
                b.edit(path,s->after(s,"vec2\\s+cloudCoord\\s*=\\s*rayPos\\.xz\\s*/\\s*scale\\s*;","cmiCloudUnitScale=scale;\n"));
                for(String name:List.of("CloudSample","CloudSampleLowDetail"))b.wrap(path,name,
                        "vec2 relative=coord*cmiCloudUnitScale-cameraPosition.xz;\ncoord=(cmiStormCloudPosition(relative)+cameraPosition.xz)/cmiCloudUnitScale;\nreturn cmiStock_"+name+"(coord,wind,sampleAltitude,thickness,amount,density)*cmiStormCloudDensity(relative);");
                String planar=b.inventory.uniqueSuffix("planarClouds.glsl");
                b.edit(planar,s->after(s,"vec3\\s+planeCoord\\s*=[^;]+;","vec2 cmiPoint=planeCoord.xz*1000.0;\n"));
                b.edit(planar,s->after(s,"planeCoord\\s*\\*=\\s*2\\.0\\s*-\\s*distanceFactor\\s*;","planeCoord.xz+=(cmiStormCloudPosition(cmiPoint)-cmiPoint)*0.001*(2.0-distanceFactor);\n"));
                b.edit(planar,s->after(s,"float\\s+noise\\s*=\\s*samplePlanarCloudNoise\\(coord\\)\\s*;","noise*=cmiStormCloudDensity(cmiPoint);\n"));
            }
            case "iteration" -> {
                String path="Lib/IndividualFounctions/NUBIS.glsl";
                b.wrap(path,"SetCloudPos","vec4 result=cmiStock_SetCloudPos(marchingPos,cloudAltitude,planetRadius,cloudScale,windDirection);\nvec2 relative=marchingPos.xz-cameraPosition.xz;\nresult.xz+=(cmiStormCloudPosition(relative)-relative)*cloudScale;\ncmiCloudDensityFactor=cmiStormCloudDensity(relative);\nreturn result;");
                b.wrap(path,"SampleDensity","return cmiStock_SampleDensity(cloudPos,windDirection,sampleDetail)*(cmiStormCloudActive()?cmiCloudDensityFactor:1.0);");
                String planar="Lib/IndividualFounctions/PlanarClouds.glsl";
                for(String name:List.of("GetPlanarCloudsDensity","GetPlanarCloudsLightingDensity"))b.wrap(planar,name,
                        "vec2 relative=position-cameraPosition.xz;\nposition=cmiStormCloudPosition(relative)+cameraPosition.xz;\nreturn cmiStock_"+name+"(position,windDirection)*cmiStormCloudDensity(relative);");
            }
            case "photon" -> {
                b.edit("program/d1_clouds.fsh",s->mainStart(s,"cmiMainView=true;\n"));
                for(String layer:List.of("cumulus","altocumulus","cumulus_congestus")) {
                    String path="include/sky/clouds/"+layer+".glsl",name="clouds_"+layer+"_density";
                    b.wrap(path,name,"vec2 relative=pos.xz/CLOUDS_SCALE;\npos.xz=cmiStormCloudPosition(relative)*CLOUDS_SCALE;\nreturn cmiStock_"+name+"(pos)*cmiStormCloudDensity(relative);");
                }
                b.wrap("include/sky/clouds/cirrus.glsl","clouds_cirrus_density","vec2 relative=coord/CLOUDS_SCALE;\ncoord=cmiStormCloudPosition(relative)*CLOUDS_SCALE;\nreturn cmiStock_clouds_cirrus_density(coord,altitude_fraction)*cmiStormCloudDensity(relative);");
                b.wrap("include/sky/blocky_clouds.glsl","blocky_clouds_density","vec2 relative=world_pos.xz-cameraPosition.xz;\nworld_pos.xz=cmiStormCloudPosition(relative)+cameraPosition.xz;\nreturn cmiStock_blocky_clouds_density(world_pos,altitude_fraction,layer_offset)*cmiStormCloudDensity(relative);");
            }
            case "sildur" -> {
                String path="deferred.fsh";
                b.edit(path,s->after(s,"vec3\\s+intersection\\s*=\\s*tpos\\s*\\*\\s*\\(\\s*heightA[^;]+;","intersection.xz=cmiStormCloudPosition(intersection.xz);\n"));
                b.edit(path,s->after(s,"float\\s+cl\\s*=\\s*max[^;]+;","cl*=cmiStormCloudDensity(intersection.xz);\n"));
                b.edit(path,s->replace(s,"vec3\\s+samplePos\\s*=\\s*progress_view\\s*\\*\\s*0\\.175\\s*\\+\\s*frameTimeCounter\\s*\\*\\s*timeVec\\s*;","vec3 samplePos=(cmiStormCloudPosition(progress_view-cameraPosition)+cameraPosition)*0.175+frameTimeCounter*timeVec;"));
                b.edit(path,s->after(s,"float\\s+cloud\\s*=\\s*\\(1\\.0\\s*-\\s*pow\\(0\\.4[^;]+;","cloud*=cmiStormCloudDensity((progress_view-cameraPosition).xz);\n"));
            }
            case "sundial-lite" -> {
                String path="libs/Cloud.glsl";
                b.helper(path,"blockyCloudDensity");
                // Keep the native DDA geometry and cloud altitude. Warp only its periodic noise lookup.
                b.edit(path,s->before(s,"vec4\\s+blockyCloud\\s*\\(","float cmiBlockyDensity(ivec2 tile,vec2 relative) {\nvec2 delta=(cmiStormCloudPosition(relative)-relative)*cloudNoiseScale;\nfloat noise=texelFetch(noisetex,ivec2(floor(vec2(tile)+delta))&63,0).z;\nreturn step(1.0-CLOUD_BLOCKY_AMOUNT,noise*cmiStormCloudDensity(relative));\n}\n"));
                b.edit(path,s->replace(s,"blockyCloudDensity\\(sampleCloudTexel\\)","cmiBlockyDensity(sampleCloudTexel,cloudOffset+stepDir.xz*totalRayLength/cloudNoiseScale)"));
                b.edit(path,s->after(s,"float\\s+shadowOpticalDepth\\s*=\\s*0\\.0\\s*;","float cmiShadowDistance=0.0;\n"));
                b.edit(path,s->replace(s,"blockyCloudDensity\\(cloudTexel\\)(?=;\\s*shadowOpticalDepth)","cmiBlockyDensity(cloudTexel,cloudPos.xz/cloudNoiseScale-cameraPosition.xz-wind+stepDir.xz*cmiShadowDistance/cloudNoiseScale)"));
                b.edit(path,s->after(s,"shadowOpticalDepth\\s*\\+=\\s*rayLength\\s*\\*\\s*step\\(0\\.5,\\s*sampleCloudDensity\\)\\s*;","cmiShadowDistance+=rayLength;\n"));
                b.wrap(path,"realisticCloudDensity","vec2 origin=cameraPosition.xz+vec2(CLOUD_REALISTIC_OFFSET_X,CLOUD_REALISTIC_OFFSET_Z);\nvec2 relative=cloudPos.xz-origin;\ncloudPos.xz=cmiStormCloudPosition(relative)+origin;\nreturn cmiStock_realisticCloudDensity(cloudPos,wind,cloudDistance,octaves,weights)*cmiStormCloudDensity(relative);");
                b.edit(path,s->after(s,"vec2\\s+cloudPos\\s*=\\s*worldPos\\.xz\\s*\\+\\s*worldDir\\.xz\\s*\\*\\s*intersection\\s*\\+\\s*cameraPosition\\.xz\\s*\\+\\s*wind\\s*;","vec2 cmiPoint=worldPos.xz+worldDir.xz*intersection;\ncloudPos=cmiStormCloudPosition(cmiPoint)+cameraPosition.xz+wind;\n"));
                b.edit(path,s->after(s,"cloudDensity\\s*=\\s*clamp\\(cloudDensity\\s*\\+\\s*PLANE_CLOUD_AMOUNT[^;]+;","cloudDensity*=cmiStormCloudDensity(cmiPoint);\n"));
            }
        }
    }
    private static String mainPrelude(String source,String marker) {
        Matcher main=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{").matcher(ShaderPackInventory.code(source));
        ShaderPatchPlan.require(main.find(),"missing fragment main");int position=main.start();
        if(main.find())ShaderPatchPlan.require(Pattern.compile("#\\s*ifdef\\s+(?:FRAGMENT_SHADER|FSH)\\b").matcher(ShaderPackInventory.code(source.substring(0,position))).find(),"ambiguous main");
        return source.substring(0,position)+RiftShaderSources.source()+"\nconst int "+marker+" = 1;\n"+source.substring(position);
    }
    private static String before(String source,String regex,String text){Matcher m=ShaderPatchPlan.unique(ShaderPackInventory.code(source),regex);return source.substring(0,m.start())+text+source.substring(m.start());}
    private static String after(String source,String regex,String text){Matcher m=ShaderPatchPlan.unique(ShaderPackInventory.code(source),regex);return source.substring(0,m.end())+"\n"+text+source.substring(m.end());}
    private static String replace(String source,String regex,String text){Matcher m=ShaderPatchPlan.unique(ShaderPackInventory.code(source),regex);return source.substring(0,m.start())+text+source.substring(m.end());}
    private static String mainStart(String source,String text){return after(source,"void\\s+main\\s*\\(\\s*\\)\\s*\\{",text);}
    private static String firstMainStart(String source,String text){Matcher main=Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{").matcher(ShaderPackInventory.code(source));ShaderPatchPlan.require(main.find(),"missing main");return source.substring(0,main.end())+"\n"+text+source.substring(main.end());}

    private static void complementary(Builder b) {
        String main="program/deferred1.glsl";
        b.main(main,"Deferred",1,"cmiRiftMain_complementary");
        b.edit(main,s->firstMainStart(s,"bool cmiIsSky = false;\n"));
        b.edit(main,s->after(s,"skyFade\\s*=\\s*1\\.0\\s*;","cmiIsSky = true;\n"));
        // Replace after the native celestial additions, while staying inside that same branch.
        b.edit(main,s->before(s,"float\\s+cloudLinearDepth\\s*=\\s*1\\.0\\s*;","if (cmiIsSky) color.rgb = cmiNativeSky(color.rgb, normalize(playerPos), 2.2, 1.0);\ncmiMainView = true;\n"));
        b.edit(main,s->after(s,"color\\s*=\\s*mix\\s*\\(\\s*color\\s*,\\s*vec4\\s*\\(\\s*clouds\\.rgb\\s*,\\s*0\\.0\\s*\\)\\s*,\\s*clouds\\.a\\s*\\)\\s*;","cmiMainView = false;\n"));
        for(String file:List.of("unboundClouds.glsl","reimaginedClouds.glsl")) {
            String path="lib/atmospherics/clouds/"+file;b.helper(path,"GetVolumetricClouds");
            b.edit(path,s->after(s,"float\\s+lTracePos\\s*=\\s*length\\s*\\(\\s*cloudPlayerPos\\s*\\)\\s*;","if (!cmiCloudVisible(cloudPlayerPos)) break;\n"));
        }
    }
    private static void bsl(Builder b) {
        String main="program/deferred1.glsl";b.main(main,"Deferred",1,"cmiRiftMain_bsl");
        b.edit(main,s->before(s,"vec4\\s+cloud\\s*=\\s*vec4\\s*\\(\\s*0\\.0\\s*\\)\\s*;","if (z >= 1.0 && isEyeInWater <= 1) color.rgb = cmiNativeSky(color.rgb, normalize(mat3(gbufferModelViewInverse) * viewPos.xyz), 1.0, 1.0);\ncmiMainView = true;\n"));
        b.edit(main,s->before(s,"cloudViewLength\\s*/=\\s*cloudMaxDistance\\s*;","cmiMainView = false;\n"));
        String clouds="lib/atmospherics/clouds.glsl";b.helper(clouds,"DrawCloudSkybox");
        b.helper(clouds,"DrawCloudVolumetric");
        b.edit(clouds,s->after(s,"vec3\\s+planeCoord\\s*=\\s*wpos\\s*\\*\\s*\\(\\s*planeY\\s*/\\s*wpos\\.y\\s*\\)\\s*;","if (!cmiCloudVisible(planeCoord * CLOUD_SCALE)) { currentStep += sampleStep; continue; }\n"));
        b.edit(clouds,s->after(s,"if\\s*\\(\\s*sampleTotalLength\\s*>\\s*viewLengthSoftMax\\s*&&\\s*cloudMask\\s*==\\s*0\\.0\\s*\\)\\s*break\\s*;","if (!cmiCloudVisible(samplePos - cameraPos)) break;\n"));
    }
    private static void solas(Builder b) {
        String main="programs/deferred1.glsl";b.main(main,"Deferred",1,"cmiRiftMain_solas");
        b.edit(main,s->before(s,"computeVolumetricClouds\\s*\\(\\s*vc\\s*,","cmiMainView = true;\n"));
        b.edit(main,s->after(s,"computeVolumetricClouds\\s*\\([^;]+;","cmiMainView = false;\n"));
        b.edit(main,s->before(s,"drawPlanarClouds\\s*\\(\\s*pc\\s*,","cmiMainView = true;\n"));
        b.edit(main,s->after(s,"drawPlanarClouds\\s*\\(\\s*pc\\s*,[^;]+;","cmiMainView = false;\n"));
        b.edit(main,s->before(s,"skyColor\\s*\\*=\\s*1\\.0\\s*-\\s*blindFactor\\s*;","if (cmiRayActive(worldPos.xyz)) {\n skyColor = cmiNativeSky(skyColor, worldPos.xyz, 2.2, 1.0);\n #ifdef PLANAR_CLOUDS\n skyColor = fmix(skyColor, pc.rgb, pc.a * pc.a);\n #endif\n}\n"));
        b.edit(main,s->replace(s,"color\\s*\\+=\\s*aurora\\s*;","if (!cmiRayActive(worldPos.xyz)) color += aurora;"));
        String clouds="lib/atmosphere/volumetricClouds.glsl";b.helper(clouds,"computeVolumetricClouds");
        b.edit(clouds,s->after(s,"if\\s*\\(cloud\\s*>\\s*0\\.99[^;]+break\\s*;","if (!cmiCloudVisible(rayPos - cameraPosition)) break;\n"));
        // The planar function gets its own check through its actual intersection, not an assumed height.
        String planar=b.inventory.uniqueSuffix("planarClouds.glsl");ShaderPatchPlan.require(planar!=null,"missing planar cloud source");
        b.helper(planar,"drawPlanarClouds");
        b.edit(planar,s->before(s,"float\\s+coordLength\\s*=\\s*length\\s*\\(\\s*planeCoord\\.xz\\s*\\)\\s*;","if (!cmiCloudVisible(planeCoord * 1000.0)) return;\n"));
    }
    private static void sildur(Builder b) {
        String main="deferred.fsh";b.main(main,"Deferred",0,"cmiRiftMain_sildur");
        b.edit(main,s->before(s,"#if\\s+Clouds\\s*==\\s*2\\s*\\|\\|\\s*Clouds\\s*==\\s*4(?=\\s*skyC)","skyC = cmiNativeSky(skyC, cpos, 1.0, 1.0);\ncmiMainView = true;\n"));
        b.edit(main,s->before(s,"albedo\\.rgb\\s*=\\s*skyC\\s*;","cmiMainView = false;\n"));
        // Helpers occur before main in this combined shader, so place declarations before the first helper.
        b.helper(main,"utilScreenSpace");
        b.edit(main,s->after(s,"vec3\\s+intersection\\s*=\\s*tpos\\s*\\*\\s*\\(\\s*heightA[^;]+;","if (!cmiCloudVisible(intersection)) continue;\n"));
        b.edit(main,s->after(s,"for\\s*\\(int\\s+i\\s*=\\s*0;\\s*i\\s*<\\s*cloudIT;\\s*i\\+\\+\\)\\s*\\{","if (!cmiCloudVisible(progress_view - cameraPosition)) break;\n"));
    }
    private static void iteration(Builder b) {
        String main="deferred3.fsh";b.main(main,"Deferred",3,"cmiRiftMain_iteration");
        b.edit(main,s->after(s,"finalComposite\\s*\\+=\\s*atmosphere\\s*;","finalComposite = cmiNativeSky(finalComposite, worldDir, 1.0, MAIN_OUTPUT_FACTOR);\ncmiMainView = true;\n"));
        b.edit(main,s->before(s,"finalComposite\\s*\\+=\\s*celestial\\s*\\*\\s*transmittance\\s*\\*\\s*200\\.0\\s*;","if (cmiRayActive(worldDir)) celestial = vec3(0.0);\ncmiMainView = false;\n"));
        String nubis="Lib/IndividualFounctions/NUBIS.glsl";b.helper(nubis,"NubisCumulus");
        b.edit(nubis,s->after(s,"for\\s*\\(int\\s+i\\s*=\\s*0;\\s*i\\s*<\\s*int\\(marchingSteps\\);[^)]*\\)\\s*\\{","if (!cmiCloudVisible(marchingPos - cameraPosition)) break;\n"));
        String planar="Lib/IndividualFounctions/PlanarClouds.glsl";b.helper(planar,"PlanarClouds");
        b.edit(planar,s->after(s,"float\\s+intersection\\s*=\\s*RaySphereIntersection[^;]+;","if (!cmiCloudVisible(worldDir * intersection)) return;\n"));
    }
    private static void bliss(Builder b) {
        b.producer("dimensions/deferred.fsh","Deferred","calculateAtmosphere");
        b.producer("dimensions/composite3.fsh","Composite","cloudAlpha");
        String main="dimensions/composite1.fsh";b.main(main,"Composite",1,"cmiRiftMain_bliss");
        b.edit(main,s->before(s,"#if\\s+defined\\s+OVERWORLD_SHADER\\s*&&\\s*defined\\s+VOLUMETRIC_CLOUDS\\s*&&\\s*!defined\\s+CLOUDS_INTERSECT_TERRAIN(?=\\s*vec4\\s+Clouds)","Background = cmiNativeSky(Background, feetPlayerPos_normalized, 1.0, 1.0);\n"));
        // This statement is outside the conditional cloud option, preserving a visible window with clouds disabled.
        b.edit(main,s->before(s,"#if\\s+RESOURCEPACK_SKY\\s*==\\s*1\\s*\\|\\|\\s*RESOURCEPACK_SKY\\s*==\\s*2\\s*\\|\\|\\s*RESOURCEPACK_SKY\\s*==\\s*3(?=\\s*vec3\\s+resourcePackskyBox)","\n"));
        b.edit(main,s->before(s,"gl_FragData\\[0\\]\\.rgb\\s*=\\s*clamp\\(fp10Dither\\(Background[^;]+;","Background = cmiNativeSky(Background, feetPlayerPos_normalized, 1.0, 1.0);\n#if defined OVERWORLD_SHADER && defined VOLUMETRIC_CLOUDS && !defined CLOUDS_INTERSECT_TERRAIN\nif (cmiRayActive(feetPlayerPos_normalized)) { vec4 cmiClouds = texture2D_bicubic_offset(colortex0, texcoord*CLOUDS_QUALITY, noise, RENDER_SCALE.x); Background = cmiNativeSky(vec3(0.0), feetPlayerPos_normalized, 1.0, 1.0) * cmiClouds.a + cmiClouds.rgb; }\n#endif\n"));
        for(String path:List.of("dimensions/deferred2.fsh","dimensions/composite2.fsh")) {
            b.main(path,path.contains("deferred")?"Deferred":"Composite",2,"cmiRiftClouds_bliss_"+(path.contains("deferred")?"deferred":"composite"));
            b.edit(path,s->mainStart(s,"cmiMainView = true;\n"));
        }
        b.edit(main,s->mainStart(s,"cmiMainView = true;\n"));
        String clouds="lib/volumetricClouds.glsl";b.helper(clouds,"renderLayer");
        b.edit(clouds,s->before(s,"if\\s*\\(layer\\s*==\\s*2\\)\\s*\\{","if (!cmiCloudVisible(rayProgress - cameraPosition)) return vec4(COLOR, TOTAL_EXTINCTION);\n"));
        b.edit(clouds,s->after(s,"for\\s*\\(int\\s+i\\s*=\\s*0;\\s*i\\s*<\\s*QUALITY;\\s*i\\+\\+\\)\\s*\\{","if (!cmiCloudVisible(rayProgress - cameraPosition)) break;\n"));
    }
    private static void sundial(Builder b) {
        String main="programs/composite/Composite4.frag";b.main(main,"Composite",4,"cmiRiftMain_sundial");
        b.edit(main,s->mainStart(s,"cmiMainView = true;\n"));
        b.edit(main,s->after(s,"solidColor\\.rgb\\s*=\\s*singleAtmosphereScattering[^;]+;","solidColor.rgb = cmiNativeSky(solidColor.rgb, worldDir, 1.0, 1.0);\nif (cmiRayActive(worldDir)) backColor = solidColor.rgb;\n"));
        String clouds="libs/Cloud.glsl";b.helper(clouds,"sampleClouds");
        b.edit(clouds,s->after(s,"vec4\\s+sampleClouds\\s*\\([^)]*\\)\\s*\\{","if (cmiMainView && CmiRiftEnabled > 0.5 && CmiSkyDimension == 0) {\n float cmiLimit = cmiRiftDistance(normalize(worldDir), CmiRift);\n if (cmiLimit > 0.0) backDepth = backDepth > 0.00001 ? min(backDepth, cmiLimit) : cmiLimit;\n}\n"));
        b.helper(clouds,"planeClouds");
        b.edit(clouds,s->after(s,"float\\s+intersection\\s*=[^;]*planeCloudHeight[^;]*;","if (!cmiCloudVisible(worldDir * intersection)) return vec4(0.0);\n"));
    }
    private static void photon(Builder b) {
        b.producer("program/d0_sky_map.fsh","Deferred","draw_sky");
        String main="program/d4_deferred_shading.fsh";b.main(main,"Deferred",4,"cmiRiftMain_photon");
        b.edit(main,s->mainStart(s,"cmiMainView = true;\n"));
        b.edit(main,s->before(s,"#if\\s+defined\\s+WORLD_OVERWORLD\\s*&&\\s*defined\\s+BLOCKY_CLOUDS(?=\\s*fragment_color)","if (cmiRayActive(direction_world)) {\n fragment_color = cmiNativeSky(fragment_color, direction_world, 1.0, 1.0);\n #if defined WORLD_OVERWORLD\n fragment_color = fragment_color * clouds_and_aurora.w + clouds_and_aurora.xyz;\n #endif\n}\n"));
        b.edit(main,s->after(s,"vec3\\s+world_end_pos\\s*=\\s*position_world\\s*;","if (cmiRayActive(direction_world)) world_end_pos = world_start_pos + direction_world * cmiCloudLimit(direction_world, length(world_end_pos-world_start_pos));\n"));
        b.edit(main,s->{String code=ShaderPackInventory.code(s);Matcher m=Pattern.compile("(raymarch_blocky_clouds\\s*\\(\\s*world_start_pos\\s*,\\s*world_end_pos\\s*,\\s*)depth\\s*==\\s*1\\.0").matcher(code);var out=new StringBuilder(s);var ranges=new ArrayList<int[]>();while(m.find())ranges.add(new int[]{m.end(1),m.end()});ShaderPatchPlan.require(ranges.size()==2,"blocky cloud call structure");for(int i=ranges.size()-1;i>=0;i--)out.replace(ranges.get(i)[0],ranges.get(i)[1],"(depth == 1.0 && !cmiRayActive(direction_world))");return out.toString();});
        String cloud="program/d1_clouds.fsh";b.main(cloud,"Deferred",1,"cmiRiftClouds_photon");
        b.edit(cloud,s->after(s,"float\\s+distance_to_terrain\\s*=[^;]+;","if (CmiRiftEnabled > 0.5 && CmiSkyDimension == 0) {\n float cmiLimit = cmiRiftDistance(normalize(ray_dir), CmiRift);\n if (cmiLimit > 0.0) distance_to_terrain = distance_to_terrain < 0.0 ? cmiLimit * CLOUDS_SCALE : min(distance_to_terrain, cmiLimit * CLOUDS_SCALE);\n}\n"));
        b.edit(cloud,s->before(s,"clouds\\.xyz\\s*\\+=\\s*draw_aurora","if (!cmiRayActive(ray_dir))\n"));
        String upscale="program/d2_clouds_upscaling.fsh";b.main(upscale,"Deferred",2,"cmiRiftHistory_photon");
        b.edit(upscale,s->after(s,"disocclusion\\s*=\\s*disocclusion\\s*\\|\\|\\s*world_age_changed\\s*;","disocclusion = disocclusion || CmiRiftReactive > 0.5 || cmiRayActive(mat3(gbufferModelViewInverse) * screen_to_view_space(vec3(uv, 1.0), false, false));\n"));
    }
}
