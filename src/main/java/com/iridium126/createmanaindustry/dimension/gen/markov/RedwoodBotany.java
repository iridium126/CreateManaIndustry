package com.iridium126.createmanaindustry.dimension.gen.markov;

/** Small botanical forms clipped by the original crown/liana density envelope. */
final class RedwoodBotany {
    private static final int SPACING = 12;
    private final int nx, ny, nz;
    private final float[] cosine, sine, jitter;
    private final int seed;

    RedwoodBotany(int width, int depth, int height, int seed) {
        this.seed = seed;
        nx = width / SPACING + 2;
        ny = depth / SPACING + 2;
        nz = height / 8 + 2;
        cosine = new float[nx * ny * nz];
        sine = new float[cosine.length];
        jitter = new float[cosine.length];
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
            int i = x + y * nx + z * nx * ny;
            int h = hash(x, y, z, seed);
            double angle = (h & 65535) * (Math.PI * 2 / 65536);
            cosine[i] = (float)Math.cos(angle);
            sine[i] = (float)Math.sin(angle);
            jitter[i] = ((h >>> 16) / 65535f - .5f) * 2;
        }
    }

    static int hash(int x, int y, int z, int seed) {
        int h = seed ^ x * 0x9E3779B9 ^ y * 0x85EBCA6B ^ z * 0xC2B2AE35;
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B;
        return h ^ (h >>> 16);
    }

    /** Overlapping feather-shaped shoots: a rachis and paired, tapering oblique needles. */
    byte needle(float x, float y, float z, float tone) {
        int bx = (int)Math.floor(x / SPACING - .5f);
        int by = (int)Math.floor(y / SPACING - .5f);
        int bz = (int)Math.floor(z / 8 - .5f);
        byte result = 0;
        for (int c = 0; c < 2; c++) for (int b = 0; b < 2; b++) for (int a = 0; a < 2; a++) {
            int gx = bx + a, gy = by + b, gz = bz + c;
            if (gx < 0 || gy < 0 || gz < 0 || gx >= nx || gy >= ny || gz >= nz) continue;
            int i = gx + gy * nx + gz * nx * ny;
            float dx = x - (gx + .5f) * SPACING - jitter[i];
            float dy = y - (gy + .5f) * SPACING + jitter[i];
            float u = dx * cosine[i] + dy * sine[i];
            float v = -dx * sine[i] + dy * cosine[i];
            float av = Math.abs(v);
            float w = z - (gz + .5f) * 8 - jitter[i] - .16f * u + .12f * av;
            if (Math.abs(u) > 8.5f || Math.abs(w) > 1.35f) continue;
            if (av < .65f && Math.abs(w) < .65f) return 14; // fine woody rachis, t
            float reach = 5.7f * (1 - Math.abs(u) / 10f);
            if (av > reach) continue;
            float along = u - .65f * av;
            float tooth = Math.abs(along - (float)Math.floor(along / 2.8f + .5f) * 2.8f);
            float taper = 1 - av / (reach + .01f);
            if (tooth < .65f + .40f * taper && Math.abs(w) < .85f + .45f * taper)
                result = av > reach * .78f && tone > .55f ? (byte)10
                    : tone < .39f ? (byte)5 : tone > .64f ? (byte)6 : (byte)4;
        }
        return result;
    }

    /** Woody twin helices with alternate pointed leaves and rare flowering nodes. */
    byte vine(float x, float y, float z) {
        int gx = (int)(x / 8), gy = (int)(y / 8);
        int h = hash(gx, gy, 0, seed);
        float phase = (h & 65535) * (float)(Math.PI * 2 / 65536);
        float twist = z * .105f + phase;
        float dx = x - (gx * 8 + 4) - (float)Math.cos(twist) * 1.15f;
        float dy = y - (gy * 8 + 4) - (float)Math.sin(twist) * 1.15f;
        float radius = dx * dx + dy * dy;
        if (radius < 1.65f) return (byte)(radius < .65f ? 11 : 7);
        float secondaryX = dx + (float)Math.cos(twist * 1.7f) * 1.65f;
        float secondaryY = dy + (float)Math.sin(twist * 1.7f) * 1.65f;
        if (secondaryX * secondaryX + secondaryY * secondaryY < .64f) return 7;
        float level = (z + (h >>> 16) % 11) / 11f;
        int node = (int)Math.floor(level);
        float dz = (level - node - .5f) * 11;
        float angle = phase + node * 2.399963f;
        float u = dx * (float)Math.cos(angle) + dy * (float)Math.sin(angle);
        float v = -dx * (float)Math.sin(angle) + dy * (float)Math.cos(angle);
        // Leaves point outward and droop; the tapered ends leave air between nodes.
        float width = 1.45f * (1 - Math.abs(u - 2.1f) / 2.6f);
        if (u > 0 && u < 4.7f && Math.abs(v) < width && Math.abs(dz + .45f * u) < .85f) {
            if ((hash(gx, gy, node, seed) & 31) == 0 && u > 2.5f) return 13;
            return (byte)(u > 2.7f ? 8 : 12);
        }
        return 0;
    }
}
