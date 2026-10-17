package com.iridium126.createmanaindustry.client.particles.packages;

/** Internal SSBO layout, independent of the 1024-byte wire event. Each body has
 * a motion workspace and one inline sample; a page supplies the other 19 slots. */
public record PackageEnvironmentLayout(int capacity) {
    public static final int SAMPLE_BYTES=48,HISTORY=20,PAGE_SAMPLES=HISTORY-1;
    public PackageEnvironmentLayout {
        if(capacity<1||capacity>131072)throw new IllegalArgumentException("Environment layout capacity");
    }
    public int workspaceSample(int body){body(body);return body;}
    public int inlineSample(int body){body(body);return capacity+body;}
    public int allocatorSample(){return capacity*2;}
    public int pendingSample(){return allocatorSample()+1;}
    public int pendingSamples(){return (capacity+127)/128;}
    public int stackSample(){return pendingSample()+pendingSamples();}
    public int stackSamples(){return (capacity+11)/12;}
    public int pageSample(){return stackSample()+stackSamples();}
    public long storageBytes(int pages){
        if(pages<0||pages>capacity)throw new IllegalArgumentException("Environment page capacity");
        return ((long)pageSample()+(long)pages*PAGE_SAMPLES)*SAMPLE_BYTES;
    }
    private void body(int body){if(body<0||body>=capacity)throw new IllegalArgumentException("Environment body");}
}
