package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;
import org.lwjgl.system.Library;
import org.lwjgl.system.windows.WindowsLibrary;

/** Uses Minecraft's active LWJGL native directory. Only file versions select modules. */
public final class ParticleNativeModules {
    private static Path installed;
    private static Path installedDxr;
    private static final String ROOT="/native/particles/windows-x86_64/";
    private static final Properties RELEASE=release();
    public static final String OPTIX_VERSION=RELEASE.getProperty("optixVersion");
    public static final String OPTIX_FILE="CmiParticleOptix-"+OPTIX_VERSION+".dll";
    static final String DXR_VERSION=RELEASE.getProperty("dxrVersion");
    private static final String DXR_FILE="CmiParticleDxr-"+DXR_VERSION+".dll";
    private ParticleNativeModules() {}

    private static Properties release(){
        var p=new Properties();
        try(var in=ParticleNativeModules.class.getResourceAsStream(ROOT+"release.properties")){
            if(in==null)throw new IOException("Bundled particle native release is missing");
            p.load(in);
            return p;
        }catch(IOException failure){throw new IllegalStateException("Particle native distribution is incomplete",failure);}
    }

    public static synchronized Path prepareOptix() throws IOException {
        if(installed!=null)return installed;
        installed=install(nativeDirectory(),ParticleNativeModules::resource);
        return installed;
    }

    static synchronized Path prepareDxr() throws IOException {
        if(installedDxr==null)installedDxr=install(nativeDirectory(),DXR_FILE,DXR_VERSION,()->resource("CmiParticleDxr.dll"));
        return installedDxr;
    }

    private static Path nativeDirectory() throws IOException {
        if(org.lwjgl.system.Platform.get()!=org.lwjgl.system.Platform.WINDOWS||!System.getProperty("os.arch","").equals("amd64"))
            throw new IOException("Native particle backends require Windows x86_64");
        Library.initialize();
        // Borrow LWJGL's existing handle. Its actual module path also covers
        // launcher layouts and LWJGL's automatic native extraction.
        String loadedPath=new WindowsLibrary(Library.JNI_LIBRARY_NAME,WindowsLibrary.HINSTANCE).getPath();
        if(loadedPath==null)throw new IOException("Minecraft's active LWJGL native directory is unavailable");
        Path directory=Path.of(loadedPath).toAbsolutePath().normalize().getParent();
        if(directory==null)throw new IOException("Minecraft's LWJGL module has no parent directory");
        return directory;
    }

    @FunctionalInterface interface Resource {InputStream open() throws IOException;}
    private static InputStream resource() throws IOException {
        return resource("CmiParticleOptix.dll");
    }

    private static InputStream resource(String name) throws IOException {
        var in=ParticleNativeModules.class.getResourceAsStream(ROOT+name);
        if(in==null)throw new IOException("Bundled particle module is missing: "+name);
        return in;
    }

    static Path install(Path directory,Resource resource) throws IOException {
        return install(directory,OPTIX_FILE,OPTIX_VERSION,resource);
    }

    private static Path install(Path directory,String name,String version,Resource resource) throws IOException {
        Path target=directory.resolve(name);
        if(matches(target,version))return target;
        try(var channel=FileChannel.open(directory.resolve(name+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.lock()){
            if(matches(target,version))return target;
            Path temporary=Files.createTempFile(directory,name,".tmp");
            try{
                try(var in=resource.open()){Files.copy(in,temporary,StandardCopyOption.REPLACE_EXISTING);}
                if(!version.equals(PeFileVersion.read(temporary)))throw new IOException("Bundled particle module version mismatch: "+name);
                try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
        }
        return target;
    }

    private static boolean matches(Path file,String version){
        if(!Files.isRegularFile(file))return false;
        try{return version.equals(PeFileVersion.read(file));}catch(IOException invalid){return false;}
    }
}
