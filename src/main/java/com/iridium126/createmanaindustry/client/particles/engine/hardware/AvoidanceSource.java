package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Generated steering preference only; production contact and settlement stay current. */
public final class AvoidanceSource {
    private AvoidanceSource(){}
    /** Reject unresolved approximate coverage before the production ready/retire audit. */
    public static String domeAudit(String source){
        source=source.replace("#define CMI_DOME_PASS 0","#define CMI_DOME_PASS 5");
        String marker="  if(phase==1u){";
        if(source.indexOf(marker)<0)throw new IllegalArgumentException("Dome audit boundary changed");
        source=source.replace("void main(){","layout(rgba32ui,binding=3) readonly uniform uimageBuffer avoidanceMetadata;\nvoid main(){");
        return source.replace(marker,marker+"\n   uvec4 coverage=imageLoad(avoidanceMetadata,int(member));if(coverage.x==0u||(coverage.w&14u)!=0u){uint word=cmiStarSlot(assignment)*384u+cmiMajorRank(assignment)/32u,bit=1u<<(cmiMajorRank(assignment)&31u);atomicAnd(CMI_STATE(state,CMI_GATHER_READY+word),~bit);CMI_STATE(state,CMI_GATHER_STREAK+member)=0u;CMI_STATE(state,member*2u+1u)&=~0x40000000u;return;}\n");
    }
    public static String resource(String name){
        String root="/assets/createmanaindustry/shaders/particles/";
        String path=java.nio.file.Path.of(name).normalize().toString().replace('\\','/');
        try(var in=AvoidanceSource.class.getResourceAsStream(root+path)){
            if(in==null)throw new IllegalArgumentException("Missing avoidance source: "+path);
            String source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            var pattern=Pattern.compile("(?m)^#pragma cmi_include (.+)$");var matcher=pattern.matcher(source);var out=new StringBuffer();
            while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(resource(java.nio.file.Path.of(path).getParent().resolve(matcher.group(1).trim()).toString())));
            matcher.appendTail(out);return out.toString();
        }catch(IOException failure){throw new IllegalStateException(failure);}
    }
    public static String update(String source){
        source=source.replace("\r\n","\n");String start="        vec3 sepW = vec3(0.0);",end="        sepW *= STORM_W_SEP;";
        int a=source.indexOf(start),b=source.indexOf(end,a);if(a<0||b<0||source.indexOf(start,a+1)>=0)throw new IllegalArgumentException("Avoidance steering boundary changed");
        String declaration="""
            layout(rgba32ui,binding=6) readonly uniform uimageBuffer avoidanceHints;
            layout(r32ui,binding=4) uniform uimageBuffer avoidanceMotionCaps;
            uint avoidanceWord(uint word){return imageLoad(avoidanceHints,int(word/4u))[word%4u];}
            uniform bool uAvoidanceEnabled;uniform uvec2 uAvoidanceNonce,uAvoidanceEpoch,uAvoidanceGeneration,uAvoidanceScene;
            vec3 avoidancePreference(uint member,vec3 position,vec3 velocity,uint token,uint emitter,uint attack,uint phase){
             if(!uAvoidanceEnabled||member>=131072u)return vec3(0);
             if(avoidanceHints.header[0]!=0x4131304fu||avoidanceHints.header[1]!=10u||avoidanceHints.header[2]!=uAvoidanceNonce.x||avoidanceHints.header[3]!=uAvoidanceNonce.y||avoidanceHints.header[4]!=uAvoidanceEpoch.x||avoidanceHints.header[5]!=uAvoidanceEpoch.y||avoidanceHints.header[8]!=uAvoidanceGeneration.x||avoidanceHints.header[9]!=uAvoidanceGeneration.y||avoidanceHints.header[10]!=131072u||avoidanceHints.header[11]!=0u||avoidanceHints.header[16]!=uAvoidanceScene.x||avoidanceHints.header[17]!=uAvoidanceScene.y||avoidanceHints.header[18]!=1u)return vec3(0);
             uint at=member*4u;uvec4 identity=floatBitsToUint(avoidanceHints.rows[at+1u]);vec4 old=avoidanceHints.rows[at+2u],oldVelocity=avoidanceHints.rows[at+3u],hint=avoidanceHints.rows[at];
             float elapsed=uTimeSec-uintBitsToFloat(avoidanceHints.header[12]);
             if(identity.x!=member||identity.y!=token||identity.z!=emitter||identity.w!=(1u|(phase<<8u))||floatBitsToUint(old.w)!=attack||elapsed<0.||elapsed>.1||distance(position,old.xyz)>oldVelocity.w*elapsed+.03||any(isnan(hint))||any(isinf(hint)))return vec3(0);
             return hint.xyz*min(6.,length(velocity));
            }
            """;
        String steering="        uint avoidanceMember=cmiStarMember(p0.w);uint avoidancePhase=uStarfallEnabled&&avoidanceMember<131072u?CMI_STATE(starStateWrite,avoidanceMember*2u+1u)&255u:0u;uint avoidanceAttack=uStarfallEnabled&&avoidanceMember<131072u?CMI_STATE(starStateWrite,avoidanceMember*2u):0u;\n"
            +"        vec3 sepW=avoidancePreference(avoidanceMember,pos,vel,floatBitsToUint(p3.z),floatBitsToUint(p3.w),avoidanceAttack,avoidancePhase);\n";
        declaration=declaration.replaceAll("avoidanceHints\\.header\\[([^]]+)\\]","avoidanceWord($1)").replaceAll("avoidanceHints\\.rows\\[([^]]+)\\]","uintBitsToFloat(imageLoad(avoidanceHints,int(8u+$1)))");
        String changed=source.substring(0,a)+steering+source.substring(b);
        String cap="if(!corpse&&stormA.x>.5){uint member=cmiStarMember(p0.w);if(member<131072u){uint at=member*8u;for(uint k=0u;k<3u;k++)imageStore(avoidanceMotionCaps,int(at+k),uvec4(floatBitsToUint(p0[k])));imageStore(avoidanceMotionCaps,int(at+3u),uvec4(floatBitsToUint(cmiStormFrameCap)));imageStore(avoidanceMotionCaps,int(at+4u),uvec4(floatBitsToUint(p3.z)));imageStore(avoidanceMotionCaps,int(at+7u),uvec4(1));}}\n";
        int reserve=changed.lastIndexOf("    uint slot = cmiReserveParticle();");
        if(reserve<changed.indexOf("void main() {"))throw new IllegalArgumentException("Current update publication boundary changed");
        changed=changed.substring(0,reserve)+cap+changed.substring(reserve);
        int main=changed.indexOf("void main() {");return changed.substring(0,main)+declaration+changed.substring(main);
    }
}
