package com.iridium126.createmanaindustry.client.particles.engine;

/** Validated GPU-only query inputs. Arrays belong to the resource owner and are reused per bank. */
public record ShadowDepthContext(int texture,int sampler,int width,int height,float[] matrix,
                                 int distortion,float amount,float depthScale,float margin,float filter,
                                 boolean reversed,float originX,float originY,float originZ) {
    public ShadowDepthContext {
        if(texture<=0||sampler<=0||width<1||height<1||matrix.length!=16||distortion<0||distortion>2
                ||!Float.isFinite(amount)||amount<0||amount>=1||!Float.isFinite(depthScale)||depthScale<=0
                ||!Float.isFinite(margin)||margin<0||!Float.isFinite(filter)||filter<0)
            throw new IllegalArgumentException("Unproven depth query bounds");
        for(float value:matrix)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite depth matrix");
        if(!Float.isFinite(originX)||!Float.isFinite(originY)||!Float.isFinite(originZ))throw new IllegalArgumentException("Nonfinite depth origin");
    }
}
