package com.iridium126.createmanaindustry.client.render.scene;
/** CPU draw inputs borrowed from the successfully published particle generation. */
public record ParticleRenderFrame(long generation,int aliveEstimate,int entryCount,boolean sorted,int permutation) {}
