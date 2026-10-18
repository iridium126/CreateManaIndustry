package com.iridium126.createmanaindustry.compat.hextrick.data;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** A nested decode inherits its parent's allowance, including nested compression. */
public final class DecodeBudget implements AutoCloseable {
    public static final int MAX_ENCODED = 2 * 1024 * 1024;
    public static final int MAX_DECODED = 8 * 1024 * 1024;
    public static final int MAX_DEPTH = 256;
    public static final int MAX_NODES = 64000;
    private static final ThreadLocal<DecodeBudget> CURRENT = new ThreadLocal<>();
    private final DecodeBudget root;
    private final boolean owner;
    private int decoded;
    private int nodes;
    private int depth;
    private boolean exhausted;
    private String failureReason;

    private DecodeBudget() {
        DecodeBudget previous = CURRENT.get();
        owner = previous == null;
        root = owner ? this : previous;
        if (owner) CURRENT.set(this);
    }

    public static DecodeBudget enter() { return new DecodeBudget(); }
    public static DecodeBudget current() { return CURRENT.get(); }
    public static ByteArrayOutputStream encodedOutput(int headerBytes) {
        int maximum = MAX_ENCODED + headerBytes;
        return new ByteArrayOutputStream() {
            private void require(int length) {
                if (length < 0 || length > maximum - count) throw new DecodeFailure("encode_limit");
            }
            @Override public synchronized void write(int value) { require(1); super.write(value); }
            @Override public synchronized void write(byte[] bytes, int start, int length) {
                require(length); super.write(bytes, start, length);
            }
        };
    }
    public void check() { if (root.exhausted) fail(root.failureReason); }
    public static void encoded(int length) {
        if (length < 0 || length > MAX_ENCODED) throw new DecodeFailure("encoded_size");
    }
    public void bytes(int length) {
        if (root.exhausted || length < 0 || length > MAX_DECODED - root.decoded) fail("decoded_size");
        root.decoded += length;
    }
    public void collection(int length) {
        if (root.exhausted || length < 0 || length > MAX_NODES - root.nodes) fail("collection_size");
    }
    public void push() {
        if (root.exhausted || root.nodes >= MAX_NODES || root.depth >= MAX_DEPTH) fail("structure_limit");
        root.nodes++;
        root.depth++;
    }
    public void pop() { root.depth--; }
    private void fail(String reason) {
        root.exhausted = true;
        if (root.failureReason == null) root.failureReason = reason;
        throw new DecodeFailure(root.failureReason);
    }

    public InputStream counting(InputStream input) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException { int value = super.read(); if (value >= 0) bytes(1); return value; }
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                int count = in.read(bytes, start, length);
                if (count > 0) DecodeBudget.this.bytes(count);
                return count;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = super.skip(count);
                if (skipped > Integer.MAX_VALUE) fail("decoded_size");
                bytes((int) skipped);
                return skipped;
            }
        };
    }

    @Override public void close() { if (owner) CURRENT.remove(); }

    public static final class DecodeFailure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public enum Code { ENCODED_LIMIT, DECOMPRESSED_LIMIT, STRUCTURE_LIMIT, UNSUPPORTED_PROTOCOL, TYPE_MISMATCH, CORRUPT_DATA }
        private final Code code;
        public DecodeFailure(String reason) { this(reason, null); }
        public DecodeFailure(String reason, Throwable cause) {
            super("Invalid bridge data: " + reason, cause);
            code = cause instanceof DecodeFailure nested ? nested.code : switch (reason) {
                case "encoded_size", "base64_size", "encode_limit" -> Code.ENCODED_LIMIT;
                case "decoded_size" -> Code.DECOMPRESSED_LIMIT;
                case "structure_limit", "collection_size", "fragment_graph_limit", "fragment_cycle" -> Code.STRUCTURE_LIMIT;
                case "unsupported_protocol", "unsupported_gzip_header" -> Code.UNSUPPORTED_PROTOCOL;
                case "not_a_spell" -> Code.TYPE_MISMATCH;
                default -> Code.CORRUPT_DATA;
            };
        }
        public Code code() { return code; }
    }
}
