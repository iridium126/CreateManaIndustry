import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

/** Real-driver compilation through the production candidate/commit/retirement path. */
public final class ParticleProgramLifecycleGpuValidation {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static String source(String path) {
        String body = ParticleShaderSource.loadResource(path, resource -> {
            try (var input = ParticleProgramLifecycleGpuValidation.class.getResourceAsStream(
                    "/assets/createmanaindustry/" + resource)) {
                String text = new String(Objects.requireNonNull(input, resource).readAllBytes(), StandardCharsets.UTF_8);
                for (String phase : List.of("spawn", "update"))
                    text = text.replace("#pragma cmi_types " + phase, ParticleTypes.shaderHooks(phase));
                return text;
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        return "#version 450 core\n" + ParticlePrograms.commonPrelude() + Objects.requireNonNull(body, path);
    }
    private static final class Compiler {
        final String fail;
        final Set<Integer> created = new HashSet<>();
        Compiler(String fail) { this.fail = fail; }
        int compute(String name) {
            if (fail != null && name.endsWith(fail)) return 0;
            String text=source(name);int id;
            try{id=GpuShaderCompiler.compute(name,text);}catch(RuntimeException failure){
                try{var directory=java.nio.file.Path.of("build/model-animation-shaders");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve(java.nio.file.Path.of(name).getFileName()),text);}catch(java.io.IOException suppressed){failure.addSuppressed(suppressed);}throw failure;
            }
            created.add(id);
            return id;
        }
        int graphics(String vertex, String fragment) {
            if (fail != null && vertex.endsWith(fail)) return 0;
            int id = GpuShaderCompiler.graphics(vertex, source(vertex), source(fragment));
            created.add(id);
            return id;
        }
    }
    private static boolean rebuild(ParticlePrograms programs, Compiler compiler) throws Exception {
        var method = ParticlePrograms.class.getDeclaredMethod("rebuildWithCompiler",
                java.util.function.ToIntFunction.class, java.util.function.BiFunction.class);
        method.setAccessible(true);
        return (boolean) method.invoke(programs,
                (java.util.function.ToIntFunction<String>) compiler::compute,
                (java.util.function.BiFunction<String, String, Integer>) compiler::graphics);
    }
    private static Set<Integer> owned(ParticlePrograms programs) throws Exception {
        var ids = new HashSet<Integer>();
        // All non-static int fields are owned GL programs. Audit the entire set,
        // so adding a required kernel cannot silently omit its transfer again.
        for (var field : ParticlePrograms.class.getDeclaredFields()) {
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            int id = field.getInt(programs);
            check(id != 0, "Missing committed program: " + field.getName());
            check(GL20.glIsProgram(id) && GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != 0,
                    "Committed program was retired: " + field.getName());
            check(ids.add(id), "Program has duplicate owners: " + field.getName());
        }
        return ids;
    }
    private static void retired(Set<Integer> programs) {
        for (int id : programs) check(!GL20.glIsProgram(id), "Program leaked after retirement: " + id);
    }
    private static void lifecycle() throws Exception {
        var programs = new ParticlePrograms();
        try {
            var rejected = new Compiler("dome_contacts.comp");
            check(!rebuild(programs, rejected) && !programs.ready(), "Failed first load became available");
            retired(rejected.created);

            programs.requestRebuild();
            check(programs.needsRebuild(), "Reload request was lost");
            var first = new Compiler(null);
            check(rebuild(programs, first), "Initial complete program set failed");
            check(programs.ready(), "Successful compilation left the engine unavailable");
            check(!programs.needsRebuild(), "Committed program set remained dirty");
            Set<Integer> original = owned(programs);
            check(original.equals(first.created), "Compiled/committed program ownership differs");
            check(programs.domeContacts() != 0 && original.contains(programs.domeContacts()),
                    "Dome contact kernel was not committed");
            programs.configureStatic(131072);

            for (String failure : List.of("dome_contacts.comp", "model.vsh")) {
                var candidate = new Compiler(failure);
                programs.requestRebuild();
                check(!rebuild(programs, candidate), "Incomplete reload was published: " + failure);
                check(programs.ready() && owned(programs).equals(original), "Reload destroyed the working set");
                retired(candidate.created);
            }

            var next = new Compiler(null);
            programs.requestRebuild();
            check(rebuild(programs, next) && programs.ready(), "Successful reload lost availability");
            check(owned(programs).equals(next.created), "Replacement program set was not fully committed");
            retired(original);
            programs.delete();
            check(!programs.ready() && programs.domeContacts() == 0, "Delete retained ready state");
            retired(next.created);
            programs.delete();
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "Program lifecycle caused GL errors");
        } finally { programs.delete(); }
    }
    public static void main(String[] args) throws Exception {
        try (var errors = GLFWErrorCallback.createPrint(System.err)) {
            errors.set();
            check(GLFW.glfwInit(), "GLFW initialization failed");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            long window = GLFW.glfwCreateWindow(64, 64, "Particle program lifecycle", 0, 0);
            check(window != 0, "OpenGL 4.5 context unavailable");
            try {
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                System.out.println(GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
                lifecycle();
                System.out.println("Particle program lifecycle GPU: " + checks + " assertions passed");
            } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
        }
    }
}
