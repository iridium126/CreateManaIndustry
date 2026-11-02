package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality;

/** Render-thread snapshot. A config reload cannot split grouping, casting and receiving within a frame. */
public final class ShadowQualityFrame {
    private static ParticleShadowQuality current=ParticleShadowQuality.FULL;
    private ShadowQualityFrame() {}
    public static ParticleShadowQuality current(){return current;}
    public static boolean begin(){var next=ClientConfig.shaderPackShadowQuality;boolean changed=next!=current;current=next;return changed;}
}
