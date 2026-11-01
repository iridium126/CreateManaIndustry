package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;

/** Explicit diagnostic entry. Geometric tests alone never qualify a simulation backend. */
public final class HardwareExperiments {
    private HardwareExperiments(){}
    public static String status(){
        com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.invalidate("hardware capability probe");
        var caps=GL.getCapabilities();var report=new StringBuilder("R7 "+HardwareBackendRuntime.INSTANCE.description()+"; qualified client backends="+HardwareBackendRuntime.qualified()+".\n");
        report.append("GL shared memory/semaphore Win32=").append(caps.GL_EXT_memory_object_win32).append('/').append(caps.GL_EXT_semaphore_win32).append("; shader clock=").append(caps.GL_ARB_shader_clock).append('\n');
        var game=Minecraft.getInstance().gameDirectory.toPath();Path installed=game.resolve("cmi-hardware/native");
        Path config=game.resolve("cmi-hardware/config.json");if(Files.isRegularFile(config))try{installed=Path.of(com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject().get("native").getAsString());}catch(Exception failure){report.append("Native config unavailable: ").append(failure).append('\n');}
        Path root=Path.of(System.getProperty("cmi.hardware.nativeDirectory",(Files.isDirectory(installed)?installed:game.resolve("../.gradle/hardware-offload/build").normalize()).toString()));
        if(!Files.isRegularFile(root.resolve("CmiHardwareOffload.dll")))return report.append("Optional native module unavailable at ").append(root.toAbsolutePath()).append(". Ordinary builds do not install CUDA/OptiX.\n").append(qualification()).toString();
        try{HardwareNative.load(root);report.append(HardwareNative.probe()).append('\n');report.append(HardwareNative.mediaProbe()).append('\n');}catch(RuntimeException|LinkageError failure){report.append("Native probe failed: ").append(failure).append('\n');}
        return report.append(qualification()).toString();
    }
    private static String qualification(){return "Only receipt-qualified diagnostic variants may be selected. Real-client FPS and visual acceptance remain user measurements. Media DLL availability is not a particle acceleration result; COPY is an isolated state-transfer candidate unless separately admitted.";}
}
