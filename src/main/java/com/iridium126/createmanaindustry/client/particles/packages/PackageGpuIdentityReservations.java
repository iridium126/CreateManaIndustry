package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Stable identities may acquire a new candidate only after the old lifetime is retired.
 * Candidate reuse requires all old admissions, flights and GPU references to be drained. */
final class PackageGpuIdentityReservations {
    private final Map<PackageLease.Identity,Integer> live=new HashMap<>();
    private final BitSet retired=new BitSet();
    boolean contains(long id,long generation){return live.containsKey(new PackageLease.Identity(id,generation));}
    void reserve(long id,long generation,int candidate) {
        if(candidate<0 || retired.get(candidate) || live.putIfAbsent(new PackageLease.Identity(id,generation),candidate)!=null)
            throw new IllegalArgumentException("Duplicate live package identity or retired candidate");
    }
    void retire(long id,long generation,int candidate) {
        if(candidate<0 || !live.remove(new PackageLease.Identity(id,generation),candidate))
            throw new IllegalArgumentException("Package identity retirement does not match its candidate");
        retired.set(candidate);
    }
    void reclaim(int candidate){if(!retired.get(candidate))throw new IllegalArgumentException("Candidate is not retired");retired.clear(candidate);}
    boolean retired(int candidate){return retired.get(candidate);}
    void clear(){live.clear();retired.clear();}
}
