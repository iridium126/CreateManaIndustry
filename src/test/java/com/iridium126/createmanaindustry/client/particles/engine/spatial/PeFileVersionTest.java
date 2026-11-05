package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PeFileVersionTest {
    @TempDir Path directory;
    static byte[] module(String version,boolean wide){
        var b=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        int pe=0x80,optional=pe+24,table=optional+(wide?240:224),root=0x400,data=0x500;
        b.putShort(0,(short)0x5a4d).putInt(0x3c,pe).putInt(pe,0x4550).putShort(pe+6,(short)1);
        b.putShort(pe+20,(short)(wide?240:224)).putShort(optional,(short)(wide?0x20b:0x10b));
        b.putInt(optional+(wide?112:96)+16,0x1000).putInt(optional+(wide?112:96)+20,0x400);
        b.putInt(table+8,0x400).putInt(table+12,0x1000).putInt(table+16,0x400).putInt(table+20,root);
        for(int offset:new int[]{0,24,48})b.putShort(root+offset+14,(short)1);
        b.putInt(root+16,16).putInt(root+20,0x80000018);
        b.putInt(root+40,1).putInt(root+44,0x80000030);
        b.putInt(root+64,1033).putInt(root+68,72);
        b.putInt(root+72,0x1100).putInt(root+76,92);
        b.putShort(data,(short)92).putShort(data+2,(short)52);
        String key="VS_VERSION_INFO";for(int i=0;i<key.length();i++)b.putChar(data+6+i*2,key.charAt(i));
        var v=java.util.Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
        b.putInt(data+40,0xfeef04bd).putInt(data+44,0x10000).putInt(data+48,(v[0]<<16)|v[1]).putInt(data+52,(v[2]<<16)|v[3]);
        return b.array();
    }
    @Test void readsWindowsVersionResourcesForBothPeFormats() throws IOException {
        assertEquals("12.1.0.0",PeFileVersion.read(module("12.1.0.0",true)));
        assertEquals("65535.2.30.4",PeFileVersion.read(module("65535.2.30.4",false)));
    }
    @Test void bundledModuleMatchesReleaseVersion() throws IOException {
        try(var dll=PeFileVersionTest.class.getResourceAsStream("/native/particle-rt/windows-x86_64/CmiParticleRt.dll")){
            assertNotNull(dll);
            assertEquals(ParticleRtModules.VERSION,PeFileVersion.read(dll.readAllBytes()));
        }
    }
    @Test void rejectsTruncatedOrMalformedResources(){
        assertThrows(IOException.class,()->PeFileVersion.read(new byte[0]));
        var b=module("12.1.0.0",true);b[0]=0;assertThrows(IOException.class,()->PeFileVersion.read(b));
        var missing=module("12.1.0.0",true);ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN).putInt(0x410,15);
        assertThrows(IOException.class,()->PeFileVersion.read(missing));
        var invalid=module("12.1.0.0",true);ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(0x448,0x7fffffff);
        assertThrows(IOException.class,()->PeFileVersion.read(invalid));
    }
    @Test void matchingVersionDoesNotOpenTheBundledDllAgain() throws IOException {
        var opens=new AtomicInteger();
        ParticleRtModules.Resource source=()->{opens.incrementAndGet();return new ByteArrayInputStream(module(ParticleRtModules.VERSION,true));};
        Path first=ParticleRtModules.install(directory,source);long time=Files.getLastModifiedTime(first).toMillis();
        assertEquals(first,ParticleRtModules.install(directory,()->{throw new AssertionError("Matching native module was extracted again");}));
        assertEquals(1,opens.get());assertEquals(time,Files.getLastModifiedTime(first).toMillis());
    }
    @Test void replacementUsesVersionAndRejectsAnIncompatibleBundledDll() throws IOException {
        Path target=directory.resolve(ParticleRtModules.FILE);Files.write(target,module("11.0.0.0",true));
        assertThrows(IOException.class,()->ParticleRtModules.install(directory,()->new ByteArrayInputStream(module("11.0.0.0",true))));
        assertEquals("11.0.0.0",PeFileVersion.read(target));
        ParticleRtModules.install(directory,()->new ByteArrayInputStream(module(ParticleRtModules.VERSION,true)));
        assertEquals(ParticleRtModules.VERSION,PeFileVersion.read(target));
        try(var files=Files.list(directory)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
    }
}
