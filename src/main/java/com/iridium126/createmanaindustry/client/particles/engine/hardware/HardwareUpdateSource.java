package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import java.util.*;
import java.util.regex.*;

/** Opt-in unified update coroutine. Generated from the committed, include-expanded
 * source; never changes the production program or a particle's record layout. */
public final class HardwareUpdateSource {
    private HardwareUpdateSource(){}
    public record Field(String type,String name,int offset){}
    public record Variant(String source,int strideWords,List<Field> fields){}
    private record Declaration(int start,int end,String type,List<String> names,String assignments){}
    private record Hoisted(String declarations,String body,List<Field> fields,int words){}
    private static final String MAIN="void main() {",STORM="    if (!corpse && stormA.x > 0.5) {\n        // ----- typhoon: world-frame servo onto the circulating arm home point\n",QUERY="        vec3 sepW = vec3(0.0);",END="        sepW *= STORM_W_SEP;";
    private static final Pattern TYPE=Pattern.compile("\\b(bool|uint|int|float|[iu]?vec[234])\\s+([A-Za-z_]\\w*)");
    private static final Map<String,String> GLOBALS=new LinkedHashMap<>();
    static {
        globals("bool","cmiStormVoxel cmiStormMissing cmiStormEmbedded cmiDomeNavigationBlocked cmiDomeNavigationUnknown cmiDomeProbeUnknown cmiDomeNavigationClipped cmiNavigationPathBlocked cmiNavigationPathUnknown cmiNavigationApplied cmiNavigationKnownBlocked cmiNavigationCoverageUnknown cmiColumnExit cmiColumnRestored cmiColumnRecoveryFrame cmiColumnBloomStarted");
        globals("uint","cmiDomePauseAssignment cmiDomeCachedAssignment cmiNavigationCurrentMember");
        globals("ivec3","cmiStormCached");globals("int","cmiStormCachedPage");
        globals("float","cmiDomePauseCached cmiDomeCachedTime");
        globals("vec3","cmiDomeCachedPoint cmiDomeCachedVelocity cmiNavigationReference cmiColumnExitVelocity cmiColumnBloomPoint");
    }
    private static void globals(String type,String names){for(String name:names.split(" "))GLOBALS.put(name,type);}
    private static int width(String type){char c=type.charAt(type.length()-1);return c>='2'&&c<='4'?c-'0':1;}
    private static int unique(String source,String marker){int i=source.indexOf(marker);if(i<0||source.indexOf(marker,i+1)>=0)throw new IllegalArgumentException("R7 source boundary changed: "+marker);return i;}
    /** Replaces comments by spaces so syntax offsets continue to refer to the original. */
    private static String mask(String s){var b=new StringBuilder(s);boolean line=false,block=false;for(int i=0;i<s.length();i++){char c=s.charAt(i),next=i+1<s.length()?s.charAt(i+1):0;if(!line&&!block&&c=='/'&&next=='/'){line=true;b.setCharAt(i,' ');}else if(!line&&!block&&c=='/'&&next=='*'){block=true;b.setCharAt(i,' ');}else if(block&&c=='*'&&next=='/'){b.setCharAt(i,' ');b.setCharAt(++i,' ');block=false;}else if(line&&c=='\n')line=false;else if(line||block)b.setCharAt(i,c=='\n'?'\n':' ');}return b.toString();}
    private static Hoisted hoist(String source,int offset){
        String masked=mask(source);int[] braces=new int[masked.length()+1],parens=new int[masked.length()+1];
        for(int i=0;i<masked.length();i++){char c=masked.charAt(i);braces[i+1]=braces[i]+(c=='{'?1:c=='}'?-1:0);parens[i+1]=parens[i]+(c=='('?1:c==')'?-1:0);}
        var found=new ArrayList<Declaration>();var fields=new ArrayList<Field>();var namesSeen=new HashSet<String>();Matcher matcher=TYPE.matcher(masked);int previous=-1;
        while(matcher.find()){
            int start=matcher.start();if(start<previous||braces[start]!=0||parens[start]!=0)continue;
            int end=masked.indexOf(';',matcher.end());if(end<0)throw new IllegalArgumentException("Unterminated local declaration");
            String type=matcher.group(1),tail=source.substring(matcher.start(2),end),clean=masked.substring(matcher.start(2),end);
            var names=new ArrayList<String>();var assignments=new StringBuilder();int at=0,depth=0;
            for(int i=0;i<=clean.length();i++){char c=i<clean.length()?clean.charAt(i):',';if(c=='(')depth++;else if(c==')')depth--;if(c!=','||depth!=0)continue;String item=tail.substring(at,i),plain=clean.substring(at,i).trim();Matcher m=Pattern.compile("^([A-Za-z_]\\w*)\\s*(=|$)").matcher(plain);if(!m.find())throw new IllegalArgumentException("Unsupported continuation declaration: "+plain);String name=m.group(1);if(!namesSeen.add(name))throw new IllegalArgumentException("Duplicate continuation local: "+name);names.add(name);fields.add(new Field(type,name,offset));offset+=width(type);if(m.group(2).equals("="))assignments.append(item).append(';');at=i+1;}
            found.add(new Declaration(start,end+1,type,names,assignments.toString()));previous=end+1;
        }
        var declarations=new StringBuilder();for(var d:found)declarations.append(d.type).append(' ').append(String.join(",",d.names)).append(";\n");var body=new StringBuilder(source);for(int i=found.size()-1;i>=0;i--){var d=found.get(i);body.replace(d.start,d.end,d.assignments);}return new Hoisted(declarations.toString(),body.toString(),fields,offset);
    }
    private static String store(Field f){var s=new StringBuilder();for(int k=0;k<width(f.type);k++){String name=f.name+(width(f.type)>1?"["+k+"]":""),expr=f.type.equals("bool")?"("+name+"?1u:0u)":f.type.equals("float")||f.type.startsWith("vec")?"floatBitsToUint("+name+")":"uint("+name+")";s.append("r7State.words[r7Base+").append(f.offset+k).append("u]=").append(expr).append(';');}return s.toString();}
    private static String load(Field f){var s=new StringBuilder();for(int k=0;k<width(f.type);k++){String word="r7State.words[r7Base+"+(f.offset+k)+"u]",expr=f.type.equals("bool")?"("+word+"!=0u)":f.type.equals("float")||f.type.startsWith("vec")?"uintBitsToFloat("+word+")":f.type.equals("int")||f.type.startsWith("ivec")?"int("+word+")":word;s.append(f.name).append(width(f.type)>1?"["+k+"]":"").append('=').append(expr).append(';');}return s.toString();}
    public static Variant build(String source){
        source=source.replace("\r\n","\n");
        int main=unique(source,MAIN)+MAIN.length(),storm=unique(source,STORM),boundary=unique(source,QUERY),end=unique(source,END);
        if(!(main<storm&&storm<boundary&&boundary<end))throw new IllegalArgumentException("R7 query source ordering changed");
        String globals=source.substring(0,main-MAIN.length());
        var a=hoist(source.substring(main,storm),12);var b=hoist(source.substring(storm+STORM.length(),boundary),a.words);var fields=new ArrayList<Field>();fields.addAll(a.fields);fields.addAll(b.fields);int words=b.words;
        String maskedGlobals=mask(globals);for(var entry:GLOBALS.entrySet())if(Pattern.compile("\\b"+entry.getKey()+"\\s*=").matcher(maskedGlobals).find()){fields.add(new Field(entry.getValue(),entry.getKey(),words));words+=width(entry.getValue());}
        int stride=(words+3)&~3;var declarations="\nlayout(r32ui,binding=7)coherent uniform uimageBuffer r7Continuation;\nlayout(r32ui,binding=6)readonly uniform uimageBuffer r7QueryResult;\nuniform bool uR7ResultsEnabled;\nuint r7Result(uint i){return imageLoad(r7QueryResult,int(i)).x;}\nuint r7WordCount(){int n=imageSize(r7Continuation);return n>4?uint(n-4):0u;}\nuint r7Load(uint i){return imageLoad(r7Continuation,int(i)+4).x;}\nvoid r7Store(uint i,uint value){imageStore(r7Continuation,int(i)+4,uvec4(value,0,0,0));}\nuniform uint uR7Stage;uniform uvec2 uR7Generation,uR7ResourceEpoch;\n";
        StringBuilder restoreA=new StringBuilder(),restoreB=new StringBuilder(),save=new StringBuilder();Set<String> blockNames=new HashSet<>();b.fields.forEach(f->blockNames.add(f.name));
        for(var f:fields){(blockNames.contains(f.name)?restoreB:restoreA).append(load(f));save.append(store(f));}
        String setup="\nuint r7Lane=gl_GlobalInvocationID.x,r7Base=r7Lane*"+stride+"u;bool r7Fits=r7Base+"+stride+"u<=uint(r7State.words.length()),r7Resume=uR7Stage==2u;\n"
            +"if(uR7Stage==1u&&uModelPass==2u&&r7Fits)r7State.words[r7Base+4u]=0u;\n"
            +"if(r7Resume){if(!r7Fits||r7State.words[r7Base+4u]!=0x52374331u)return;if(r7State.words[r7Base]!=uR7Generation.x||r7State.words[r7Base+1u]!=uR7Generation.y){atomicOr(r7State.status.w,2u);return;}uint saved=r7State.words[r7Base+2u];if(saved>=min(prevCounter.live,uCapacity)||floatBitsToUint(readBuf.data[saved*4u].w)!=r7State.words[r7Base+3u]){atomicOr(r7State.status.w,2u);return;}}\n";
        String spill="\nif(uR7Stage==1u&&uModelPass==2u&&!domeMember){if(r7Fits){"+save
            +"r7State.words[r7Base]=uR7Generation.x;r7State.words[r7Base+1u]=uR7Generation.y;r7State.words[r7Base+2u]=idx;r7State.words[r7Base+3u]=floatBitsToUint(readBuf.data[idx*4u].w);r7State.words[r7Base+8u]=floatBitsToUint(pos.x);r7State.words[r7Base+9u]=floatBitsToUint(pos.y);r7State.words[r7Base+10u]=floatBitsToUint(pos.z);r7State.words[r7Base+11u]=floatBitsToUint(separationRadius);r7State.words[r7Base+5u]=r7Lane;r7State.words[r7Base+4u]=0x52374331u;return;}atomicOr(r7State.status.w,1u);}\n";
        String narrow="if(j==idx)continue;uint otherHb=floatBitsToUint(readBuf.data[j*4u+3u].w)*VEC4_PER_EMITTER;if(otherHb+18u>=uint(emitters.u.length())||emitters.u[otherHb+18u].x<.5)continue;vec3 op=readBuf.data[j*4u].xyz;vec3 d=pos-op;float d2=dot(d,d);if(d2>=separationRadius*separationRadius)continue;if(d2<=1e-8){d=cmiStormSeparationDelta(d,cmiStarMember(p0.w),cmiStarMember(readBuf.data[j*4u].w));d2=dot(d,d);}if(d2<separationRadius*separationRadius&&d2>1e-8){float dl=sqrt(d2);sepW+=d*((separationRadius-dl)/(dl*separationRadius));}";
        // Result envelope (16 uints): magic, generation lo/hi, phase, query count,
        // The production storm roster contains MODEL members. A complete pool
        // reference must apply that eligibility rule too, rather than turning
        // an unrelated generic emitter into a new separation obstacle.
        narrow=narrow.replace("emitters.u[otherHb+18u].x<.5","emitters.u[otherHb+18u].x<.5||emitters.u[otherHb+7u].x<1.5||emitters.u[otherHb+7u].x>=2.5");
        // input live count, ids offset in uints, capacity, error, complete.
        // CSR starts at uint 16 and retains the native 64-bit offsets/header ABI.
        String ranges="bool r7External=uR7ResultsEnabled&&r7Resume&&imageSize(r7QueryResult)>=22;uint r7From=0u,r7To=0u,r7Ids=0u,r7Row=r7Resume?r7Load(r7Base+5u):r7Lane;\n"
            +"if(r7External){uint size=uint(imageSize(r7QueryResult));r7External=r7Result(0u)==0x52375131u&&r7Result(1u)==uR7Generation.x&&r7Result(2u)==uR7Generation.y&&r7Result(3u)==1u&&r7Row<r7Result(4u)&&r7Result(5u)==min(prevCounter.live,uCapacity)&&r7Result(8u)==0u&&r7Result(9u)==1u&&r7Result(10u)==uR7ResourceEpoch.x&&r7Result(11u)==uR7ResourceEpoch.y&&r7Result(17u)==0u&&r7Result(18u)==0u&&r7Result(19u)==0u;\n"
            +"if(r7External){uint q=r7Result(4u);r7External=q<=(size-22u)/2u;if(r7External){r7From=r7Result(20u+r7Row*2u);r7To=r7Result(22u+r7Row*2u);r7Ids=r7Result(6u);r7External=r7Result(21u+r7Row*2u)==0u&&r7Result(23u+r7Row*2u)==0u&&r7From<=r7To&&r7To<=r7Result(16u)&&r7To<=r7Result(7u)&&r7Ids<=size&&r7To<=size-r7Ids&&r7Ids>=22u+q*2u;}}}\n"
            +"if(r7External)for(uint k=r7From;k<r7To;k++)if(r7Result(r7Ids+k)>=min(prevCounter.live,uCapacity)){r7External=false;break;}if(uR7ResultsEnabled&&r7Resume&&!r7External)imageAtomicOr(r7Continuation,2,1u);\n";
        String reference="\nvec3 sepW=vec3(0);"+ranges+"if(!domeMember){if(r7External){for(uint k=r7From;k<r7To;k++){uint j=r7Result(r7Ids+k);if(j>=min(prevCounter.live,uCapacity))continue;"+narrow+"}}else for(uint j=0u;j<min(prevCounter.live,uCapacity);j++){"+narrow+"}}\n";
        String result=globals+declarations+MAIN+setup+a.declarations+"\nif(r7Resume){"+restoreA+"}else{"+a.body+"}\n"+STORM+b.declarations+"\nif(r7Resume){"+restoreB+"}else{"+b.body+"}\n"+spill+reference+source.substring(end);
        // update.comp already uses the device's sixteen compute SSBO blocks.
        // The continuation is an optional integer image buffer, like diagnostic traces.
        result=result.replace("uint(r7State.words.length())","r7WordCount()").replace("atomicOr(r7State.status.w,","imageAtomicOr(r7Continuation,3,");
        result=Pattern.compile("r7State\\.words\\[([^\\]]+)\\]\\s*=\\s*([^;]+);").matcher(result).replaceAll("r7Store($1,$2);");
        result=Pattern.compile("r7State\\.words\\[([^\\]]+)\\]").matcher(result).replaceAll("r7Load($1)");
        return new Variant(result,stride,List.copyOf(fields));
    }
}
