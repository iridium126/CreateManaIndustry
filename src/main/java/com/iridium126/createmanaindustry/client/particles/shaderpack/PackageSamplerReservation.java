package com.iridium126.createmanaindustry.client.particles.shaderpack;

/** Only merged package compilation reserves units in an Iris-only installation. */
public final class PackageSamplerReservation implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH=new ThreadLocal<>();
    private final Integer previous;
    private boolean closed;
    private PackageSamplerReservation() {
        previous=DEPTH.get();DEPTH.set(previous==null?1:Math.incrementExact(previous));
    }
    public static PackageSamplerReservation open(){return new PackageSamplerReservation();}
    public static boolean active(){return DEPTH.get()!=null;}
    @Override public void close(){if(!closed){closed=true;if(previous==null)DEPTH.remove();else DEPTH.set(previous);}}
}
