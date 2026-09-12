package com.iridium126.createmanaindustry.client.dimension.render.sodium;

/** Pure cube/section arithmetic kept independent from Minecraft runtime types. */
public final class AllvrSodiumCoordinateSpace {

    public static final int SECTIONS_PER_CUBE_AXIS = 2;
    public static final int SECTIONS_PER_CUBE = 8;

    private AllvrSodiumCoordinateSpace() {}

    public static int sectionOfBlock(int blockCoord) {
        return blockCoord >> 4;
    }

    public static int cubeOfSection(int sectionCoord) {
        return sectionCoord >> 1;
    }

    public static int cubeOfBlock(int blockCoord) {
        return blockCoord >> 5;
    }

    public static int localSectionInCube(int sectionCoord) {
        return sectionCoord & 1;
    }

    public static int sliceIndexOfSection(int sectionX, int sectionY, int sectionZ) {
        return ((sectionY & 1) << 2) | ((sectionZ & 1) << 1) | (sectionX & 1);
    }

    public static int cubeSection(int cubeCoord, int localIndex) {
        return (cubeCoord << 1) + localIndex;
    }

    public static int localBlockIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int cubeCellIndex(int cubeLocalX, int cubeLocalY, int cubeLocalZ) {
        return (cubeLocalY << 10) | ((cubeLocalZ & 31) << 5) | (cubeLocalX & 31);
    }

    public static int cubeCellX(int cellIndex) {
        return cellIndex & 31;
    }

    public static int cubeCellY(int cellIndex) {
        return (cellIndex >> 10) & 31;
    }

    public static int cubeCellZ(int cellIndex) {
        return (cellIndex >> 5) & 31;
    }

    public static int lightIndex(int x, int y, int z) {
        return localBlockIndex(x, y, z);
    }
}
