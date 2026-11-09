package com.iridium126.createmanaindustry.client.particles.engine;

/** Render-only playback of sealed samples. Times use the native input's logical 20 Hz ticks, never world time. */
public final class NativeStormDisplayClock {
    public record Weights(float oldest,float previous,float current,float anchor) {
        static final Weights CURRENT=new Weights(0,0,1,0);
    }
    public record Transition(boolean continuous,boolean newAnchor,Weights source) {}
    public record View(int texture,int stamp,Weights weights) {}
    private long sequence,sampleTick,lastNanos;
    private long previousTick,oldestTick;
    private double renderTick,recoveryAt=Double.NEGATIVE_INFINITY,lastLogical=Double.NaN;
    private boolean held,paused,anchorPending;
    private Weights drawn=Weights.CURRENT;

    public Transition transition(long nextSequence,long nextTick,boolean sameIdentity){
        boolean continuous=sameIdentity&&nextSequence==sequence+1&&nextTick>sampleTick;
        return new Transition(continuous,sameIdentity&&(!continuous||held),drawn);
    }

    public void commit(long nextSequence,long nextTick,boolean sameIdentity,Transition transition){
        if(!sameIdentity){
            recoveryAt=Double.NEGATIVE_INFINITY;
            drawn=Weights.CURRENT;
            lastLogical=Double.NaN;
            anchorPending=false;
        }else if(transition.newAnchor()){
            anchorPending=true;
            if(!transition.continuous())lastLogical=Double.NaN;
        }
        if(transition.continuous()){oldestTick=previousTick;previousTick=sampleTick;}
        else{previousTick=nextTick-1;oldestTick=previousTick;}
        sequence=nextSequence;sampleTick=nextTick;held=false;
    }

    public Weights sample(double logical,long nanos,boolean paused){
        if(lastNanos!=0&&!paused&&!this.paused)
            renderTick+=Math.clamp((nanos-lastNanos)/50_000_000.0,0,2);
        lastNanos=nanos;
        this.paused=paused;
        if(!Double.isNaN(lastLogical))logical=paused?lastLogical:Math.max(logical,lastLogical);
        lastLogical=logical;
        double target=logical-2;
        held=target>sampleTick+.001;
        float oldest=0,previous=0,current=0;
        if(target<=previousTick){
            float a=previousTick>oldestTick?(float)Math.clamp((target-oldestTick)/(previousTick-oldestTick),0,1):1;
            oldest=1-a;previous=a;
        }else{
            float a=sampleTick>previousTick?(float)Math.clamp((target-previousTick)/(sampleTick-previousTick),0,1):1;
            previous=1-a;current=a;
        }
        if(anchorPending){recoveryAt=renderTick;anchorPending=false;}
        float blend=(float)Math.clamp((renderTick-recoveryAt)/2,0,1);
        drawn=new Weights(oldest*blend,previous*blend,current*blend,1-blend);
        return drawn;
    }
    long latestTick(){return sampleTick;}
    boolean held(){return held;}
}
