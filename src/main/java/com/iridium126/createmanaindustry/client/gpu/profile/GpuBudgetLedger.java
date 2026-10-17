package com.iridium126.createmanaindustry.client.gpu.profile;

/** Five independent measurements. This ledger never makes admission or throttle decisions. */
public final class GpuBudgetLedger {
    public enum Kind { CPU_PREPARE, GPU_SIMULATE_DRAW, UPLOAD, READBACK, QUEUE }
    public record Snapshot(long frame,long cpuNanos,double gpuMillis,long uploadedBytes,long readbackBytes,
                           long accepted,long deferred,long rejected,long backlog) {}
    private static final ThreadLocal<GpuBudgetLedger> CURRENT=new ThreadLocal<>();
    private long frame,cpuNanos,uploadedBytes,readbackBytes,accepted,deferred,rejected,backlog;
    private double gpuMillis;
    private int cpuDepth;
    private long cpuStart;

    public void begin(long token) {
        if(cpuDepth!=0)throw new IllegalStateException("Open CPU budget scope");
        frame=token;cpuNanos=uploadedBytes=readbackBytes=accepted=deferred=rejected=backlog=0;gpuMillis=0;
    }
    /** Nested CPU phases are details of their parent; elapsed time is counted once. */
    public Scope prepare() {
        if(cpuDepth++==0)cpuStart=System.nanoTime();
        return new Scope(this,CURRENT.get());
    }
    public void gpu(double milliseconds) {
        if(!Double.isFinite(milliseconds)||milliseconds<0)throw new IllegalArgumentException("GPU duration");
        gpuMillis+=milliseconds;
    }
    public void queue(long received,long postponed,long refused,long pending) {
        if((received|postponed|refused|pending)<0)throw new IllegalArgumentException("Queue measurement");
        accepted=Math.addExact(accepted,received);deferred=Math.addExact(deferred,postponed);
        rejected=Math.addExact(rejected,refused);backlog=Math.addExact(backlog,pending);
    }
    public Snapshot snapshot() { return new Snapshot(frame,cpuNanos,gpuMillis,uploadedBytes,readbackBytes,accepted,deferred,rejected,backlog); }
    public static void uploaded(long bytes) { var ledger=CURRENT.get();if(ledger!=null)ledger.uploadedBytes=Math.addExact(ledger.uploadedBytes,bytes); }
    public static void downloaded(long bytes) { var ledger=CURRENT.get();if(ledger!=null)ledger.readbackBytes=Math.addExact(ledger.readbackBytes,bytes); }
    public static void queued(long accepted,long deferred,long rejected,long backlog) { var ledger=CURRENT.get();if(ledger!=null)ledger.queue(accepted,deferred,rejected,backlog); }
    public static final class Scope implements AutoCloseable {
        private GpuBudgetLedger ledger;
        private final GpuBudgetLedger previous;
        private Scope(GpuBudgetLedger ledger,GpuBudgetLedger previous) {this.ledger=ledger;this.previous=previous;CURRENT.set(ledger);}
        @Override public void close() {
            var owner=ledger;if(owner==null)throw new IllegalStateException("Budget scope already closed");
            ledger=null;CURRENT.set(previous);
            if(--owner.cpuDepth==0)owner.cpuNanos+=System.nanoTime()-owner.cpuStart;
        }
    }
}
