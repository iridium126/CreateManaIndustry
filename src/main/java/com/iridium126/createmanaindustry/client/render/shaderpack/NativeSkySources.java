package com.iridium126.createmanaindustry.client.render.shaderpack;

import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Isolated native sky variants keep the pack's math but cannot recurse into or publish the source rift. */
public final class NativeSkySources {
    private NativeSkySources() {}
    public static ProgramSource targetClouds(ProgramSource source,String family){
        var isolated=isolate(source,family);
        return com.iridium126.createmanaindustry.mixin.iris.NativeSkySourceAccess.cmi$copy(isolated.getName(),isolated.getVertexSource().orElse(null),
                isolated.getGeometrySource().orElse(null),isolated.getTessControlSource().orElse(null),isolated.getTessEvalSource().orElse(null),
                targetCloudFragment(isolated.getFragmentSource().orElseThrow()),isolated.getDirectives(),isolated.getParent());
    }
    /** Apply distance ownership after the pack's normal cloud shader without changing its outputs. */
    public static String targetCloudFragment(String source){
        String declarations="\nuniform mat4 CmiTargetInverseViewProjection;\n";
        if(!Pattern.compile("uniform\\s+sampler2D\\s+CmiJuliaHit\\s*;").matcher(ShaderPackInventory.code(source)).find())declarations+="uniform sampler2D CmiJuliaHit;\n";
        var main=ShaderPatchPlan.unique(ShaderPackInventory.code(source),"void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
        // Keep #extension directives before all added declarations on strict GLSL drivers.
        String next=source.substring(0,main.start())+declarations+"void cmiTargetCloudStock()"+source.substring(main.end());
        return next+"\nvoid main(){\nvec2 uv=gl_FragCoord.xy/vec2(textureSize(CmiJuliaHit,0)); float hit=texture(CmiJuliaHit,uv).r;\nvec4 p=CmiTargetInverseViewProjection*vec4(uv*2.0-1.0,gl_FragCoord.z*2.0-1.0,1.0);\nif(hit<=0.0||dot(p.xyz,p.xyz)<=hit*hit*p.w*p.w)discard;\ncmiTargetCloudStock();\n}\n";
    }
    public static ProgramSource isolate(ProgramSource source,String family) {
        return com.iridium126.createmanaindustry.mixin.iris.NativeSkySourceAccess.cmi$copy("cmi_target_"+source.getName(),source.getVertexSource().map(NativeSkySources::readOnly).orElse(null),
                source.getGeometrySource().map(NativeSkySources::readOnly).orElse(null),source.getTessControlSource().map(NativeSkySources::readOnly).orElse(null),source.getTessEvalSource().map(NativeSkySources::readOnly).orElse(null),
                isolate(source.getFragmentSource().orElseThrow(),family),source.getDirectives(),source.getParent());
    }
    public static String isolate(String source,String family) {
        String next=readOnly(source);
        next=body(next,"cmiStormCloudActive","return false;");
        next=body(next,"cmiRayActive","return false;");
        next=body(next,"cmiCloudVisible","return true;");
        next=body(next,"cmiCloudLimit","return maximum;");
        next=body(next,"cmiNativeSky","return original;");
        // Camera effects are applied once by the source scene after the portal composition.
        next=next.replaceAll("\\buniform\\s+(float|int)\\s+(blindness|nightVision|darknessFactor|darknessLightFactor)\\s*;","const $1 $2 = 0;");
        // Deliberately unbound uniform defaults to zero. A constant zero would make otherwise guarded
        // native denseFogColor[isEyeInWater-2] expressions fail some drivers' static bounds checks.
        next=next.replaceAll("\\bisEyeInWater\\b","cmiTargetEyeInWater");
        if(family.equals("bliss")&&Pattern.compile("color\\s*\\*=\\s*vl\\.a\\s*\\*\\s*cloudAlpha\\s*;").matcher(ShaderPackInventory.code(next)).find()) {
            var anchor=ShaderPatchPlan.unique(ShaderPackInventory.code(next),"color\\s*\\+=\\s*vl\\.rgb\\s*;");
            next=next.substring(0,anchor.end())+"\ngl_FragData[0]=vec4(1.0); gl_FragData[1]=vec4(clamp(color,0.0,68000.0),1.0); return;\n"+next.substring(anchor.end());
        }
        if(family.equals("iteration"))next=next.replaceAll("\\bLinearToCurve\\s*\\(\\s*finalComposite\\s*\\)","finalComposite");
        return next;
    }
    private static String body(String source,String name,String body) {
        String code=ShaderPackInventory.code(source);
        var matcher=Pattern.compile("\\b(?:bool|float|vec3)\\s+"+name+"\\s*\\([^)]*\\)\\s*\\{").matcher(code);
        if(!matcher.find())return source;
        int start=matcher.end(),end=balanced(code,start-1,'{','}');
        ShaderPatchPlan.require(!matcher.find(),"duplicate target sky helper "+name);
        return source.substring(0,start)+body+source.substring(end);
    }
    public static String readOnly(String source) {
        String code=ShaderPackInventory.code(source);
        // Selected native sky programs may read the pack's resources but must never publish into them.
        ShaderPatchPlan.require(!Pattern.compile("\\b(?:imageAtomic\\w*|atomicCounter\\w*|buffer)\\b").matcher(code).find(),"target sky has unsupported shared storage writes");
        var matcher=Pattern.compile("\\bimageStore\\s*\\(").matcher(code);var edits=new ArrayList<int[]>();
        while(matcher.find())edits.add(new int[]{matcher.start(),balanced(code,matcher.end()-1,'(',')')+1});
        var next=new StringBuilder(source);for(int i=edits.size()-1;i>=0;i--)next.delete(edits.get(i)[0],edits.get(i)[1]);
        return next.toString();
    }
    private static int balanced(String code,int open,char left,char right) {
        int depth=0;
        for(int i=open;i<code.length();i++) {
            char c=code.charAt(i);
            if(c==left)depth++;
            if(c==right&&--depth==0)return i;
        }
        throw new IllegalArgumentException("Unbalanced target shader source");
    }
}
