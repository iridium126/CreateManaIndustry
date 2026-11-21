package com.iridium126.createmanaindustry.client.dlss;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.lwjgl.system.Platform;

final class DlssNative {
    static final int SLOTS = 3;
    private static final String ROOT = "/native/dlss/windows-x86_64/";
    private static final String SDK = "310.6.0";
    private static final String BRIDGE = "CmiDlssSr.dll";
    private static final List<String> LIBRARIES = List.of("nvngx_dlss.dll", BRIDGE);
    private static Path installed;

    private DlssNative() {}

    static synchronized Path load(Path gameDir) throws IOException {
        if (installed != null) return installed;
        if (Platform.get() != Platform.WINDOWS || !System.getProperty("os.arch", "").matches("(?i)amd64|x86_64"))
            throw new IOException("DLSS requires Windows x64");
        Properties release = new Properties();
        try (InputStream source = asset("release.properties")) {
            release.load(source);
        }
        if (!SDK.equals(release.getProperty("sdk"))) throw new IOException("Unexpected DLSS SDK version");
        MessageDigest digest = sha256();
        for (String library : LIBRARIES) digest.update(checksum(release, library));
        Path path = gameDir.toAbsolutePath().resolve(".cache/cmi-dlss").resolve(HexFormat.of().formatHex(digest.digest()));
        Files.createDirectories(path);
        try (var file = FileChannel.open(path.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = file.lock()) {
            for (String library : LIBRARIES) install(path, library, checksum(release, library));
        }
        System.load(path.resolve(BRIDGE).toString());
        installed = path;
        return path;
    }

    private static void install(Path directory, String name, byte[] expected) throws IOException {
        Path file = directory.resolve(name);
        if (Files.isRegularFile(file)) {
            try (var input = new DigestInputStream(Files.newInputStream(file), sha256())) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
                if (MessageDigest.isEqual(expected, input.getMessageDigest().digest())) return;
            }
        }
        Path temp = Files.createTempFile(directory, "install", ".tmp");
        try (var input = new DigestInputStream(asset(name), sha256())) {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            if (!MessageDigest.isEqual(expected, input.getMessageDigest().digest())) throw new IOException("Invalid DLSS component " + name);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] checksum(Properties release, String library) throws IOException {
        String value = release.getProperty(library, "");
        if (!value.matches("[0-9a-f]{64}")) throw new IOException("Missing DLSS checksum for " + library);
        return HexFormat.of().parseHex(value);
    }

    private static InputStream asset(String name) throws IOException {
        InputStream source = DlssNative.class.getResourceAsStream(ROOT + name);
        if (source == null) throw new IOException("Missing DLSS component " + name);
        return source;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static native long create(long luid, int width, int height, int outputWidth, int outputHeight,
                              boolean hdr, String runtimePath, String cachePath);
    static native void destroy(long session);
    static native void exportTextures(long session, int slot, long[] output);
    static native void closeHandle(long handle);
    static native boolean available(long session, int slot);
    static native void evaluate(long session, int slot, long token, float jitterX, float jitterY, float elapsedMs,
                                boolean reset, boolean profile);
    static native double pollGpuTime(long session, int slot);
}
