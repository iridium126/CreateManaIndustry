package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.nio.file.Path;
import java.util.Set;

/** Backend changes are applied only at a simulation boundary; retirement never waits. */
public final class SpatialQueryRuntime implements AutoCloseable {
    public static final SpatialQueryRuntime INSTANCE=new SpatialQueryRuntime();
    private String requested=System.getProperty("cmi.particle.backend","gl"),active="gl",failure="";
    private boolean changed=true;
    private SpatialScene scene;
    private SpatialQueryRuntime(){}
    public static Set<String> backends(){return Set.of("auto","gl","rt");}
    public boolean request(String backend){if(!backends().contains(backend))return false;requested=backend;changed=true;com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.invalidate("spatial backend changed");return true;}
    public void invalidate(){changed=true;}
    public SpatialScene begin(long tick,float time,long resourceEpoch){
        RtQueryWorker.retire();
        if(changed){close();changed=false;}
        if(scene==null){
            // Auto remains portable until whole-chain RT measurements qualify this module.
            boolean nativeQueries=requested.equals("rt");
            Path modules=Path.of(System.getProperty("cmi.particle.rt.path","cmi-particle-rt"));
            if(nativeQueries)try{scene=new SpatialScene(modules,true);active=scene.nativeFailed()?"rt-failed":"rt";failure="";}
            catch(RuntimeException|LinkageError error){failure=error.toString();throw new IllegalStateException("RT initialization failed; select GL explicitly",error);}
            if(scene==null){scene=new SpatialScene(modules,false);active="gl";}
        }
        scene.begin(tick,time,resourceEpoch);return scene;
    }
    public String identity(){return "requested="+requested+" active="+(scene==null?active:scene.backend())+" abi=12";}
    public String status(){return identity()+"; auto RT qualification=pending\n"+(scene==null?"not initialized":scene.description())+(failure.isEmpty()?"":"\nfailure="+failure);}
    @Override public void close(){if(scene!=null)scene.close();scene=null;}
}
