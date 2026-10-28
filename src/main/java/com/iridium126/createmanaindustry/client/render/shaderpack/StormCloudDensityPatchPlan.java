package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;
import static com.iridium126.createmanaindustry.client.render.shaderpack.NativeRiftPatchPlan.*;

/** Independently atomic density candidates. Stock coordinates, occupancy and history stay intact. */
public final class StormCloudDensityPatchPlan {
    private final String family,reason;
    private final Map<String,String> originals,edits;
    private StormCloudDensityPatchPlan(String family,String reason,Map<String,String> originals,Map<String,String> edits){
        this.family=family;this.reason=reason;this.originals=Map.copyOf(originals);this.edits=Map.copyOf(edits);
    }
    public static StormCloudDensityPatchPlan disabled(String reason){return new StormCloudDensityPatchPlan("unknown",reason,Map.of(),Map.of());}
    public boolean enabled(){return !edits.isEmpty();}
    public String reason(){return reason;}
    public String family(){return family;}
    public Map<String,String> edits(){return edits;}
    public String transform(String path,String source){
        path=ShaderPackInventory.normalize(path);if(!edits.containsKey(path))return source;
        ShaderPatchPlan.require(Objects.equals(originals.get(path),source),"density source changed during load: "+path);
        return edits.get(path);
    }
    public static StormCloudDensityPatchPlan prepare(ShaderPackInventory inventory,String family){
        var b=new Builder(inventory);
        try {
            switch(family){
                case "complementary" -> complementary(b);
                case "bsl" -> {
                    String p="lib/atmospherics/clouds.glsl";b.helper(p,"CloudApplyDensity");
                    parameter(b,p,"CloudApplyDensity","cmiDensityFactor");
                    parameter(b,p,"CloudCombineDefault","cmiDensityFactor");
                    parameter(b,p,"CloudCombineBlocky","cmiDensityFactor");
                    b.edit(p,s->after(s,"noise\\s*\\*=\\s*CLOUD_DENSITY\\s*\\*\\s*0\\.125\\s*;","noise*=cmiDensityFactor;"));
                    b.edit(p,s->replace(s,"float\\s+CloudSample\\s*\\(([^)]*)\\)","float CloudSample(vec2 coord,vec2 wind,float cloudGradient,float sunCoverage,float dither,float cmiDensityFactor)"));
                    b.edit(p,s->after(s,"float\\s+CloudSample\\s*\\([^)]*\\)\\s*\\{","if(cmiDensityFactor==0.0)return 0.0;"));
                    b.edit(p,s->replace(s,"CloudSample\\(cloudCoord,\\s*wind,\\s*currentStep,\\s*sunCoverage,\\s*dither\\)",
                            "CloudSample(cloudCoord,wind,currentStep,sunCoverage,dither,cmiStormCloudDensity(planeCoord*CLOUD_SCALE))"));
                    b.edit(p,s->replace(s,"CloudSample\\(cloudCoord,\\s*wind,\\s*cloudGradient,\\s*sunCoverage,\\s*dither\\)",
                            "CloudSample(cloudCoord,wind,cloudGradient,sunCoverage,dither,cmiStormCloudDensity(samplePos-cameraPos))"));
                }
                case "bliss" -> {
                    String p="lib/volumetricClouds.glsl";
                    b.wrap(p,"GetAltostratusDensity","float m=cmiStormCloudDensity(pos-cameraPosition); if(m==0.0)return 0.0; return cmiStock_GetAltostratusDensity(pos)*m;");
                    b.wrap(p,"GetCumulusDensity","float m=cmiStormCloudDensity(pos-cameraPosition); if(m==0.0)return 0.0; return cmiStock_GetCumulusDensity(layer,pos,LoD,minHeight,maxHeight)*m;");
                }
                case "solas" -> solas(b);
                case "iteration" -> iteration(b);
                case "photon" -> photon(b);
                case "sildur" -> {
                    String p="deferred.fsh";
                    b.edit(p,s->after(s,"float\\s+cl\\s*=\\s*max[^;]+;","cl=cmiCloudOpacity(cl,cmiStormCloudDensity(intersection));"));
                    b.edit(p,s->after(s,"float\\s+cloud\\s*=\\s*\\(1\\.0\\s*-\\s*pow\\(0\\.4[^;]+;","cloud*=cmiStormCloudDensity(progress_view-cameraPosition);"));
                }
                case "sundial-lite" -> sundial(b);
                default -> {return disabled("no unambiguous native density contract");}
            }
            return new StormCloudDensityPatchPlan(family,"preflight: world-position density; below rift; stock noise/occupancy/history",inventory.sources(),b.edits);
        } catch(RuntimeException error){return new StormCloudDensityPatchPlan(family,"disabled: "+error.getMessage(),Map.of(),Map.of());}
    }
    private static void complementary(Builder b){
        String main="lib/atmospherics/clouds/mainClouds.glsl";
        b.edit(main,s->RiftShaderSources.source()+"\nvec3 cmiCloudCamera=vec3(0.0);\n"+s);
        b.edit(main,s->after(s,"cameraPos\\s*\\+=\\s*cameraPosOffset\\s*;","cmiCloudCamera=cameraPos;"));
        b.wrap("lib/atmospherics/clouds/unboundClouds.glsl","GetCloudNoise",
                "float m=cmiStormCloudDensity(tracePos-cmiCloudCamera); if(m==0.0)return 0.0; return cmiStock_GetCloudNoise(tracePos,cloudAltitude,lTracePosXZ,cloudPlayerPosY)*m;");
        String p="lib/atmospherics/clouds/reimaginedClouds.glsl";
        b.edit(p,s->after(s,"if\\s*\\(GetCloudNoise\\(tracePos,\\s*tracePosM,\\s*cloudAltitude\\)\\)\\s*\\{",
                "float cmiDensity=cmiStormCloudDensity(tracePos-cmiCloudCamera); if(cmiDensity==0.0)continue;"));
        b.edit(p,s->{String needle="Get2DCloudSample(cLightPos.xz) * cloudShadingM";
            ShaderPatchPlan.require(s.split(java.util.regex.Pattern.quote(needle),-1).length==3,"two native Reimagined lighting samples");
            return s.replace(needle,"cmiCloudOpacity(Get2DCloudSample(cLightPos.xz),cmiStormCloudDensity(tracePos-cmiCloudCamera+vec3((cLightPos.x-tracePosM.x)/cloudNarrowness,cLightPos.y-tracePosM.y,(cLightPos.z-tracePosM.z)/cloudNarrowness))) * cloudShadingM");});
        b.edit(p,s->after(s,"volumetricClouds\\.a\\s*=\\s*sqrt[^;]+;",
                "volumetricClouds.a=cmiCloudOpacity(volumetricClouds.a,cmiDensity);"));
    }
    private static void solas(Builder b){
        String p="lib/atmosphere/volumetricClouds.glsl";b.helper(p,"CloudApplyDensity");
        parameter(b,p,"CloudApplyDensity","cmiDensityFactor");
        parameter(b,p,"CloudCombineDefault","cmiDensityFactor");
        b.edit(p,s->after(s,"noise\\s*\\*=\\s*density\\s*\\*\\s*0\\.125\\s*;","noise*=cmiDensityFactor;"));
        for(String name:List.of("CloudSample","CloudSampleLowDetail")){
            b.edit(p,s->replace(s,"float\\s+"+name+"\\s*\\([^)]*\\)","float "+name+"(vec2 coord,vec2 wind,float sampleAltitude,float thickness,float amount,float density,float cmiDensityFactor)"));
            b.edit(p,s->after(s,"float\\s+"+name+"\\s*\\([^)]*\\)\\s*\\{","if(cmiDensityFactor==0.0)return 0.0;"));
        }
        b.edit(p,s->replace(s,"CloudSample\\(cloudCoord,\\s*wind,\\s*sampleAltitude,\\s*thickness,\\s*amount,\\s*density\\)",
                "CloudSample(cloudCoord,wind,sampleAltitude,thickness,amount,density,cmiStormCloudDensity(rayPos-cameraPosition))"));
        b.edit(p,s->replace(s,"CloudSampleLowDetail\\(cloudCoord\\s*\\+\\s*worldLightVec\\.xy,\\s*wind,\\s*sampleAltitudeL,\\s*thickness,\\s*amount,\\s*density\\)",
                "CloudSampleLowDetail(cloudCoord+worldLightVec.xy,wind,sampleAltitudeL,thickness,amount,density,cmiStormCloudDensity(vec3(rayPos.x+worldLightVec.x*scale,rayPos.y+worldLightVec.y,rayPos.z+worldLightVec.y*scale)-cameraPosition))"));
        String planar=b.inventory.uniqueSuffix("planarClouds.glsl");
        b.edit(planar,s->after(s,"vec3\\s+planeCoord\\s*=[^;]+;","vec3 cmiPoint=planeCoord*1000.0;"));
        b.edit(planar,s->replace(s,"float\\s+samplePlanarCloudNoise\\s*\\(vec2\\s+coord\\)","float samplePlanarCloudNoise(vec2 coord,float cmiDensityFactor)"));
        b.edit(planar,s->after(s,"float\\s+samplePlanarCloudNoise\\s*\\([^)]*\\)\\s*\\{","if(cmiDensityFactor==0.0)return 0.0;"));
        b.edit(planar,s->replace(s,"return\\s+clamp\\(pow\\(noise,\\s*1\\.5\\),\\s*0\\.0,\\s*1\\.0\\);","return clamp(pow(noise,1.5)*cmiDensityFactor,0.0,1.0);"));
        b.edit(planar,s->replace(s,"samplePlanarCloudNoise\\(coord\\)","samplePlanarCloudNoise(coord,cmiStormCloudDensity(cmiPoint))"));
        b.edit(planar,s->replace(s,"samplePlanarCloudNoise\\(coord\\s*\\+\\s*worldLightVec\\.xz\\s*\\*\\s*0\\.025\\)",
                "samplePlanarCloudNoise(coord+worldLightVec.xz*0.025,cmiStormCloudDensity(cmiPoint+vec3(worldLightVec.x,0.0,worldLightVec.z)*(20.0/(2.0-distanceFactor))))"));
    }
    /** Add a scoped scalar argument through native density functions, never a mutable last-sample global. */
    private static void parameter(Builder b,String path,String name,String value){
        b.edit(path,source->{
            String code=ShaderPackInventory.code(source);var m=java.util.regex.Pattern.compile("\\b"+name+"\\s*\\(").matcher(code);
            var edits=new ArrayList<int[]>();int definitions=0;
            while(m.find()){
                int close=m.end(),depth=1;while(depth>0&&close<code.length()){char c=code.charAt(close++);if(c=='(')depth++;if(c==')')depth--;}
                ShaderPatchPlan.require(depth==0,"unbalanced density call "+name);int next=close;while(next<code.length()&&Character.isWhitespace(code.charAt(next)))next++;
                boolean definition=next<code.length()&&code.charAt(next)=='{';if(definition)definitions++;edits.add(new int[]{close-1,definition?1:0});
            }
            ShaderPatchPlan.require(definitions==1&&edits.size()>=2,"density definition/call contract "+name);
            var out=new StringBuilder(source);for(int i=edits.size()-1;i>=0;i--){var e=edits.get(i);out.insert(e[0],","+(e[1]==1?"float ":"")+value);}return out.toString();
        });
    }
    private static void iteration(Builder b){
        String p="Lib/IndividualFounctions/NUBIS.glsl";
        // Carry the ORIGINAL world sample into each call, including all lighting probes.
        b.edit(p,s->before(s,"vec2\\s+SampleDensity\\s*\\(",RiftShaderSources.source()+"\n"));
        b.edit(p,s->after(s,"float\\s+lightingDensity\\s*=\\s*0\\.0\\s*;","float cmiLightDensity=1.0;"));
        b.edit(p,s->after(s,"vec4\\s+checkPos\\s*=\\s*SetCloudPos\\([^;]+;",
                "cmiLightDensity=cmiStormCloudDensity(marchingPos+worldShadowVector*stepLength*noise-cameraPosition);"));
        b.edit(p,s->replace(s,"SampleDensity\\(checkPos,\\s*windDirection,\\s*i\\s*<\\s*3\\)\\.y",
                "(cmiLightDensity==0.0?0.0:SampleDensity(checkPos,windDirection,i<3).y*cmiLightDensity)"));
        b.edit(p,s->replace(s,"vec2\\s+cloudDensity\\s*=\\s*SampleDensity\\(cloudPos,\\s*windDirection,\\s*true\\)\\s*;",
                "float cmiDensity=cmiStormCloudDensity(marchingPos-cameraPosition); vec2 cloudDensity=cmiDensity==0.0?vec2(0.0):SampleDensity(cloudPos,windDirection,true)*cmiDensity;"));
        String planar="Lib/IndividualFounctions/PlanarClouds.glsl";
        for(String name:List.of("GetPlanarCloudsDensity","GetPlanarCloudsLightingDensity"))
            b.wrap(planar,name,"vec3 relative=vec3(position.x-cameraPosition.x,PC_ALTITUDE-cameraPosition.y,position.y-cameraPosition.z); float m=cmiStormCloudDensity(relative); if(m==0.0)return 0.0; return cmiStock_"+name+"(position,windDirection)*m;");
    }
    private static void photon(Builder b){
        b.edit("program/d1_clouds.fsh",s->mainStart(s,"cmiMainView=true;\n"));
        // Atmosphere coordinates are camera-centred in XZ and scaled in all three axes.
        for(String layer:List.of("cumulus","altocumulus","cumulus_congestus")){
            String p="include/sky/clouds/"+layer+".glsl",name="clouds_"+layer+"_density";
            b.wrap(p,name,"vec3 relative=vec3(pos.x/CLOUDS_SCALE,(pos.y-planet_radius)/CLOUDS_SCALE+SEA_LEVEL-eyeAltitude,pos.z/CLOUDS_SCALE); float m=cmiStormCloudDensity(relative); if(m==0.0)return 0.0; return cmiStock_"+name+"(pos)*m;");
        }
        String cirrus="include/sky/clouds/cirrus.glsl";
        b.helper(cirrus,"clouds_cirrus_density");
        // The two-argument API is shared by prepare/cloud shadows. Keep it stock and add
        // a view-only wrapper after its definition, with the actual spherical sample.
        b.edit(cirrus,s->{
            String code=ShaderPackInventory.code(s);var function=ShaderPatchPlan.unique(code,"float\\s+clouds_cirrus_density\\s*\\(vec2\\s+coord,\\s*float\\s+altitude_fraction\\)\\s*\\{");
            int end=function.end(),depth=1;while(depth>0&&end<code.length()){char c=code.charAt(end++);if(c=='{')depth++;if(c=='}')depth--;}
            ShaderPatchPlan.require(depth==0,"cirrus density body");
            return s.substring(0,end)+"\nfloat cmiCirrusViewDensity(vec2 coord,float altitude_fraction,vec3 cmiSample){\nvec3 relative=vec3(cmiSample.x/CLOUDS_SCALE,(cmiSample.y-planet_radius)/CLOUDS_SCALE+SEA_LEVEL-eyeAltitude,cmiSample.z/CLOUDS_SCALE); float m=cmiStormCloudDensity(relative); if(m==0.0)return 0.0; return clouds_cirrus_density(coord,altitude_fraction)*m;\n}\n"+s.substring(end);
        });
        b.edit(cirrus,s->replace(s,"clouds_cirrus_density\\(sphere_pos\\.xz,\\s*altitude_fraction\\)","cmiCirrusViewDensity(sphere_pos.xz,altitude_fraction,dithered_pos)"));
        b.edit(cirrus,s->replace(s,"clouds_cirrus_density\\(sphere_pos\\.xz,\\s*0\\.5\\)","cmiCirrusViewDensity(sphere_pos.xz,0.5,sphere_pos)"));
        b.wrap("include/sky/blocky_clouds.glsl","blocky_clouds_density",
                "float m=cmiStormCloudDensity(world_pos-cameraPosition); if(m==0.0)return 0.0; return cmiStock_blocky_clouds_density(world_pos,altitude_fraction,layer_offset)*m;");
    }
    private static void sundial(Builder b){
        String p="libs/Cloud.glsl";b.helper(p,"blockyCloudDensity");
        b.edit(p,s->after(s,"float\\s+sampleCloudDensity\\s*=\\s*blockyCloudDensity\\(sampleCloudTexel\\)\\s*;",
                "float cmiDensity=cmiStormCloudDensity(worldPos+worldDir*(intersection+(totalRayLength+0.5*rayLength)/cloudNoiseScale));"));
        b.edit(p,s->replace(s,"sampleCloudDensity\\s*>\\s*0\\.5\\s*&&\\s*cloudOpticalDepth\\s*==\\s*0\\.0",
                "sampleCloudDensity > 0.5 && cmiDensity > 0.0 && cloudOpticalDepth == 0.0"));
        b.edit(p,s->replace(s,"cloudOpticalDepth\\s*\\+=\\s*rayLength\\s*\\*\\s*step\\(0\\.5,\\s*sampleCloudDensity\\)\\s*;",
                "cloudOpticalDepth += rayLength*step(0.5,sampleCloudDensity)*cmiDensity;"));
        b.edit(p,s->after(s,"float\\s+shadowOpticalDepth\\s*=\\s*0\\.0\\s*;",
                "vec3 cmiShadowPoint=worldPos+worldDir*(intersection+hitLength);"));
        b.edit(p,s->replace(s,"shadowOpticalDepth\\s*\\+=\\s*rayLength\\s*\\*\\s*step\\(0\\.5,\\s*sampleCloudDensity\\)\\s*;",
                "shadowOpticalDepth += rayLength*step(0.5,sampleCloudDensity)*cmiStormCloudDensity(cmiShadowPoint+shadowDir*(0.5*rayLength/cloudNoiseScale)); cmiShadowPoint+=shadowDir*(rayLength/cloudNoiseScale);"));
        b.wrap(p,"realisticCloudDensity",
                "vec3 relative=cloudPos-vec3(cameraPosition.x+CLOUD_REALISTIC_OFFSET_X,earthRadius+max(300.0,cameraPosition.y+WORLD_BASIC_HEIGHT),cameraPosition.z+CLOUD_REALISTIC_OFFSET_Z); float m=cmiStormCloudDensity(relative); if(m==0.0)return 0.0; return cmiStock_realisticCloudDensity(cloudPos,wind,cloudDistance,octaves,weights)*m;");
        b.edit(p,s->after(s,"cloudDensity\\s*=\\s*clamp\\(cloudDensity\\s*\\+\\s*PLANE_CLOUD_AMOUNT[^;]+;",
                "cloudDensity*=cmiStormCloudDensity(worldPos+worldDir*intersection-vec3(0.0,earthRadius+max(300.0,cameraPosition.y+WORLD_BASIC_HEIGHT),0.0));"));
    }
}
