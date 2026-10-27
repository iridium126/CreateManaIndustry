package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import java.util.*;

/** Own only sky writes. Read-only inputs retain the source pack's textures and binding precision. */
public final class NativeSkyTargets extends RenderTargets {
    private final RenderTargets source;
    private final ImmutableSet<Integer> sourceFlips;
    private final Set<Integer> writes;
    private final Map<Integer,RenderTarget> references=new HashMap<>();
    public NativeSkyTargets(int w,int h,int depth,PackDirectives directives,RenderTargets source,ImmutableSet<Integer> flips,Set<Integer> writes) {
        super(w,h,depth,0,DepthBufferFormat.DEPTH24,directives.getRenderTargetDirectives().getRenderTargetSettings(),directives);
        this.source=source;this.sourceFlips=flips;this.writes=Set.copyOf(writes);
    }
    @Override public RenderTarget getOrCreate(int index) {
        if(source==null||writes.contains(index))return super.getOrCreate(index);
        return references.computeIfAbsent(index,k->new RenderTarget(RenderTarget.builder().setDimensions(1,1).setName("cmi_readonly_"+k)) {
            private RenderTarget original(){return source.getOrCreate(k);}
            @Override public int getMainTexture(){return sourceFlips.contains(k)?original().getAltTexture():original().getMainTexture();}
            @Override public int getAltTexture(){return getMainTexture();}
            @Override public int getWidth(){return original().getWidth();}
            @Override public int getHeight(){return original().getHeight();}
        });
    }
    @Override public RenderTarget get(int index) { return writes==null||writes.contains(index)?super.get(index):getOrCreate(index); }
    @Override public void destroy(){super.destroy();references.values().forEach(RenderTarget::destroy);references.clear();}
}
