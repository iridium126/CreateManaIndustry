package com.iridium126.createmanaindustry.client.particles.shaderpack;

/** Only merged package compilation reserves units in an Iris-only installation. */
public final class PackageSamplerReservation implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH=new ThreadLocal<>();
    private final Integer previous;
    private boolean closed;
    private PackageSamplerReservation(int units) {
        previous=DEPTH.get();DEPTH.set(previous==null?units:Math.max(previous,units));
    }
    public static PackageSamplerReservation open(){return open(3);}
    public static PackageSamplerReservation open(int units){if(units<1||units>4)throw new IllegalArgumentException("CMI sampler reservation");return new PackageSamplerReservation(units);}
    public static int units(){return DEPTH.get()==null?0:DEPTH.get();}
    public static boolean active(){return DEPTH.get()!=null;}
    @Override public void close(){if(!closed){closed=true;if(previous==null)DEPTH.remove();else DEPTH.set(previous);}}
}
