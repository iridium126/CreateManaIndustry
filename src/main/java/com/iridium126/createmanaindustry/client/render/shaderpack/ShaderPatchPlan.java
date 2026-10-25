package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;
import java.util.regex.*;

/** A whole-source candidate. Failed preflight exposes no source edits and no material-map edits. */
public final class ShaderPatchPlan {
    private final String family,reason;
    private final Map<String,String> originals,edits;
    private final int blockId, donorId, replacementId, mistBuffer,glowBuffer;
    private ShaderPatchPlan(String family,String reason,Map<String,String> originals,Map<String,String> edits,
            int blockId,int donorId,int replacementId,int mistBuffer,int glowBuffer) {
        this.family=family;this.reason=reason;this.originals=Map.copyOf(originals);this.edits=Map.copyOf(edits);
        this.blockId=blockId;this.donorId=donorId;this.replacementId=replacementId;this.mistBuffer=mistBuffer;this.glowBuffer=glowBuffer;
    }
    public static ShaderPatchPlan disabled(String reason) { return new ShaderPatchPlan("unclassified",reason,Map.of(),Map.of(),-1,-1,-1,-1,-1); }
    public String family(){return family;} public String reason(){return reason;}
    public Map<String,String> edits(){return edits;} public int blockId(){return blockId;}
    public int donorId(){return donorId;} public int replacementId(){return replacementId;}
    public boolean enabled(){return blockId>=0&&!edits.isEmpty();}
    public boolean rawLayers(){return mistBuffer>=0;} public int mistBuffer(){return mistBuffer;}public int glowBuffer(){return glowBuffer;}
    public String transform(String path,String original) {
        String p=ShaderPackInventory.normalize(path);
        if(!edits.containsKey(p))return original;
        if(!Objects.equals(originals.get(p),original))throw new IllegalStateException("Shader source changed during candidate load: "+p);
        return edits.get(p);
    }
    public static ShaderPatchPlan prepare(ShaderPackInventory inv) {
        var candidates=new ArrayList<String>();
        if(!inv.source("include/lighting/lpv/light_colors.glsl").isEmpty())candidates.add("lpv-lut");
        if(!inv.source("lib/voxelization/lightVoxelization.glsl").isEmpty())candidates.add("voxel-id");
        if(inv.containsCode("dimensions/setup.csh","imageStore\\s*\\(\\s*imgBlockData"))candidates.add("block-data");
        if(!inv.source("Lib/Settings.glsl").isEmpty()&&!inv.source("Lib/GbufferData.glsl").isEmpty())candidates.add("curve-encoded");
        if(candidates.size()!=1)return disabled(candidates.isEmpty()?"no proven colored-light contract":"ambiguous source contracts");
        String family=candidates.getFirst();
        var edits=new LinkedHashMap<String,String>();
        int block=-1,donor=-1,replacement=-1,mist=-1,glow=-1;
        try {
            if(family.equals("lpv-lut")) {
                String path="include/lighting/lpv/light_colors.glsl", source=inv.source(path),code=ShaderPackInventory.code(source);
                Matcher head=unique(code,"const\\s+vec3\\s*\\[\\s*(\\d+)\\s*]\\s+light_color\\s*=\\s*vec3\\s*\\[\\s*\\1\\s*]\\s*\\(");
                int n=Integer.parseInt(head.group(1)), start=head.end(), end=closing(code,start-1,'(',')');
                var entries=entries(code,start,end);
                require(entries.size()==n,"LUT declaration and entries disagree");
                String floodPath=Objects.requireNonNull(inv.uniqueSuffix("lighting/lpv/floodfill.glsl"));
                Matcher range=unique(ShaderPackInventory.code(inv.source(floodPath)),"\\bbool\\s+is_emitter\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+(\\d+)u?\\s*<=\\s*block_id\\s*&&\\s*block_id\\s*<\\s*(\\d+)u?\\s*;");
                int base=Integer.parseInt(range.group(1)),limit=Integer.parseInt(range.group(2));
                require(limit-base==n,"emitter range and LUT disagree");
                String props=inv.source("block.properties").replaceAll("\\\\\\s*\\R", " ");
                donor=propertyId(props,"candle:lit=true");replacement=propertyId(props,"torch");
                Matcher encoding=unique(ShaderPackInventory.code(inv.source("include/vertex/utility.glsl")),"mc_Entity\\.x\\s*-\\s*(\\d+)(?:\\.0)?");
                int offset=Integer.parseInt(encoding.group(1));
                int d=donor-offset-base,r=replacement-offset-base;
                require(d>=0&&d<n&&r>=0&&r<n&&d!=r,"material encoding not proven");
                require(canonical(code.substring(entries.get(d)[0],entries.get(d)[1])).equals(canonical(code.substring(entries.get(r)[0],entries.get(r)[1]))),"donor and replacement are not exactly equal colors");
                String setup=ShaderPackInventory.code(inv.source("program/lpv/setup.csh"));
                require(setup.matches("(?s).*local_size_x\\s*=\\s*"+n+"\\s*\\).*"),"dispatch size differs from LUT");
                require(setup.matches("(?s).*light_color\\s*\\[\\s*clamp\\s*\\(\\s*index\\s*,\\s*0u?\\s*,\\s*"+(n-1)+"u?\\s*\\).*"),"LUT clamp differs");
                require(inv.source("shaders.properties").matches("(?s).*image\\.light_data_img\\s*=[^\\r\\n]*\\s"+n+"\\s+2(?:\\s|$).*"),"light image size differs");
                int[] e=entries.get(d); edits.put(path,source.substring(0,e[0])+"vec3(1.00, 0.35, 0.55) * 8.0"+source.substring(e[1]));block=donor;
            } else if(family.equals("voxel-id")) {
                String map="lib/voxelization/lightVoxelization.glsl",color=Objects.requireNonNull(inv.uniqueSuffix("blocklightColors.glsl"));
                block=free(inv.materialIds(),11000,12000,4);
                var used=new HashSet<Integer>();Matcher m=Pattern.compile("\\bmat\\s*==\\s*(\\d+)").matcher(ShaderPackInventory.code(inv.source(color)));
                while(m.find())used.add(Integer.parseInt(m.group(1)));
                int mat=free(used,98,256,1);
                edits.put(map,insertFunction(inv.source(map),"GetVoxelIDs","return\\s+1\\s*;", "if (mat == "+block+") return "+mat+"; // CMI\n"));
                edits.put(color,insert(inv.source(color),"return\\s+vec4\\s*\\(\\s*blocklightCol\\s*\\*\\s*20(?:\\.0)?\\s*,\\s*0(?:\\.0)?\\s*\\)\\s*;","if (mat == "+mat+") return vec4(vec3(1.00, 0.35, 0.55) * 4.5, 0.0); // CMI\n"));
            } else if(family.equals("block-data")) {
                String path="dimensions/setup.csh", source=inv.source(path);
                Matcher bound=unique(ShaderPackInventory.code(source),"if\\s*\\(\\s*blockId\\s*>=\\s*(\\d+)\\s*\\)\\s*return\\s*;");
                block=free(inv.materialIds(),600,Math.min(1000,Integer.parseInt(bound.group(1))),1);
                edits.put(path,insert(source,"if\\s*\\(\\s*lightRange\\s*>\\s*0(?:\\.0)?\\s*\\)\\s*lightRange\\s*\\+=\\s*1(?:\\.0)?\\s*;",
                        "if (blockId == "+block+") { lightColor = vec3(1.00, 0.35, 0.55); lightRange = 15.0; } // CMI\n"));
            } else {
                block=free(inv.materialIds(),7110,7200,1);
                String settings=inv.source("Lib/Settings.glsl");var used=new HashSet<Integer>();
                Matcher m=Pattern.compile("#define\\s+MATID_\\w+\\s+(\\d+)").matcher(ShaderPackInventory.code(settings));while(m.find())used.add(Integer.parseInt(m.group(1)));
                int mat=free(used,29,40,1);
                var occupied=inv.colorBuffers();int a=-1,b=-1;
                try{a=free(occupied,9,32,1);var more=new HashSet<>(occupied);more.add(a);b=free(more,9,32,1);}catch(IllegalArgumentException unavailable){a=b=-1;}
                var adapter=new CurveEncodedSourcePatch(block,mat,a,b);
                for(String suffix:List.of("Settings.glsl","Terrain_VS.glsl","GbufferData.glsl","Blocklight.glsl")) {
                    String path=suffix.equals("Terrain_VS.glsl")?"Lib/Programs/Gbuffers/Terrain_VS.glsl":Objects.requireNonNull(inv.uniqueSuffix(suffix));
                    String changed=adapter.transformShader(suffix,inv.source(path));require(changed!=null,"missing/ambiguous "+path);edits.put(path,changed);
                }
                String composite=inv.source("composite.fsh");String changed=a>=0&&b>=0?adapter.transformShader("composite.fsh",composite):null;
                if(changed!=null) {
                    edits.put("composite.fsh",changed);mist=a;glow=b;
                }
            }
            return new ShaderPatchPlan(family,"complete source/ID preflight",inv.sources(),edits,block,donor,replacement,mist,glow);
        } catch(RuntimeException failure) { return new ShaderPatchPlan(family,"disabled: "+failure.getMessage(),Map.of(),Map.of(),-1,-1,-1,-1,-1); }
    }
    static String canonical(String text){return text.replaceAll("\\s+","");}
    static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
    static Matcher unique(String code,String regex){Matcher m=Pattern.compile(regex,Pattern.DOTALL).matcher(code);require(m.find(),"missing structure: "+regex);int at=m.start();require(!m.find(),"ambiguous structure: "+regex);m.reset();m.find(at);return m;}
    static String insert(String source,String regex,String text){Matcher m=unique(ShaderPackInventory.code(source),regex);return source.substring(0,m.start())+text+source.substring(m.start());}
    static String insertFunction(String source,String name,String regex,String text){String code=ShaderPackInventory.code(source);Matcher f=unique(code,"\\bint\\s+"+name+"\\s*\\([^)]*\\)\\s*\\{");int end=closing(code,f.end()-1,'{','}');return source.substring(0,f.end())+insert(source.substring(f.end(),end),regex,text)+source.substring(end);}
    static int free(Set<Integer> used,int start,int end,int step){for(int id=start;id<end;id+=step)if(!used.contains(id))return id;throw new IllegalArgumentException("no proven free material/attachment slot");}
    static int propertyId(String props,String name){var ids=new HashSet<Integer>();Matcher m=Pattern.compile("(?m)^\\s*block\\.(\\d+)\\s*=([^\\r\\n]*)").matcher(props);while(m.find())for(String token:m.group(2).trim().split("\\s+"))if(token.equals(name)||token.equals("minecraft:"+name))ids.add(Integer.parseInt(m.group(1)));require(ids.size()==1,"ambiguous material: "+name);return ids.iterator().next();}
    static int closing(String code,int at,char open,char close){int depth=0;for(int i=at;i<code.length();i++){char c=code.charAt(i);if(c==open)depth++;if(c==close&&--depth==0)return i;}throw new IllegalArgumentException("unbalanced source");}
    static List<int[]> entries(String code,int start,int end){var entries=new ArrayList<int[]>();int depth=0,at=start;for(int i=start;i<end;i++){char c=code.charAt(i);if(c=='('||c=='['||c=='{')depth++;if(c==')'||c==']'||c=='}')depth--;if(c==','&&depth==0){entries.add(trim(code,at,i));at=i+1;}}if(!code.substring(at,end).isBlank())entries.add(trim(code,at,end));return entries;}
    static int[] trim(String s,int a,int b){while(a<b&&Character.isWhitespace(s.charAt(a)))a++;while(b>a&&Character.isWhitespace(s.charAt(b-1)))b--;return new int[]{a,b};}
}
