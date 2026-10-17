package com.iridium126.createmanaindustry.client.gpu.shader;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Namespace-relative source expansion, independent of resource managers and business shader layouts. */
public final class GpuShaderSources {
    private static final Pattern INCLUDE=Pattern.compile("^\\s*#pragma\\s+cmi_include\\s+(\\S+)\\s*$",Pattern.MULTILINE);
    private GpuShaderSources() {}
    public static String load(String path,String includeRoot,Function<String,String> reader){
        check(path);check(includeRoot);return resolve(path,includeRoot,reader,0);
    }
    private static String resolve(String path,String root,Function<String,String> reader,int depth){
        String raw=reader.apply(path);if(raw==null)return null;
        var matcher=INCLUDE.matcher(raw);if(!matcher.find())return raw;
        if(depth>=12)throw new IllegalArgumentException("GPU shader include depth exceeded: "+path);
        matcher.reset();var output=new StringBuffer(raw.length());
        while(matcher.find()){
            String name=matcher.group(1);check(name);
            String included=resolve(root+name,root,reader,depth+1);if(included==null)return null;
            matcher.appendReplacement(output,Matcher.quoteReplacement(included));
        }
        matcher.appendTail(output);return output.toString();
    }
    public static void check(String path){
        if(path==null||path.isBlank()||path.startsWith("/")||path.startsWith("assets/")
                ||path.contains("\\")||path.contains(":")||path.contains(".."))
            throw new IllegalArgumentException("Expected namespace-relative GPU resource path: "+path);
    }
}
