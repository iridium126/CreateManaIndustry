package com.iridium126.createmanaindustry.client.particles.engine;

import org.lwjgl.opengl.*;

/** GPU-owned phase boundaries. Slot 0 = committed pool, 1 = output pool, 2 = sort items. */
final class ParticleDispatch {
    private ParticleDispatch() {}
    static void prepare(ParticlePrograms programs, int phase) {
        prepare(programs,phase,false);
    }
    static void prepare(ParticlePrograms programs,int phase,boolean packageStaged) {
        int program = programs.prepareDispatch();
        GL20.glUseProgram(program);
        CMIParticleEngine.setUIntUniform(program, "uPhase", phase);
        CMIParticleEngine.setUIntUniform(program,"uPackageStaged",packageStaged?1:0);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
    }
}
