import com.iridium126.createmanaindustry.client.particles.engine.BlockEmitterTable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/** Exercises the actual resident-table implementation in a hidden GL context. */
public class BlockEmitterTableValidation {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static float word(int slot, int word) {
        int buffer = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, 4);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        float[] value = new float[1];
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (slot * 8L + word) * 4, value);
        return value[0];
    }

    public static void main(String[] args) {
        check(GLFW.glfwInit(), "GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        long window = GLFW.glfwCreateWindow(32, 32, "Table validation", 0, 0);
        check(window != 0, "GL context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        BlockEmitterTable table = new BlockEmitterTable();
        try {
            for (int i = 0; i < 50000; i++)
                check(table.add(i, 2, 3, 100, 4, 8) == i, "50k allocation");
            check(table.size() == 50000, "occupied count");
            table.uploadAndBind();
            check(word(49999, 0) == 49999 && word(49999, 4) == 4, "bulk upload layout");

            // Simulate the phase advanced by the compute shader; unrelated CPU
            // edits must not overwrite this GPU-owned word.
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (100 * 8L + 5) * 4, new float[]{.375f});
            table.remove(99);
            table.remove(101);
            table.uploadAndBind();
            check(word(99, 3) == 0 && word(101, 3) == 0, "sparse removal upload");
            check(word(100, 5) == .375f, "GPU phase survives neighboring edits");
            check(table.add(7, 8, 9, 25, 2, 3) == 99, "reuse first hole");
            table.uploadAndBind();
            check(word(99, 0) == 7 && word(99, 3) == 25, "reused slot payload");
            check(word(100, 5) == .375f, "GPU phase survives slot reuse");

            table.remove(49999);
            check(table.dispatchSize() == 49999, "trailing removal shrinks dispatch");
            table.clear();
            check(table.dispatchSize() == 0 && table.size() == 0, "dimension clear");
            check(table.spawnBound(.1f, 1, 1000000) == 0, "cleared spawn bound");
            check(table.add(11, 12, 13, 1, 0, 1) == 0, "new dimension starts at zero");
            table.uploadAndBind();
            check(word(0, 0) == 11 && word(0, 3) == 1, "clear and re-upload");

            for (int i = 1; i < BlockEmitterTable.CAPACITY; i++)
                check(table.add(i, 0, 0, 100, 0, 1) == i, "capacity fill");
            check(table.add(0, 0, 0, 100, 0, 1) == -1, "capacity guard");
            check((table.dispatchSize() + 3) / 4 <= 65535, "portable dispatch limit");
            table.remove(123);
            check(table.add(0, 0, 0, 100, 0, 1) == 123, "full table recovers freed slot");
            check(table.spawnBound(.25f, 1, 1000000) == 1000000, "saturated spawn bound");
            check(GL43.glGetError() == 0, "OpenGL errors");
            System.out.println("PASS: resident table 50k upload, sparse edits, GPU phase preservation, dimension clear, capacity/reuse");
        } finally {
            table.free();
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
