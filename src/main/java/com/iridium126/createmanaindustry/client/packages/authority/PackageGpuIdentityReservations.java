package com.iridium126.createmanaindustry.client.packages.authority;


import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Stable identities may acquire a new candidate only after the old lifetime is retired.
 * Candidate reuse requires all old admissions, flights and GPU references to be drained. */
public final class PackageGpuIdentityReservations {
    private final Map<PackageLease.Identity,Integer> live=new HashMap<>();
    private final BitSet retired=new BitSet();
    public boolean contains(long id,long generation){return live.containsKey(new PackageLease.Identity(id,generation));}
    public void reserve(long id,long generation,int candidate) {
        if(candidate<0 || retired.get(candidate) || live.putIfAbsent(new PackageLease.Identity(id,generation),candidate)!=null)
            throw new IllegalArgumentException("Duplicate live package identity or retired candidate");
    }
    public void retire(long id,long generation,int candidate) {
        if(candidate<0 || !live.remove(new PackageLease.Identity(id,generation),candidate))
            throw new IllegalArgumentException("Package identity retirement does not match its candidate");
        retired.set(candidate);
    }
    public void reclaim(int candidate){if(!retired.get(candidate))throw new IllegalArgumentException("Candidate is not retired");retired.clear(candidate);}
    public boolean retired(int candidate){return retired.get(candidate);}
    public void clear(){live.clear();retired.clear();}
}
