import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/** Shared hidden OpenGL 4.5 context for the off-screen validation fixtures. */
public final class GpuValidationSupport {
    private static GLFWErrorCallback callback;

    private GpuValidationSupport() {}

    public static long open(int width, int height, String title, boolean coreProfile) {
        callback = GLFWErrorCallback.createPrint(System.err);
        callback.set();
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        if (coreProfile) GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) throw new AssertionError("GL context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        return window;
    }

    public static long open(String title) {
        return open(64, 64, title, false);
    }

    public static void close(long window) {
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        if (callback != null) {
            callback.free();
            callback = null;
        }
    }
}
