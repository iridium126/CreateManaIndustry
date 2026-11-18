package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;

public final class StormGpuConfigurationScreen {
    private static final String GPU = "simulationGpu";
    private static final String TRANSLATION = "createmanaindustry.configuration.simulationGpu";

    private StormGpuConfigurationScreen() {}

    public static Screen create(ModContainer mod, Screen parent) {
        return new ConfigurationScreen(mod, parent, (root, type, config, title) -> config.getSpec() == ClientConfig.SPEC
                ? new ClientSection(root, type, config, title).build()
                : new ConfigurationSectionScreen(root, type, config, title));
    }

    private static final class ClientSection extends ConfigurationSectionScreen {
        ClientSection(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
            super(parent, type, config, title);
        }

        Screen build() { return rebuild(); }

        @Override
        protected Element createSection(String key, UnmodifiableConfig spec, UnmodifiableConfig section) {
            if (!key.equals("allayStorm")) return super.createSection(key, spec, section);
            var title = getTranslationComponent(key);
            var tooltip = getTooltipComponent(key, null);
            return new Element(title, tooltip, Button.builder(title, button -> minecraft.setScreen(
                    sectionCache.computeIfAbsent(key, name -> new StormSection(context, this, spec.valueMap(),
                            name, section.entrySet(), title).build())))
                    .width(Button.DEFAULT_WIDTH).tooltip(Tooltip.create(tooltip)).build(), false);
        }
    }

    private static final class StormSection extends ConfigurationSectionScreen {
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
