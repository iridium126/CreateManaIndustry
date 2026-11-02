package com.iridium126.createmanaindustry.client.particles.shaderpack;

import java.util.regex.Pattern;

/** Specializes declarations in CMI's own generated input only. Pack stages are never rewritten here. */
public final class ShadowVertexContext {
    private static final Pattern SIDE_EFFECT=Pattern.compile("\\b(?:[iu]?image\\w*|atomic\\w*|buffer|memoryBarrier\\w*)\\b");
    private static final Pattern COMMENTS=Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*");
    private static final Pattern SHADOW=Pattern.compile("\\buniform\\s+bool\\s+cmi_ShadowPass\\s*;");
    private static final Pattern LOD=Pattern.compile("\\buniform\\s+int\\s+cmi_LodPass\\s*;");
    private static final Pattern FEATHER=Pattern.compile("\\buniform\\s+int\\s+cmi_FeatherPass\\s*,\\s*cmi_FeatherWordBase\\s*;");
    public record Result(String source,boolean applied,String reason) {}
    private ShadowVertexContext() {}
    public static Result prepare(String own,String vertex,String geometry,String tessControl,String tessEval,String fragment){
        if(geometry!=null||tessControl!=null||tessEval!=null)return new Result(own,false,"native auxiliary stages retained");
        String pack=COMMENTS.matcher(vertex+"\n"+fragment).replaceAll("");
        if(SIDE_EFFECT.matcher(pack).find())return new Result(own,false,"native image/atomic/storage context retained");
        if(SHADOW.matcher(own).results().count()!=1||LOD.matcher(own).results().count()!=1||FEATHER.matcher(own).results().count()!=1)
            return new Result(own,false,"CMI declaration contract unavailable");
        String source=SHADOW.matcher(own).replaceAll("const bool cmi_ShadowPass=true;");
        source=LOD.matcher(source).replaceAll("const int cmi_LodPass=0;");
        source=FEATHER.matcher(source).replaceAll("const int cmi_FeatherPass=0; uniform int cmi_FeatherWordBase;");
        return new Result(source,true,"constant shadow/feather/LOD input context");
    }
}
