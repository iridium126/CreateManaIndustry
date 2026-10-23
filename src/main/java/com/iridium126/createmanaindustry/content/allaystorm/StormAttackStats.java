package com.iridium126.createmanaindustry.content.allaystorm;

/** Numeric counters only; formatting happens when an operator requests status. */
public final class StormAttackStats {
    public enum Reason { LAUNCHED, DRAW_MISSED, TOO_FEW, RESERVED, CAPACITY, RESOURCE, GATHER_TIMEOUT, BODY_OVERLAP }
    private final long[] counts=new long[Reason.values().length];
    public void count(Reason reason){counts[reason.ordinal()]++;}
    public long get(Reason reason){return counts[reason.ordinal()];}
    @Override public String toString(){var out=new StringBuilder();for(var reason:Reason.values())out.append(reason).append('=').append(get(reason)).append(' ');return out.toString();}
}
