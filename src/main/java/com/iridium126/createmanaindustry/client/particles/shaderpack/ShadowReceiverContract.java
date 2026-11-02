package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact local source identities. Recognition alone never authorizes a material rewrite. */
public record ShadowReceiverContract(String pack,boolean forward,String reason) {
    private static final Map<String,String> PACKS=Map.of(
            "807b51800a84e402fca2e7ae0f5b8c60f3f84c75b4fc90cd3b57e51b056dd628","Bliss 2.1.2",
            "58ea9d5b9c7c4fd4dd2a210a75729d791768cbb81c04d291f90c555c68438646","BSL 10.1.1",
            "da43d77bf336ce3b0b6e818b48cb6146bc6679bdbaa84dbd0287a150d71f2142","Complementary Unbound 5.9.3",
            "9caab82da28453bda5ac4f9e4d88bedf3bbc01c3fbea5b9e2d095f81ecf96943","iterationT 3.2.0",
            "8dc071d0c4e6700283f5092c744092b8bf6793054456d2caf47256ce150758b8","Photon 1.4",
            "a44e1d16845cecfe616e148c081bb0239ee4b377b6b03fe9a02cb41e13267764","Sildur Vibrant 2.02 Extreme",
            "006360226d5eb97b9c6d3e2ac8f98ed72f1a788459d82f9fee3f6a3abf1bbd34","Solas 3.8",
            "3b4e3c249e052d6dd2f7250d2b66f15b39906a76c124b865d77bf9589add8219","Sundial Lite 1.2.0");
    public static ShadowReceiverContract resolve(ShaderPackInventory inventory){
        String pack=PACKS.get(inventory.digest());
        if(pack==null)return new ShadowReceiverContract("unknown/modified",false,"source inventory does not match a fixed local contract");
        if(pack.startsWith("BSL"))return new ShadowReceiverContract(pack,true,"native forward visibility functions; directional lighting retained");
        String reason=switch(pack){
            case "Bliss 2.1.2","Photon 1.4","Sundial Lite 1.2.0"->"deferred receiver coverage/depth channel not implemented";
            case "Complementary Unbound 5.9.3"->"native directional/visibility split not verified";
            case "iterationT 3.2.0"->"deferred and transparent receiver contract not verified";
            case "Solas 3.8"->"native fake-shadow/cloud branches not verified";
            default->"native angular shadow term not separated from visibility";
        };
        return new ShadowReceiverContract(pack,false,reason);
    }
    public ModelShaderOptimization.Stages adapt(ModelShaderOptimization.Stages stages){
        if(!forward)throw new IllegalStateException(reason);
        String s=stages.fragment(),code=ShaderPackInventory.code(s);
        // This is the *effective* preprocessed CMI fragment, never the pack's shared program.
        if(!Pattern.compile("\\bvoid\\s+GetLighting\\s*\\(").matcher(code).find())throw new IllegalArgumentException("effective BSL GetLighting missing");
        s=visibility(s,"GetShadow","vec3","vec3(1.0)",true);
        s=visibility(s,"GetSubsurfaceShadow","vec3","vec3(1.0)",false);
        s=visibility(s,"GetCloudShadow","float","1.0",false);
        return new ModelShaderOptimization.Stages(stages.vertex(),stages.geometry(),stages.tessControl(),stages.tessEval(),s);
    }
    private static String visibility(String source,String name,String type,String value,boolean required){
        String code=ShaderPackInventory.code(source);
        var m=Pattern.compile("\\b"+type+"\\s+"+name+"\\s*\\([^;{}]*\\)\\s*\\{").matcher(code);
        if(!m.find()){
            if(required||Pattern.compile("\\b"+name+"\\s*\\(").matcher(code).find())throw new IllegalArgumentException("effective "+name+" contract missing");
            return source;
        }
        int open=m.end()-1,end=open+1,depth=1;
        for(;end<code.length()&&depth>0;end++){char c=code.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        if(depth!=0||m.find())throw new IllegalArgumentException("ambiguous "+name+" definition");
        String body=code.substring(open,end);
        // SHADOW disabled / Nether variants contain baked skylight, not a scene shadow lookup.
        if(!name.equals("GetCloudShadow")&&!Pattern.compile("Sample\\w*Shadow|shadowtex[01]").matcher(body).find())return source;
        if(Pattern.compile("\\b(?:imageStore|imageAtomic\\w*|atomic\\w*|discard|barrier)\\b").matcher(body).find())throw new IllegalArgumentException(name+" has observable effects");
        return source.substring(0,open)+"{ return "+value+"; }"+source.substring(end);
    }
}
