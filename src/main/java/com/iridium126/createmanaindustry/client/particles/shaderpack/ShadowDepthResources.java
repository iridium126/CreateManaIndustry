package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.particles.engine.*;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality;

/** Render-thread depth ownership and entry provenance. Unsupported contracts allocate no resources. */
public final class ShadowDepthResources implements AutoCloseable {
    public static final ShadowDepthResources INSTANCE=new ShadowDepthResources();
    private long session=-1,entryFrame=-1;
    private Object owner;
    private ParticleShadowQuality quality;
    private ShadowReceiverContract contract;
    private int currentDepth,width,height;
    private String shadowReason="current shadow entry not observed",screenReason="pre-hand boundary not observed";
    private final ShadowDepthHistory history=new ShadowDepthHistory();
    private ShadowDepthPyramid current;
    private final ShadowDepthPyramid[] previous=new ShadowDepthPyramid[2];
    private int readBank;
    private ShadowDepthContext currentContext,previousContext;
    private ShadowDepthResources(){}
    public void shadowEntry(Object targetOwner,int texture,int resolution,long frame){
        var q=ShadowQualityFrame.current();
        if(!ClientConfig.shaderPackIntegration||(!q.shadowDepth()&&!q.screenDepth())){if(session!=-1)close();shadowReason=screenReason="not requested";return;}
        long generation=ShaderPackSession.current().revision();
        if(session!=generation||owner!=targetOwner){close();session=generation;owner=targetOwner;contract=ShadowReceiverContract.resolve(ShaderPackSession.current().inventory());}
        currentDepth=texture;width=height=resolution;entryFrame=frame;currentContext=null;
        // A recognized radial distortion is not a proof of bias, filter bounds or shader side effects.
        if(quality!=q){quality=q;
            shadowReason=q.shadowDepth()?contract.pack()+": native bias/filter/side-effect contract not verified":"not requested";
            screenReason=q.screenDepth()?contract.pack()+": reliable static-receiver mask not available":"not requested";}
    }
    /** Native source provenance for diagnostics and a full-chain qualification harness; never the opaque snapshot. */
    public int entryTexture(long frame){return entryFrame==frame&&session==ShaderPackSession.current().revision()?currentDepth:0;}
    public int entryWidth(){return width;}public int entryHeight(){return height;}
    public void releaseOwner(Object targets){if(owner==targets)close();}
    public ShadowDepthContext current(){return currentContext;}public ShadowDepthContext previous(){return previousContext;}
    public String report(){var q=ShadowQualityFrame.current();return "shadow="+(q.shadowDepth()?shadowReason:"not requested")+"; screen="+(q.screenDepth()?screenReason:"not requested");}
    public void preHand(){
        if(!ClientConfig.shaderPackIntegration||!ShadowQualityFrame.current().screenDepth())return;
        // Depth by itself cannot certify static terrain: moving entities can occupy the same texels.
        // Do not publish an apparently complete history bank without the GPU reliability attachment.
        previousContext=null;history.invalidate("pre-hand depth has no static-receiver reliability attachment");
        screenReason=history.reason();
    }
    @Override public void close(){
        currentContext=previousContext=null;entryFrame=-1;currentDepth=width=height=0;owner=null;session=-1;readBank=0;quality=null;contract=null;
        if(current!=null)current.close();current=null;
        for(int i=0;i<previous.length;i++){if(previous[i]!=null)previous[i].close();previous[i]=null;}
        history.invalidate("depth resources invalidated");
    }
}
