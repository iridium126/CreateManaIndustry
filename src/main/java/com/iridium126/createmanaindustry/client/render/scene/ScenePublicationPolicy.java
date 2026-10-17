package com.iridium126.createmanaindustry.client.render.scene;

import com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication;

/** Resource versions refer to their actual publications, never to game or package logical ticks. */
public final class ScenePublicationPolicy {
    private ScenePublicationPolicy() {}
    public static void validate(Publication frame,long population,long storm,long packages) {
        if(frame.populationGeneration()!=population||population!=frame.generation()
                ||frame.stormGeneration()!=storm||frame.packageGeneration()!=packages)
            throw new IllegalArgumentException("Mixed scene resource versions");
    }
}
