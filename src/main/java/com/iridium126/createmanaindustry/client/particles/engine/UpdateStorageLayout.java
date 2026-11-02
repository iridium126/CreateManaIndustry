package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.regex.Pattern;

/** Generated update-domain experiment ABI; all variants use the same 0..15 slot assignment. */
public record UpdateStorageLayout(boolean memberStorage,boolean allocatorStorage) {
    public static final int FRAME=5,MEMBERS=14,ALLOCATOR=15;
    private static final String[] MACROS={"BIND_POOL_READ","BIND_POOL_WRITE","BIND_COUNTER","BIND_EMITTER","BIND_PREV_COUNTER","BIND_GRID","BIND_CORRECTION","BIND_MEMBERMAP","BIND_IDENTITY","BIND_POPULATION_READ"};
    private static final int[] SLOTS={0,1,2,3,4,6,7,8,9,13};
    public int activeBlocks(){return 14+(memberStorage?1:0)+(allocatorStorage?1:0);}
    public int[] sourceBindings(){return new int[]{0,1,3,5,14,-1,13,19,21,ParticleBuffers.IDENTITY_BB,27,28,26,24,-1,-1};}
    public String source(String input){
        String s=remove(input,"BIND_BAKEMETA","bakeMeta");s=remove(s,"BIND_PLAYERS","players");s=remove(s,"BIND_DAMAGE","damageQ");
        s=s.replace("bakeMeta.m.length()","int(uFrameCounts.x)").replace("players.pl.length()","int(uFrameCounts.y)").replace("damageQ.entries.length()","int(uFrameCounts.z)");
        s=array(s,"bakeMeta.m","cmiFrameBake");s=array(s,"players.pl","cmiFramePlayer");s=array(s,"damageQ.entries","cmiFrameDamage");s=s.replace("damageQ.header","floatBitsToUint(frameInputs.rows[uFrameOffsets.z])");
        if(memberStorage){
            String declaration="layout(r32ui,binding=2)coherent uniform uimageBuffer cmi_ModelMembersWrite;";
            if(!s.contains(declaration))throw new IllegalArgumentException("Candidate member ABI changed");
            s=s.replace(declaration,"layout(std430,binding=14)buffer CandidateMembers {uint words[];} candidateMembers;");
            s=s.replace("imageStore(cmi_ModelMembersWrite,","cmiMemberStore(");
            s=s.replace("uniform bool uModelMembersEnabled;","void cmiMemberStore(int i,uvec4 v){candidateMembers.words[i]=v.x;}\nuniform bool uModelMembersEnabled;");
        }
        if(allocatorStorage){
            String declaration="layout(r32ui,binding=3)coherent uniform uimageBuffer cmi_ModelAllocator;";
            if(!s.contains(declaration))throw new IllegalArgumentException("Model allocator ABI changed");
            s=s.replace(declaration,"layout(std430,binding=15)coherent buffer ModelAllocator {uint words[];} modelAllocator;");
            s=s.replace("imageAtomicOr(cmi_ModelAllocator,","cmiAllocatorOr(").replace("imageAtomicAnd(cmi_ModelAllocator,","cmiAllocatorAnd(");
            s=s.replaceAll("(cmiAllocator(?:Or|And)\\([^;]*;\\s*)memoryBarrierImage\\(\\)","$1memoryBarrierBuffer()");
            s=s.replace("uniform bool uModelMembersEnabled;","uint cmiAllocatorOr(int i,uint v){return atomicOr(modelAllocator.words[i],v);}\nuint cmiAllocatorAnd(int i,uint v){return atomicAnd(modelAllocator.words[i],v);}\nuniform bool uModelMembersEnabled;");
        }
        s=s.replace("binding=27","binding=10").replace("binding=28","binding=11").replace("binding=26","binding=12");
        var header=new StringBuilder();for(int i=0;i<MACROS.length;i++)header.append("#undef ").append(MACROS[i]).append("\n#define ").append(MACROS[i]).append(' ').append(SLOTS[i]).append('\n');
        return header+"\nlayout(std430,binding=5)readonly buffer FrameInputs{vec4 rows[];}frameInputs;uniform uvec3 uFrameOffsets,uFrameCounts;\n"
                +"vec4 cmiFrameBake(uint i){return frameInputs.rows[uFrameOffsets.x+i];}vec4 cmiFrameBake(int i){return cmiFrameBake(uint(i));}\n"
                +"vec4 cmiFramePlayer(uint i){return frameInputs.rows[uFrameOffsets.y+i];}vec4 cmiFramePlayer(int i){return cmiFramePlayer(uint(i));}\n"
                +"float cmiFrameDamage(uint i){return frameInputs.rows[uFrameOffsets.z+1u+(i>>2u)][i&3u];}float cmiFrameDamage(int i){return cmiFrameDamage(uint(i));}\n"+s;
    }
    private static String remove(String source,String binding,String alias){
        var m=Pattern.compile("layout\\(std430,\\s*binding\\s*=\\s*"+binding+"\\)\\s*readonly buffer.*?}\\s*"+alias+"\\s*;",Pattern.DOTALL).matcher(source);
        if(!m.find())throw new IllegalArgumentException("Missing frame input "+alias);return m.replaceFirst("");
    }
    private static String array(String s,String alias,String function){
        String prefix=alias+"[";int start=0;var out=new StringBuilder();
        for(;;){int at=s.indexOf(prefix,start);if(at<0)return out.append(s,start,s.length()).toString();out.append(s,start,at).append(function).append('(');
            int end=at+prefix.length(),depth=1;for(;end<s.length()&&depth>0;end++){char c=s.charAt(end);if(c=='[')depth++;if(c==']')depth--;}
            if(depth!=0)throw new IllegalArgumentException(alias);out.append(s,at+prefix.length(),end-1).append(')');start=end;}
    }
}
