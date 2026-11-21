package com.iridium126.createmanaindustry.client.gpu.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL45.GL_CONTEXT_LOST;

/** Attributes context-wide OpenGL error flags to a checked batch of commands. */
public final class GpuGlErrors {
    private static final Logger LOGGER = LoggerFactory.getLogger(GpuGlErrors.class);

    private GpuGlErrors() {}

    public static void begin(String operation) {
        int error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            if (error == GL_CONTEXT_LOST) throw failure(operation, error);
            LOGGER.debug("OpenGL error 0x{} was already pending before {}", Integer.toHexString(error), operation);
        }
    }

    public static void check(String operation) {
        int first = glGetError();
        if (first == GL_NO_ERROR) return;
        if (first != GL_CONTEXT_LOST) {
            int next;
            while ((next = glGetError()) != GL_NO_ERROR) {
                if (next == GL_CONTEXT_LOST) throw failure(operation, next);
            }
        }
        throw failure(operation, first);
    }

    private static IllegalStateException failure(String operation, int error) {
        return new IllegalStateException(operation + " (OpenGL 0x" + Integer.toHexString(error) + ")");
    }
}
