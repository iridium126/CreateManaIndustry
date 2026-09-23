package com.iridium126.createmanaindustry.compat.ysm;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class YsmRuntimeGameTests {
    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    @SuppressWarnings("unchecked")
    public static void playerStateAndNativeModelCatalog(GameTestHelper helper) throws Exception {
        var symbols = YsmRuntimeSymbols.inspect(Path.of(System.getProperty("cmi.ysm.fixture")), YsmRuntimeSymbols.VERSION);
        var loader = YsmRuntimeGameTests.class.getClassLoader();
        helper.assertTrue((Boolean) Class.forName("com.elfmcys.yesstevemodel.YesSteveModel", false, loader)
                .getMethod("isAvailable").invoke(null), "YSM native runtime is unavailable; check its startup error");
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var result = (Optional<?>) symbols.playerState().bind(loader).invoke(null, player);
        helper.assertTrue(result.isPresent(), "YSM player attachment is absent");
        var state = (INBTSerializable<CompoundTag>) result.orElseThrow();
        var original = state.serializeNBT(helper.getLevel().registryAccess()).copy();
        helper.assertTrue(original.contains("model_id"), "YSM selection has no model ID");
        var lookup = symbols.serverLookup().bind(loader);
        // Wait for the official asynchronous native model loader, not a mock catalog.
        helper.succeedWhen(() -> {
            try {
                var model = (Optional<?>) lookup.invoke(null, "default");
                helper.assertTrue(model.isPresent(), "Native YSM default model has not loaded");
                helper.assertTrue(original.equals(state.serializeNBT(helper.getLevel().registryAccess())),
                        "Read-only probe changed player state");
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("YSM native catalog probe failed", exception);
            }
        });
    }
}
