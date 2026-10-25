package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;
import java.util.regex.Pattern;

/** Structural scene contracts migrated from the historical native compositor. No pack-name matching. */
public record NativeSceneContract(String id,int buffer,int compositeIndex,String readerPath,
        String readPattern,String operationPattern,float gamma,boolean alphaBlendEncoding,boolean hdr) {
    public boolean acceptsActiveSource(String source) {
        String code=ShaderPackInventory.code(source);
        return matches(code,"uniform\\s+sampler2D\\s+colortex"+buffer+"\\s*;")&&matches(code,readPattern)&&matches(code,operationPattern);
    }
    public float gammaForAlphaBlend(double value){return alphaBlendEncoding?(value==0?2:1):gamma;}
    public float effectiveGamma(){return gammaForAlphaBlend(ActivePackOptions.doubleValue("ALPHA_BLEND",0));}
    public String evidence(){return readerPath+" -> colortex"+buffer+"; "+(alphaBlendEncoding?"effective ALPHA_BLEND encoding":"gamma="+gamma);}
    public static List<NativeSceneContract> detect(ShaderPackInventory inventory) {
        var found=new ArrayList<NativeSceneContract>();
        add(found,inventory,new NativeSceneContract("layered-linear",0,1,"program/c1_blend_layers.fsh",
                "fragment_color\\s*=\\s*texture\\s*\\(\\s*colortex0\\b","fragment_color\\s*=\\s*blend_layers_with_fog\\s*\\(",1,false,false),
                "world0/composite1.fsh","include/global.glsl");
        add(found,inventory,new NativeSceneContract("forward-power",0,1,"program/composite1.glsl",
                "vec3\\s+color\\s*=\\s*texelFetch\\s*\\(\\s*colortex0\\b","color\\s*=\\s*pow\\s*\\(\\s*color\\s*,\\s*vec3\\s*\\(\\s*2\\.2\\s*\\)",2.2f,false,false),
                "world0/composite1.fsh","program/composite.glsl");
        if(inventory.containsCode("program/gbuffers_terrain.glsl","albedo\\.rgb\\s*=\\s*sqrt\\s*\\(\\s*max\\s*\\(\\s*albedo\\.rgb"))
            add(found,inventory,new NativeSceneContract("forward-alpha-blend",0,0,"program/composite.glsl",
                    "vec4\\s+color\\s*=\\s*texture2D\\s*\\(\\s*colortex0\\b","gl_FragData\\s*\\[\\s*0\\s*]\\s*=\\s*color\\s*;",2,true,false),
                    "world0/composite.fsh","lib/settings.glsl");
        if(inventory.containsCode("lib/lighting/gbuffersLighting.glsl","albedo\\.rgb\\s*=\\s*pow\\s*\\(\\s*max\\s*\\(\\s*finalColor"))
            add(found,inventory,new NativeSceneContract("forward-lighting-power",0,0,"programs/composite.glsl",
                    "vec3\\s+color\\s*=\\s*texture2D\\s*\\(\\s*colortex0\\b","color\\s*=\\s*pow\\s*\\(\\s*color\\s*,\\s*vec3\\s*\\(\\s*2\\.2\\s*\\)",2.2f,false,false),
                    "composite.fsh","programs/gbuffers_terrain.glsl");
        if(drawsTo(inventory.source("deferred1.fsh"),4)&&drawsTo(inventory.source("composite1.fsh"),4))
            add(found,inventory,new NativeSceneContract("deferred-four-power",4,1,"composite1.fsh",
                    "vec4\\s+albedo\\s*=\\s*texture2D\\s*\\(\\s*colortex4\\b","albedo\\.rgb\\s*=\\s*pow\\s*\\(\\s*albedo\\.rgb\\s*,\\s*vec3\\s*\\(\\s*2\\.2\\s*\\)",2.2f,false,false),
                    "deferred1.fsh","shaders.settings");
        // Format directives intentionally live inside comments in this family.
        if(matches(inventory.source("programs/composite/Composite13.frag"),"const\\s+int\\s+colortex3Format\\s*=\\s*RGBA16F\\s*;")
                &&matches(inventory.source("shaders.properties"),"(?m)^\\s*texture\\.composite\\.colortex7\\s*=[^\\r\\n]*AtmosphereTransmittance\\.bin\\s+TEXTURE_2D\\s+RGB16\\b"))
            add(found,inventory,new NativeSceneContract("deferred-hdr-lut",3,14,"programs/composite/Composite14.frag",
                    "textureLod\\s*\\(\\s*colortex3\\b","return\\s+color\\s*\\*\\s*pow\\s*\\(\\s*averageBrightness\\s*\\+\\s*1e-5\\s*,\\s*-\\s*(?:AVERAGE_EXPOSURE_STRENGTH|[0-9]+(?:\\.[0-9]+)?)\\s*\\)\\s*\\*\\s*0\\.2\\s*;",1,false,true),
                    "composite14.fsh","programs/composite/Composite0.frag");
        return List.copyOf(found);
    }
    private static void add(List<NativeSceneContract> results,ShaderPackInventory inventory,NativeSceneContract contract,String entry,String companion){
        String reader=ShaderPackInventory.code(inventory.source(contract.readerPath));
        if(inventory.source(entry).isEmpty()||inventory.source(companion).isEmpty()||!matches(reader,contract.readPattern)||!matches(reader,contract.operationPattern))return;
        var overrides=Pattern.compile("(?m)^\\s*texture\\.composite\\.colortex"+contract.buffer+"\\s*=([^\\r\\n]*)").matcher(inventory.source("shaders.properties"));
        while(overrides.find())if(!overrides.group(1).contains("TEXTURE_3D"))return;
        results.add(contract);
    }
    private static boolean drawsTo(String source,int buffer){return matches(source,"/\\*\\s*DRAWBUFFERS\\s*:\\s*"+buffer+"\\s*\\*/")||matches(source,"/\\*\\s*RENDERTARGETS\\s*:\\s*"+buffer+"\\s*\\*/");}
    private static boolean matches(String source,String pattern){return Pattern.compile(pattern,Pattern.DOTALL).matcher(source).find();}
}
