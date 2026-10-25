package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Immutable, load-time source inventory. No dependency on Iris, Minecraft or a GL context. */
public record ShaderPackInventory(Map<String,String> sources, String digest) {
    public ShaderPackInventory { sources=Collections.unmodifiableMap(new TreeMap<>(sources)); }
    public static ShaderPackInventory of(Map<String,String> inputs) {
        var sources=new TreeMap<String,String>();
        inputs.forEach((p,s)->sources.put(normalize(p),s));
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            sources.forEach((p,s)->{hash.update(p.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);hash.update(s.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);});
            return new ShaderPackInventory(sources,HexFormat.of().formatHex(hash.digest()));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static ShaderPackInventory read(Path shaderRoot) throws IOException {
        var result=new TreeMap<String,String>();
        try(Stream<Path> paths=Files.walk(shaderRoot)) {
            for(Path path:paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative=normalize(shaderRoot.relativize(path).toString());
                if(relative.matches(".*\\.(?:glsl|fsh|vsh|gsh|csh|tcs|tes|frag|vert|geom|comp|inc|h|properties)$"))
                    result.put(relative,Files.readString(path));
            }
        }
        // Iris includes may use arbitrary text suffixes, e.g. shaders.settings.
        // Follow those dependencies once at load time instead of silently omitting them.
        var pending=new ArrayList<>(result.keySet());
        var includes=Pattern.compile("(?m)^\\s*#include\\s+[\"<]([^\">]+)[\">]");
        for(int i=0;i<pending.size();i++){
            if(pending.size()>10000)throw new IOException("Shader include inventory limit exceeded");
            String file=pending.get(i);Matcher matcher=includes.matcher(code(result.get(file)));
            while(matcher.find()){
                String target=matcher.group(1);Path included=(target.startsWith("/")?shaderRoot.resolve(target.substring(1)):shaderRoot.resolve(file).getParent().resolve(target)).normalize();
                if(!included.startsWith(shaderRoot.normalize())||!Files.isRegularFile(included))continue;
                String path=normalize(shaderRoot.relativize(included).toString());
                if(!result.containsKey(path)){result.put(path,Files.readString(included));pending.add(path);}
            }
        }
        return of(result);
    }
    public static ShaderPackInventory readPack(Path path) throws IOException {
        if(Files.isDirectory(path))return read(Files.isDirectory(path.resolve("shaders"))?path.resolve("shaders"):path);
        try(FileSystem zip=FileSystems.newFileSystem(path)) { return read(zip.getPath("/shaders")); }
    }
    public static String normalize(String path) {
        String p=path.replace('\\','/');while(p.startsWith("/"))p=p.substring(1);
        return p.startsWith("shaders/")?p.substring(8):p;
    }
    public String source(String path) { return sources.getOrDefault(normalize(path),""); }
    public String uniqueSuffix(String suffix) {
        var hits=sources.keySet().stream().filter(p->p.equals(suffix)||p.endsWith("/"+suffix)).toList();
        return hits.size()==1?hits.getFirst():null;
    }
    /** Comment masking preserves offsets, allowing structural edits against the original source. */
    public static String code(String input) {
        Matcher m=Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*").matcher(input);
        var out=new StringBuilder(input);
        while(m.find())for(int i=m.start();i<m.end();i++)if(out.charAt(i)!='\r'&&out.charAt(i)!='\n')out.setCharAt(i,' ');
        return out.toString();
    }
    public Set<Integer> colorBuffers() {
        var result=new TreeSet<Integer>();
        for(String source:sources.values()) {
            Matcher m=Pattern.compile("\\bcolortex(\\d+)\\b").matcher(code(source));
            while(m.find())result.add(Integer.parseInt(m.group(1)));
            m=Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9, ]+)\\*/").matcher(source);
            while(m.find()) {
                if(m.group(1).equals("DRAWBUFFERS"))for(char c:m.group(2).toCharArray()){if(Character.isDigit(c))result.add(c-'0');}
                else for(String n:m.group(2).split(","))if(!n.isBlank())result.add(Integer.parseInt(n.trim()));
            }
        }
        return Collections.unmodifiableSet(result);
    }
    public Set<Integer> materialIds() {
        var ids=new TreeSet<Integer>();
        for(String source:sources.values()) {
            Matcher m=Pattern.compile("(?:block|item|entity)\\.(\\d+)\\s*=|\\b(?:case|mat\\s*==|blockId\\s*==)\\s*(\\d+)").matcher(code(source));
            while(m.find())ids.add(Integer.parseInt(m.group(1)!=null?m.group(1):m.group(2)));
        }
        return ids;
    }
    public boolean containsCode(String path,String regex) { return Pattern.compile(regex,Pattern.DOTALL).matcher(code(source(path))).find(); }
}
