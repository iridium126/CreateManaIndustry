package com.iridium126.createmanaindustry.client.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.StormGpuDevices;
import com.iridium126.createmanaindustry.client.xessfg.XessFgAdapters;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgGpu;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgTransportMode;
import com.iridium126.createmanaindustry.infrastructure.config.XessFgSurfaceMode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration screen for {@link ClientConfig}, with custom sections for Allay Storm, XeSS-FG
 * and the startup switches. Other sections use NeoForge's default editors.
 */
public final class ClientConfigScreen {
    private ClientConfigScreen() {}

    public static Screen create(ModContainer mod, Screen parent) {
        return new ConfigurationScreen(mod, parent, (root, type, config, title) -> config.getSpec() == ClientConfig.SPEC
                ? new RootSection(root, type, config, title).build()
                : new ConfigurationSectionScreen(root, type, config, title));
    }

    private static final class RootSection extends ConfigurationSectionScreen {
        RootSection(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
            super(parent, type, config, title);
        }

        Screen build() { return rebuild(); }

        @Override
        protected Element createSection(String key, UnmodifiableConfig spec, UnmodifiableConfig section) {
            if (!key.equals("allayStorm") && !key.equals("xessFg") && !key.equals("experimental"))
                return super.createSection(key, spec, section);
            var title = getTranslationComponent(key);
            var tooltip = getTooltipComponent(key, null);
            return new Element(title, tooltip, Button.builder(title, button -> minecraft.setScreen(
                    sectionCache.computeIfAbsent(key, name -> switch (key) {
                        case "xessFg" -> new XessFgSection(context, this, spec.valueMap(), name, section.entrySet(), title).build();
                        case "allayStorm" -> new StormSection(context, this, spec.valueMap(), name, section.entrySet(), title).build();
                        default -> new ExperimentalSection(context, this, spec.valueMap(), name, section.entrySet(), title).build();
                    })))
                    .width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tooltip)).build(), false);
        }
    }

    /**
     * The startup switch is a plain {@code ConfigValue<Boolean>}, so NeoForge has no editor for it.
     * Show its file value without the "cannot be edited in the UI" warning: the switch is
     * intentionally restart-only and is read from the raw TOML before Mixins load.
     */
    private static final class ExperimentalSection extends ConfigurationSectionScreen {
        ExperimentalSection(Context context, Screen parent, Map<String, Object> specs, String key,
                            Set<? extends UnmodifiableConfig.Entry> entries, Component title) {
            super(context, parent, specs, key, entries, title);
        }

        ConfigurationSectionScreen build() { return rebuild(); }

        @Override
        protected Element createOtherValue(String key, ModConfigSpec.ConfigValue<?> value) {
            if (!key.equals("clientPackages")) return super.createOtherValue(key, value);
            var label = new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT,
                    Component.literal(String.valueOf(value.getRaw())), font).alignLeft();
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), label, false);
        }
    }

    private static final class XessFgSection extends ConfigurationSectionScreen {
        private static final String GPU_TRANSLATION = "createmanaindustry.configuration.xessFg.gpu";

        XessFgSection(Context context, Screen parent, Map<String, Object> specs, String key,
                      Set<? extends UnmodifiableConfig.Entry> entries, Component title) {
            super(context, parent, specs, key, entries, title);
        }

        ConfigurationSectionScreen build() { return rebuild(); }

        @Override
        protected Element createStringValue(String key, Predicate<String> tester, Supplier<String> source,
                                            Consumer<String> target) {
            if (key.equals("transport") || key.equals("surface")) {
                String translation = "createmanaindustry.configuration.xessFg." + key;
                boolean transport = key.equals("transport");
                var choices = transport ? java.util.Arrays.stream(XessFgTransportMode.values()).map(XessFgTransportMode::value).toList()
                        : java.util.Arrays.stream(XessFgSurfaceMode.values()).map(XessFgSurfaceMode::value).toList();
                String selected = transport ? XessFgTransportMode.parse(source.get()).value()
                        : XessFgSurfaceMode.parse(source.get()).value();
                var option = new OptionInstance<>(getTranslationKey(key),
                        value -> Tooltip.create(Component.translatable(translation + "." + value + ".tooltip")),
                        (caption, value) -> Component.translatable(translation + "." + value),
                        new Custom<>(choices), selected,
                        value -> undoManager.add(v -> { target.accept(v); onChanged(key); }, value,
                                v -> { target.accept(v); onChanged(key); }, source.get()));
                return new Element(Component.translatable(translation),
                        Component.translatable(translation + ".tooltip"), option);
            }
            if (!key.equals("gpu")) return super.createStringValue(key, tester, source, target);
            String current = source.get();
            var selected = XessFgGpu.parse(current);
            var choices = new LinkedHashMap<String, Component>();
            choices.put(XessFgGpu.AUTO, Component.translatable(GPU_TRANSLATION + ".auto"));
            try {
                for (var device : XessFgAdapters.discover(minecraft.gameDirectory.toPath())) {
                    var gpu = device.selection();
                    String value = gpu.id().equals(selected.id()) ? current : gpu.value();
                    choices.put(value, Component.literal(gpu.label()));
                }
            } catch (IOException | LinkageError | RuntimeException unavailable) {
                // Keep the saved selection available for viewing and undo.
            }
            choices.putIfAbsent(current, Component.translatable(GPU_TRANSLATION + ".unavailable",
                    selected.label().isEmpty() ? selected.id() : selected.label()));
            var option = new OptionInstance<>(getTranslationKey(key), value -> Tooltip.create(choices.get(value)),
                    (caption, value) -> choices.get(value), new Custom<>(new ArrayList<>(choices.keySet())),
                    current, value -> undoManager.add(v -> { target.accept(v); onChanged(key); }, value,
                            v -> { target.accept(v); onChanged(key); }, source.get()));
            return new Element(Component.translatable(GPU_TRANSLATION),
                    Component.translatable(GPU_TRANSLATION + ".tooltip"), option);
        }

        @Override
        protected Element createIntegerValue(String key, ModConfigSpec.ValueSpec spec, Supplier<Integer> source,
                                             Consumer<Integer> target) {
            if (!key.equals("multiplier")) return super.createIntegerValue(key, spec, source, target);
            String translation = "createmanaindustry.configuration.xessFg.multiplier";
            var option = new OptionInstance<>(getTranslationKey(key),
                    value -> Tooltip.create(Component.translatable(translation + ".tooltip")),
                    (caption, value) -> Component.literal(value + "×"),
                    new Custom<>(ClientConfig.XESS_FG_MULTIPLIERS), source.get(),
                    value -> undoManager.add(v -> { target.accept(v); onChanged(key); }, value,
                            v -> { target.accept(v); onChanged(key); }, source.get()));
            return new Element(Component.translatable(translation),
                    Component.translatable(translation + ".tooltip"), option);
        }
    }

    private static final class StormSection extends ConfigurationSectionScreen {
        private static final String GPU = "simulationGpu";
        private static final String TRANSLATION = "createmanaindustry.configuration.simulationGpu";
        private record Choice(Component label, Component tooltip) {}

        StormSection(Context context, Screen parent, Map<String, Object> specs, String key,
                     Set<? extends UnmodifiableConfig.Entry> entries, Component title) {
            super(context, parent, specs, key, entries, title);
        }

        ConfigurationSectionScreen build() { return rebuild(); }

        @Override
        protected Element createStringValue(String key, Predicate<String> tester, Supplier<String> source,
                                            Consumer<String> target) {
            if (!key.equals(GPU)) return super.createStringValue(key, tester, source, target);
            String current = source.get();
            var selected = StormSimulationGpu.parse(current);
            var choices = new LinkedHashMap<String, Choice>();
            String renderer = StormGpuDevices.renderer();
            choices.put(StormSimulationGpu.DEFAULT_VALUE, new Choice(model(renderer),
                    Component.translatable(TRANSLATION + ".minecraft")));
            long renderLuid = StormGpuDevices.renderAdapter().luid();
            for (var device : StormGpuDevices.discover()) {
                if (!device.visible(renderLuid, selected.uuid())) continue;
                var gpu = device.selection();
                String value = gpu.uuid().equals(selected.uuid()) ? current : gpu.value();
                choices.put(value, new Choice(model(gpu.label()),
                        Component.translatable(TRANSLATION + ".pci", device.backends(), device.pci())));
            }
            choices.putIfAbsent(current, new Choice(model(selected.label()),
                    Component.translatable(TRANSLATION + ".unavailable", selected.uuid())));
            var option = new OptionInstance<>(getTranslationKey(key),
                    value -> Tooltip.create(choices.get(value).tooltip()),
                    (caption, value) -> choices.get(value).label(), new Custom<>(new ArrayList<>(choices.keySet())),
                    current, value -> undoManager.add(v -> { target.accept(v); onChanged(key); }, value,
                            v -> { target.accept(v); onChanged(key); }, source.get()));
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), option);
        }

        private static Component model(String label) {
            return label.isEmpty() ? Component.translatable(TRANSLATION + ".unknown") : Component.literal(label);
        }
    }
}
