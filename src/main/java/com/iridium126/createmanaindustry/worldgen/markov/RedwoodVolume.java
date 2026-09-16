package com.iridium126.createmanaindustry.worldgen.markov;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Sparse 16^3 pages; empty space in the 1.29 billion voxel canvas costs no voxel storage. */
public final class RedwoodVolume {
    public final int x, y, z;
    private final int nx, ny;
    private final byte[][] pages;

    public RedwoodVolume(int x, int y, int z) {
        this.x = x; this.y = y; this.z = z;
        nx = (x + 15) / 16; ny = (y + 15) / 16;
        pages = new byte[nx * ny * ((z + 15) / 16)][];
    }

    public byte get(int a, int b, int c) {
        if (a < 0 || b < 0 || c < 0 || a >= x || b >= y || c >= z) return 0;
        byte[] page = pages[(a >> 4) + (b >> 4) * nx + (c >> 4) * nx * ny];
        return page == null ? 0 : page[(a & 15) + (b & 15) * 16 + (c & 15) * 256];
    }

    // Writers must own a whole Z page layer. Published volumes are read-only.
    void set(int a, int b, int c, byte value) {
        int p = (a >> 4) + (b >> 4) * nx + (c >> 4) * nx * ny;
        byte[] page = pages[p];
        if (page == null) {
            if (value == 0) return;
            pages[p] = page = new byte[4096];
        }
        page[(a & 15) + (b & 15) * 16 + (c & 15) * 256] = value;
    }

    static RedwoodVolume from(byte[] state, int x, int y, int z) {
        RedwoodVolume result = new RedwoodVolume(x, y, z);
        for (int c = 0, i = 0; c < z; c++) for (int b = 0; b < y; b++) for (int a = 0; a < x; a++, i++)
            if (state[i] != 0) result.set(a, b, c, state[i]);
        return result;
    }

    public boolean intersects(int a, int b, int c, int size) {
        if (a >= x || b >= y || c >= z || a + size <= 0 || b + size <= 0 || c + size <= 0) return false;
        for (int pz = Math.max(0, c) >> 4; pz <= Math.min(z - 1, c + size - 1) >> 4; pz++)
            for (int py = Math.max(0, b) >> 4; py <= Math.min(y - 1, b + size - 1) >> 4; py++)
                for (int px = Math.max(0, a) >> 4; px <= Math.min(x - 1, a + size - 1) >> 4; px++)
                    if (pages[px + py * nx + pz * nx * ny] != null) return true;
        return false;
    }

    /** Same first occupied voxel and six-neighbor flood as RedwoodRefinement.cs. */
    void retainRoot() {
        int first = Integer.MAX_VALUE;
        for (int p = 0; p < pages.length; p++) {
            byte[] page = pages[p];
            if (page == null) continue;
            int a = (p % nx) * 16, b = (p / nx % ny) * 16, c = (p / (nx * ny)) * 16;
            for (int i = 0; i < 4096; i++) if (page[i] != 0) {
                first = Math.min(first, a + (i & 15) + (b + (i >> 4 & 15)) * x + (c + (i >> 8)) * x * y);
                break;
            }
        }
        if (first == Integer.MAX_VALUE) return;
        IntQueue queue = new IntQueue();
        visit(first % x, first / x % y, first / (x * y), queue);
        while (!queue.empty()) {
            int index = queue.remove(), a = index % x, b = index / x % y, c = index / (x * y);
            if (a > 0) visit(a - 1, b, c, queue);
            if (a + 1 < x) visit(a + 1, b, c, queue);
            if (b > 0) visit(a, b - 1, c, queue);
            if (b + 1 < y) visit(a, b + 1, c, queue);
            if (c > 0) visit(a, b, c - 1, queue);
            if (c + 1 < z) visit(a, b, c + 1, queue);
        }
        for (int p = 0; p < pages.length; p++) {
            byte[] page = pages[p];
            if (page == null) continue;
            boolean any = false;
            for (int i = 0; i < page.length; i++) {
                page[i] = page[i] < 0 ? (byte)(page[i] & 127) : 0;
                any |= page[i] != 0;
            }
            if (!any) pages[p] = null;
        }
    }

    private void visit(int a, int b, int c, IntQueue queue) {
        byte value = get(a, b, c);
        if (value <= 0) return;
        set(a, b, c, (byte)(value | 128));
        queue.add(a + b * x + c * x * y);
    }

    public long allocatedBytes() { return Arrays.stream(pages).filter(p -> p != null).count() * 4096L; }

    /** Canonical upstream X-fastest byte stream, including every air voxel. */
    public void writeTo(OutputStream output) throws IOException {
        byte[] row = new byte[x];
        for (int c = 0; c < z; c++) for (int b = 0; b < y; b++) {
            for (int a = 0; a < x; a++) row[a] = get(a, b, c);
            output.write(row);
        }
    }

    private static final class IntQueue {
        private int[] data = new int[16384];
        private int head, size;
        boolean empty() { return size == 0; }
        int remove() { int result = data[head]; head = (head + 1) & (data.length - 1); size--; return result; }
        void add(int value) {
            if (size == data.length) {
                int[] expanded = new int[data.length * 2];
                for (int i = 0; i < size; i++) expanded[i] = data[(head + i) & (data.length - 1)];
                data = expanded; head = 0;
            }
            data[(head + size++) & (data.length - 1)] = value;
        }
    }
}
