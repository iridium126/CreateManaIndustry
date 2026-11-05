package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;
import org.lwjgl.system.Library;
import org.lwjgl.system.windows.WindowsLibrary;

/** Uses Minecraft's active LWJGL native directory. Only file versions select modules. */
public final class ParticleRtModules {
    private static Path installed;
    private static final String ROOT="/native/particle-rt/windows-x86_64/";
    private static final Properties RELEASE=release();
    public static final String VERSION=RELEASE.getProperty("version");
    public static final String FILE="CmiParticleRt-"+VERSION+".dll";
    private ParticleRtModules() {}

    private static Properties release(){
        var p=new Properties();
        try(var in=ParticleRtModules.class.getResourceAsStream(ROOT+"release.properties")){
            if(in==null)throw new IOException("Bundled Particle RT release is missing");
            p.load(in);
            if(!p.getProperty("version","").matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("Invalid Particle RT release version");
            return p;
        }catch(IOException failure){throw new IllegalStateException("Particle RT distribution is incomplete",failure);}
    }

    public static synchronized Path prepare() throws IOException {
        if(installed!=null)return installed;
        if(org.lwjgl.system.Platform.get()!=org.lwjgl.system.Platform.WINDOWS||!System.getProperty("os.arch","").equals("amd64"))
            throw new IOException("Particle RT requires Windows x86_64");
        Library.initialize();
        // Borrow LWJGL's existing handle. Its actual module path also covers
        // launcher layouts and LWJGL's automatic native extraction.
        String loadedPath=new WindowsLibrary(Library.JNI_LIBRARY_NAME,WindowsLibrary.HINSTANCE).getPath();
        if(loadedPath==null)throw new IOException("Minecraft's active LWJGL native directory is unavailable");
        Path directory=Path.of(loadedPath).toAbsolutePath().normalize().getParent();
        if(directory==null)throw new IOException("Minecraft's LWJGL module has no parent directory");
        installed=install(directory,ParticleRtModules::resource);
        return installed;
    }

    @FunctionalInterface interface Resource {InputStream open() throws IOException;}
    private static InputStream resource() throws IOException {
        var in=ParticleRtModules.class.getResourceAsStream(ROOT+"CmiParticleRt.dll");
        if(in==null)throw new IOException("Bundled Particle RT DLL is missing");
        return in;
    }

    static Path install(Path directory,Resource resource) throws IOException {
        Path target=directory.resolve(FILE);
        if(matches(target))return target;
        try(var channel=FileChannel.open(directory.resolve(FILE+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.lock()){
            if(matches(target))return target;
            Path temporary=Files.createTempFile(directory,"CmiParticleRt-",".tmp");
            try{
                try(var in=resource.open()){Files.copy(in,temporary,StandardCopyOption.REPLACE_EXISTING);}
                if(!VERSION.equals(PeFileVersion.read(temporary)))throw new IOException("Bundled Particle RT DLL version mismatch");
                try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
        }
        return target;
    }

    private static boolean matches(Path file){
        if(!Files.isRegularFile(file))return false;
        try{return VERSION.equals(PeFileVersion.read(file));}catch(IOException invalid){return false;}
    }
}
