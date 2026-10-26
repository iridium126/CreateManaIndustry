package com.iridium126.createmanaindustry.content.allaystorm.rift;

/** Shared across recovery and live transactions for one server tick. Native operations are timed as indivisible units. */
public final class RiftWorkBudget {
    private final long deadline;
    private int loads;
    public RiftWorkBudget(long deadline,int loads){this.deadline=deadline;this.loads=loads;}
    public long deadline(){return deadline;}
    public boolean available(){return System.nanoTime()<deadline;}
    public boolean load(){if(loads<=0||!available())return false;loads--;return true;}
    public int remainingLoads(){return loads;}
}
