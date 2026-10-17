package com.iridium126.createmanaindustry.client.gpu.frame;

/** Fixed render-thread transaction. It coordinates publication, never advances a business clock. */
public final class GpuFrameCoordinator {
    public enum Phase { IDLE, INPUT, SIMULATION, GENERATION, CULL_SORT, PUBLISHED, DRAWING, DONE }
    /** Buffer names and versions are borrowed for this render scope; ownership stays with their domains. */
    public record Publication(long frame,long generation,int pool,int permutation,int commands,int counter,
                              int population,long populationGeneration,long stormGeneration,long packageGeneration) {
        public Publication {
            if(frame<=0||generation<=0||pool<=0||permutation<0||commands<=0||counter<=0||population<=0)
                throw new IllegalArgumentException("Incomplete GPU frame publication");
            if(populationGeneration!=generation||stormGeneration!=-1&&stormGeneration!=generation
                    ||packageGeneration!=-1&&packageGeneration!=generation)
                throw new IllegalArgumentException("Mixed GPU frame generations");
        }
    }
    private long frame;
    private Phase phase=Phase.IDLE;
    private Publication published;

    public void begin() { frame++;published=null;phase=Phase.INPUT; }
    public void simulate() { advance(Phase.INPUT,Phase.SIMULATION); }
    public void generate() { advance(Phase.SIMULATION,Phase.GENERATION); }
    public void cullSort() { advance(Phase.GENERATION,Phase.CULL_SORT); }

    public void publish(long generation,int pool,int permutation,int commands,int counter,int population,
                        long populationGeneration,long stormGeneration,long packageGeneration) {
        require(Phase.CULL_SORT);
        // Validation precedes the readiness transition. A rejected tuple cannot expose an older frame.
        published=new Publication(frame,generation,pool,permutation,commands,counter,population,
                populationGeneration,stormGeneration,packageGeneration);
        phase=Phase.PUBLISHED;
    }

    public Publication publication() { return phase==Phase.PUBLISHED||phase==Phase.DRAWING?published:null; }
    public void draw() { advance(Phase.PUBLISHED,Phase.DRAWING); }
    public void finishDraw() { advance(Phase.DRAWING,Phase.DONE);published=null; }
    public void abort() { published=null;phase=Phase.IDLE; }
    public Phase phase() { return phase; }
    private void advance(Phase expected,Phase next) { require(expected);phase=next; }
    private void require(Phase expected) {
        if(phase!=expected)throw new IllegalStateException("GPU frame phase "+phase+"; expected "+expected);
    }
}
