package com.iridium126.createmanaindustry.client.particles.engine;

/** Render-only playback of the native owner's sealed samples. All times are in 20 Hz ticks. */
public final class NativeStormDisplayClock {
    public record Weights(float oldest,float previous,float current,float anchor) {
        static final Weights CURRENT=new Weights(0,0,1,0);
    }
    public record Transition(boolean continuous,boolean newAnchor,Weights source) {}
    public record View(int texture,int stamp,Weights weights) {}
    private long sequence,worldTick,lastNanos;
    private double renderTick,recoveryAt=Double.NEGATIVE_INFINITY,lastWorld=Double.NaN;
    private boolean held,paused,anchorPending;
    private Weights drawn=Weights.CURRENT;

    public Transition transition(long nextSequence,long nextWorldTick,boolean sameIdentity){
        boolean continuous=sameIdentity&&nextSequence==sequence+1&&nextWorldTick==worldTick+1;
        return new Transition(continuous,sameIdentity&&(!continuous||held),drawn);
    }

    public void commit(long nextSequence,long nextWorldTick,boolean sameIdentity,Transition transition){
        if(!sameIdentity){
            recoveryAt=Double.NEGATIVE_INFINITY;
            drawn=Weights.CURRENT;
            lastWorld=Double.NaN;
            anchorPending=false;
        }else if(transition.newAnchor()){
            anchorPending=true;
            if(!transition.continuous())lastWorld=Double.NaN;
        }
        sequence=nextSequence;worldTick=nextWorldTick;held=false;
    }

    public Weights sample(double world,long nanos,boolean paused){
        if(lastNanos!=0&&!paused&&!this.paused)
            renderTick+=Math.clamp((nanos-lastNanos)/50_000_000.0,0,2);
        lastNanos=nanos;
        this.paused=paused;
        if(!Double.isNaN(lastWorld))world=paused?lastWorld:Math.max(world,lastWorld);
        lastWorld=world;
        double age=2-(world-worldTick);
        held=age<-.001;
        float a=(float)Math.clamp(age,0,2);
        if(anchorPending){recoveryAt=renderTick;anchorPending=false;}
        float blend=(float)Math.clamp((renderTick-recoveryAt)/2,0,1);
        drawn=new Weights(Math.max(0,a-1)*blend,(1-Math.abs(a-1))*blend,Math.max(0,1-a)*blend,1-blend);
        return drawn;
    }
}
