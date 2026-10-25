package com.iridium126.createmanaindustry.client.render.shaderpack;

/** The verified deferred-HDR conversion, using the session's effective options. */
public final class SundialAutoExposure {
    private SundialAutoExposure(){}
    public static float compensationScale(){
        if(ShaderPackSession.current().capabilities().worldPath()!=PackCapabilities.WorldPath.HDR)return 1;
        double exposure=ActivePackOptions.doubleValue("EXPOSURE_VALUE",0);
        return (float)Math.max(.125,Math.min(64,1/(.2*Math.pow(2,exposure))));
    }
}
