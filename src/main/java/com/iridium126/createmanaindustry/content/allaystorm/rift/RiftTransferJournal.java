package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Workers receive detached NBT only. A successful future means the replacement journal was forced to disk. */
public final class RiftTransferJournal implements AutoCloseable {
    private static final int MAGIC = 0x434d4952, VERSION = 1, MAX_BYTES = 64*1024*1024;
    private final Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("CMI rift journal").factory());
    public RiftTransferJournal(Path directory) { this.directory = directory; }
    public CompletableFuture<Void> write(UUID transaction, String state, CompoundTag snapshot) {
        CompoundTag copy;
        if(state.equals("RELEASED")||state.equals("ROLLED_BACK_RELEASED")) {
            // Native checkpoints own the data now. Keep a small durable decision, not every old inventory forever.
            copy=new CompoundTag();if(snapshot.hasUUID("Group"))copy.putUUID("Group",snapshot.getUUID("Group"));
            for(String key:List.of("Source","Target","Adapter"))if(snapshot.contains(key))copy.putString(key,snapshot.getString(key));
        } else copy=snapshot.copy();
        copy.putUUID("Transaction", transaction);
        copy.putString("State", state);
        CompoundTag detached=copy;
        return CompletableFuture.runAsync(() -> {
            try { writeChecked(directory.resolve(transaction + ".cmi-rift"), encode(detached)); }
            catch (IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }, io);
    }
    public CompletableFuture<List<CompoundTag>> readAll() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isDirectory(directory)) return List.of();
                var records = new ArrayList<CompoundTag>();
                try (var files = Files.list(directory)) {
                    for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".cmi-rift")).sorted().toList()) {
                        if(Files.size(path)>MAX_BYTES+44L)throw new IOException("Oversized transfer journal: "+path.getFileName());
                        records.add(decode(Files.readAllBytes(path)));
                    }
                }
                return List.copyOf(records);
            } catch (IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }, io);
    }
    public java.util.concurrent.Executor executor(){return io;}
    public CompletableFuture<Void> player(Path directory,UUID id,CompoundTag detached) {
        CompoundTag copy=detached.copy();
        return CompletableFuture.runAsync(()->{
            try {
                Files.createDirectories(directory);Path temporary=directory.resolve(id+".cmi-pending"),path=directory.resolve(id+".dat");
                NbtIo.writeCompressed(copy,temporary);
                try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException error){throw new java.util.concurrent.CompletionException(error);}
        },io);
    }
    static byte[] encode(CompoundTag tag) throws IOException {
        var payload = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(new GZIPOutputStream(payload))) { NbtIo.write(tag, data); }
        byte[] bytes = payload.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IOException("Transfer snapshot exceeds journal limit");
        var envelope = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(envelope)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(bytes.length); out.write(hash(bytes)); out.write(bytes);
        }
        return envelope.toByteArray();
    }
    static CompoundTag decode(byte[] envelope) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported transfer journal");
            int length = in.readInt();
            if (length < 0 || length > MAX_BYTES || envelope.length != 44 + length) throw new IOException("Truncated/oversized transfer journal");
            byte[] checksum = in.readNBytes(32), payload = in.readNBytes(length);
            if (!MessageDigest.isEqual(checksum, hash(payload))) throw new IOException("Transfer journal checksum mismatch");
            try (var data = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
                return NbtIo.read(data, NbtAccounter.create(MAX_BYTES));
            }
        }
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void writeChecked(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".pending");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // No non-atomic fallback: a rejected filesystem cannot authorize destructive migration.
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() { io.shutdown(); }
}
