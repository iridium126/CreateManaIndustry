package com.iridium126.createmanaindustry.client.gpu.shader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Resource-manager adapter; source consumers supply their own namespace and include root. */
public final class GpuShaderResources {
    private GpuShaderResources() {}
    public static Function<String,String> loader(String namespace,String root){
        GpuShaderSources.check(root);
        if(!root.endsWith("/"))throw new IllegalArgumentException("Shader include root must end in /");
        return name->{
            GpuShaderSources.check(name);
            String source=GpuShaderSources.load(root+name,root,path->{
                try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(ResourceLocation.fromNamespaceAndPath(namespace,path))){
                    var out=new StringBuilder();char[] chars=new char[4096];int count;
                    while((count=reader.read(chars))!=-1)out.append(chars,0,count);
                    return out.toString();
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            if(source==null)throw new IllegalStateException("Missing GPU shader: "+root+name);
            return source;
        };
    }
}
