import java.nio.file.Path;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Loader services for the opt-in standalone shader compiler, never packaged in the mod. */
public final class OffscreenIrisPlatform implements IrisPlatformHelpers {
    public static void initialize() throws ReflectiveOperationException {
        var field=net.irisshaders.iris.Iris.class.getDeclaredField("irisConfig");field.setAccessible(true);
        // Constructor defaults only. Never read or save the user's run/config.
        field.set(null,new net.irisshaders.iris.config.IrisConfig(Path.of("build/particle-offscreen-platform/iris.properties"),Path.of("build/particle-offscreen-platform/excluded.json")));
    }
    public boolean isModLoaded(String id){return false;}
    public String getVersion(){return "offscreen-fixture";}
    public boolean isDevelopmentEnvironment(){return false;}
    public Path getGameDir(){return Path.of("run").toAbsolutePath();}
    public Path getConfigDir(){return getGameDir().resolve("config");}
    public int compareVersions(String a,String b){throw new UnsupportedOperationException("Version-dependent loader behavior requires the client");}
    public KeyMapping registerKeyBinding(KeyMapping mapping){throw new UnsupportedOperationException("No key registration in the offscreen compiler");}
    public boolean useELS(){return false;}
    public BlockState getBlockAppearance(BlockAndTintGetter level,BlockState state,Direction face,BlockPos pos){return state;}
}
