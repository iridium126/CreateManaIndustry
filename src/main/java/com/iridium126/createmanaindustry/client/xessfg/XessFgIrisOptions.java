package com.iridium126.createmanaindustry.client.xessfg;

import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;

/** Reads pack defaults and overrides through the same option contract for capture and allocation. */
public record XessFgIrisOptions(ShaderPackOptions options) implements XessFgPack.Options {
    @Override
    public boolean flag(String key, boolean fallback) {
        var values = options.getOptionValues();
        return values.getOptionSet().getBooleanOptions().containsKey(key)
                ? values.getBooleanValueOrDefault(key) : fallback;
    }

    @Override
    public float number(String key, float fallback) {
        var values = options.getOptionValues();
        if (!values.getOptionSet().getStringOptions().containsKey(key)) return fallback;
        try {
            return Float.parseFloat(values.getStringValueOrDefault(key));
        } catch (NumberFormatException | NullPointerException invalid) {
            return fallback;
        }
    }
}
