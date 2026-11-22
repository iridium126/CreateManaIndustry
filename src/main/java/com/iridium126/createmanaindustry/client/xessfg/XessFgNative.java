package com.iridium126.createmanaindustry.client.xessfg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.lwjgl.system.Platform;

final class XessFgNative {
    static final int GEOMETRY_SLOTS = 4, SLOT_COUNT = GEOMETRY_SLOTS + 1;
    static final int PRODUCER_BACKPRESSURE = -2;
    static final int GEOMETRY = 0, COLOR = 1, HUD_BASE = 2;
    static final int GPU_PROFILE_FIELDS = 22, CLOCK_PROFILE_FIELDS = 10, PRESENT_RESULT_CAPACITY = SLOT_COUNT * 4;
    static final int GPU_HUD_COPY_ROUTE = 19;
    static final int CONSTANT_BYTES = 152;
    static final int REPROJECTION_BYTES = 144;
    private static final String ROOT = "/native/xessfg/windows-x86_64/";
    private static final String[] LIBRARIES = {"libxell.dll", "libxess_fg.dll", "CmiXessFg.dll"};
    private static boolean loaded;

    private XessFgNative() {}

    static synchronized void load(Path gameDir) throws IOException {
        if (loaded) return;
        if (Platform.get() != Platform.WINDOWS || !System.getProperty("os.arch", "").matches("(?i)amd64|x86_64")) {
            throw new IOException("XeSS-FG requires Windows x64");
        }
        byte[][] binaries = new byte[LIBRARIES.length][];
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (int i = 0; i < LIBRARIES.length; i++) {
            try (InputStream input = XessFgNative.class.getResourceAsStream(ROOT + LIBRARIES[i])) {
                if (input == null) throw new IOException("XeSS-FG component is missing: " + LIBRARIES[i]);
                binaries[i] = input.readAllBytes();
            }
            digest.update(binaries[i]);
        }
        Path directory = gameDir.toAbsolutePath().resolve(".cache/cmi-xessfg").resolve(HexFormat.of().formatHex(digest.digest()));
        Files.createDirectories(directory);
        try (var lockFile = FileChannel.open(directory.resolve("install.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = lockFile.lock()) {
            for (int i = 0; i < LIBRARIES.length; i++) {
                Path target = directory.resolve(LIBRARIES[i]);
                if (Files.exists(target) && MessageDigest.isEqual(digest.digest(Files.readAllBytes(target)),
                        digest.digest(binaries[i]))) continue;
                Path temporary = Files.createTempFile(directory, "install", ".tmp");
                try {
                    Files.write(temporary, binaries[i]);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
        for (String library : LIBRARIES) System.load(directory.resolve(library).toString());
        loaded = true;
    }

    static native int adapter(long luid);
    static native long create(long hwnd, long renderLuid, long generationLuid, int width, int height, int transport,
                              boolean topLevel, int geometryWidth, int geometryHeight);
    static native long[] exportGroup(long session, int slot, int group);
    static native void closeHandle(long handle);
    static native int admission(long session, boolean gameplay);
    static native int acquire(long session, long sequence, long captureFrame, boolean colorOnly);
    static native int slotsInUse(long session);
    static native void waitForSlot(long session, boolean colorOnly);
    static native void waitForProducer(long session, boolean colorOnly);
    static native void submitGeometry(long session, int slot, long sequence, ByteBuffer reprojection, boolean overrides);
    static native void submitHudless(long session, int slot, long sequence);
    static native void submitColor(long session, int slot, long sequence, boolean full);
    static native boolean discard(long session, int slot, long sequence);
    static native int sleep(long session, int frameId);
    static native void marker(long session, int frameId, int marker);
    static native boolean enqueuePresent(long session, int slot, int frameId, long sequence, boolean interpolate,
                                         boolean vsync, ByteBuffer constants, long captureFrame);
    static native void waitForPresentSpace(long session);
    static native int pollPresents(long session, ByteBuffer output);
    static native int pollGpuProfile(long session, ByteBuffer output);
    static native void sampleClocks(long session, ByteBuffer output);
    static native void destroy(long session);
}
