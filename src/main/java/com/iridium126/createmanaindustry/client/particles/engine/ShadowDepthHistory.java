package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.Arrays;

/** CPU identity gate only. Pixel reliability is a separate GPU contract, never implied by this gate. */
public final class ShadowDepthHistory {
    public record Stamp(long session,long options,Object dimension,int width,int height,long frame,
                        double x,double y,double z,float forwardX,float forwardY,float forwardZ,float[] projection) {
        public Stamp {if(projection.length!=16)throw new IllegalArgumentException("History projection matrix");projection=projection.clone();}
        @Override public float[] projection(){return projection.clone();}
    }
    private Stamp committed;
    private String reason="no complete previous frame";
    public void publish(Stamp stamp){committed=stamp;reason="previous frame published; pixel reliability required";}
    public void invalidate(String why){committed=null;reason=why;}
    public String reason(){return reason;}
    public boolean accepts(Stamp now){
        Stamp old=committed;if(old==null)return false;
        if(now.session()!=old.session()){reason="shader-pack session changed";return false;}
        if(now.options()!=old.options()){reason="shader-pack options changed";return false;}
        if(!java.util.Objects.equals(now.dimension(),old.dimension())){reason="dimension changed";return false;}
        if(now.width()!=old.width()||now.height()!=old.height()){reason="depth dimensions changed";return false;}
        if(now.frame()!=old.frame()+1){reason="history frame discontinuity";return false;}
        if(!Arrays.equals(now.projection,old.projection)){reason="projection changed";return false;}
        double dx=now.x()-old.x(),dy=now.y()-old.y(),dz=now.z()-old.z();
        if(!Double.isFinite(dx+dy+dz)||dx*dx+dy*dy+dz*dz>64){reason="camera translation exceeds 8 blocks";return false;}
        double dot=now.forwardX()*old.forwardX()+now.forwardY()*old.forwardY()+now.forwardZ()*old.forwardZ();
        double norm=Math.sqrt((now.forwardX()*now.forwardX()+now.forwardY()*now.forwardY()+now.forwardZ()*now.forwardZ())
                *(old.forwardX()*old.forwardX()+old.forwardY()*old.forwardY()+old.forwardZ()*old.forwardZ()));
        if(!Double.isFinite(dot)||!Double.isFinite(norm)||norm<1e-12||dot/norm<Math.cos(Math.toRadians(15))){reason="camera rotation exceeds 15 degrees";return false;}
        for(float p:now.projection)if(!Float.isFinite(p)){reason="nonfinite projection";return false;}
        reason="previous complete frame matches; temporal error permitted; pixel reliability required";return true;
    }
}
