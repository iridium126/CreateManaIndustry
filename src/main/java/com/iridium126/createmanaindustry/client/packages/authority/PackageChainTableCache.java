package com.iridium126.createmanaindustry.client.packages.authority;


import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;

/** Track headers outlive a GPU resource restart, but never a connection or closed epoch. */
final class PackageChainTableCache {
    private final ArrayList<ClientboundChainPackagePacket.Track> tracks=new ArrayList<>();
    private long epoch,closedThrough;
    boolean track(long incoming,ClientboundChainPackagePacket.Track track) {
        if(incoming<=closedThrough||incoming<epoch)return false;
        if(incoming>epoch) {
            if(track.index()!=0)throw new IllegalStateException("Chain table index order: received="+track.index()+", expected=0");
            epoch=incoming;tracks.clear();
        }
        if(track.index()<tracks.size()){
            if(!tracks.get(track.index()).equals(track))throw new IllegalStateException("Conflicting chain table namespace");
            return true;
        }
        if(track.index()!=tracks.size())throw new IllegalStateException("Chain table index order: received="+track.index()+", expected="+tracks.size());
        if(tracks.size()==131072)throw new IllegalStateException("Chain table capacity reached: capacity=131072");
        tracks.add(track);return true;
    }
    boolean current(long incoming){return incoming==epoch&&incoming>closedThrough&&!tracks.isEmpty();}
    void close(long incoming){closedThrough=Math.max(closedThrough,incoming);if(epoch<=closedThrough)tracks.clear();}
    List<ClientboundChainPackagePacket.Track> table(long incoming){return current(incoming)?List.copyOf(tracks):List.of();}
}
