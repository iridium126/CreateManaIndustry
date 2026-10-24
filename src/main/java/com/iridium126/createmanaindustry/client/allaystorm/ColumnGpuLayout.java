package com.iridium126.createmanaindustry.client.allaystorm;

/** Internal GPU record only; the network snapshot remains thirty floats. */
public final class ColumnGpuLayout {
    private ColumnGpuLayout() {}
    public static final int WORDS=96, SAMPLES=128, NODES=65;
    public static final int ORIGIN=33, DIRECTION=36, TOKEN=39, TANGENT=40,
            PHASE=43, CATCHUP=44, BLOOM_RADIUS=45, BLOOM_ANGLE=46,
            RECOVER_POS=80, RECOVER_VEL=83, RECOVER_GENERATION=86;
    public static int node(int logical){
        if(logical<0||logical>=NODES)throw new IndexOutOfBoundsException(logical);
        return logical<33?logical:logical+15;
    }
    public static String glsl(){
        return "#define CMI_COLUMN_ARC_WORDS "+WORDS+"u\n"
                +"#define CMI_COLUMN_ARC_SAMPLES "+SAMPLES+"u\n"
                +"#define CMI_COLUMN_ARC_NODES "+NODES+"u\n"
                +"#define CMI_COLUMN_ORIGIN "+ORIGIN+"u\n"
                +"#define CMI_COLUMN_DIRECTION "+DIRECTION+"u\n"
                +"#define CMI_COLUMN_TOKEN "+TOKEN+"u\n"
                +"#define CMI_COLUMN_TANGENT "+TANGENT+"u\n"
                +"#define CMI_COLUMN_PHASE "+PHASE+"u\n"
                +"#define CMI_COLUMN_CATCHUP "+CATCHUP+"u\n"
                +"#define CMI_COLUMN_BLOOM_RADIUS "+BLOOM_RADIUS+"u\n"
                +"#define CMI_COLUMN_BLOOM_ANGLE "+BLOOM_ANGLE+"u\n"
                +"#define CMI_COLUMN_RECOVER_POS "+RECOVER_POS+"u\n"
                +"#define CMI_COLUMN_RECOVER_VEL "+RECOVER_VEL+"u\n"
                +"#define CMI_COLUMN_RECOVER_GENERATION "+RECOVER_GENERATION+"u\n";
    }
}
