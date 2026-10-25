package com.iridium126.createmanaindustry.client.render.shaderpack;

/** Effective Iris options including shipped defaults. No option-file IO on draw paths. */
public final class ActivePackOptions {
    private ActivePackOptions(){}
    public static boolean isEnabled(String key) {
        var options=ShaderPackSession.current().options();
        if(options==null||!options.getOptionSet().getBooleanOptions().containsKey(key))return false;
        return options.getOptionValues().getBooleanValueOrDefault(key);
    }
    public static double doubleValue(String key,double fallback) {
        var options=ShaderPackSession.current().options();
        if(options==null||!options.getOptionSet().getStringOptions().containsKey(key))return fallback;
        try {double value=Double.parseDouble(options.getOptionValues().getStringValueOrDefault(key));return Double.isFinite(value)?value:fallback;}
        catch(RuntimeException invalid){return fallback;}
    }
}
