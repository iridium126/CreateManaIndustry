package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.List;
import com.iridium126.createmanaindustry.CreateManaIndustry;
/** Loading-session backed shadow convention resolver; absence and ambiguity never imply identity distortion. */
public final class ShadowDistortionRegistry {
    private static final List<ShadowDistortionConvention> CONVENTIONS=List.of(new QuarticShadowDistortion(),new EuclideanShadowDistortion(),new LogShadowDistortion(),new SundialShadowDistortion(),new IterationTShadowDistortion());
    private static String signature="";
    private static PackShadowParams params=PackShadowParams.NONE;
    private static boolean recognized;
    private ShadowDistortionRegistry(){}
    public static PackShadowParams resolveForCurrentPack(){
        String next=ShaderPackSession.current().revision()+"/"+EuclideanShadowDistortion.shadowDistance();
        if(next.equals(signature))return params;
        signature=next;params=PackShadowParams.NONE;recognized=false;
        try {
            PackShaderSource source=()->ShaderPackSession.current().inventory().sources().entrySet().stream()
                    .filter(e->e.getKey().endsWith(".properties")||e.getKey().toLowerCase(java.util.Locale.ROOT).contains("shadow")||e.getKey().toLowerCase(java.util.Locale.ROOT).contains("setting"))
                    .map(java.util.Map.Entry::getValue).toList();
            int matches=0;
            for(var c:CONVENTIONS){var p=c.tryResolve(source);if(p!=null){params=p;matches++;}}
            recognized=matches==1;if(!recognized)params=PackShadowParams.NONE;
        }catch(RuntimeException|LinkageError failure){CreateManaIndustry.LOGGER.warn("[CMI shadow] contract resolution failed",failure);params=PackShadowParams.NONE;}
        return params;
    }
    public static boolean recognized(){resolveForCurrentPack();return recognized;}
}
