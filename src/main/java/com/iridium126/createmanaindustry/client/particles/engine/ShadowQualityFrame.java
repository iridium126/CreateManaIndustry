package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality;

/** Render-thread snapshot. A config reload cannot split grouping, casting and receiving within a frame. */
public final class ShadowQualityFrame {
    private static ParticleShadowQuality current=ParticleShadowQuality.FULL;
    private static boolean receive=true, cameraRt=true;
    private ShadowQualityFrame() {}
    public static ParticleShadowQuality current(){return current;}
    public static boolean receivesShadows(){return receive;}
    public static boolean cameraRt(){return cameraRt;}
    public static boolean begin(){
        var next=ClientConfig.shaderPackShadowQuality;
        boolean nextReceive=ClientConfig.particleReceiveShadows, nextCameraRt=ClientConfig.rtCameraOcclusion;
        boolean changed=next!=current||nextReceive!=receive||nextCameraRt!=cameraRt;
        current=next;receive=nextReceive;cameraRt=nextCameraRt;
        return changed;
    }
}
