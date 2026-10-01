package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** One native tracker, server thread only. Suppression belongs to a connection, not a UUID.
 * Retain divergence after a lease/world runtime closes until a native position packet rebases
 * that recipient, or unpairing discards its entity. Never alter the shared vanilla codec. */
public final class PackageNativeDownlink<P> {
    public enum Kind { OTHER, ROTATION, VELOCITY, RELATIVE_POSITION, ABSOLUTE_POSITION }
    public enum Action { SEND, DROP, REBASE }
    // The common case is one authority connection. Avoid a Set/table allocation for every
    // one of 131072 trackers; only overlapping old-owner migrations need additional entries.
    private P first;
    private Set<P> more;

    public Action route(P recipient,Kind kind,boolean visibleAuthority) {
        if(kind==Kind.OTHER)return Action.SEND;
        if(visibleAuthority){
            if(first==null)first=recipient;
            else if(first!=recipient){if(more==null)more=Collections.newSetFromMap(new IdentityHashMap<>(2));more.add(recipient);}
            return Action.DROP;
        }
        if(kind==Kind.RELATIVE_POSITION&&(first==recipient||more!=null&&more.contains(recipient)))return Action.REBASE;
        return Action.SEND;
    }
    /** Call after an absolute native packet is successfully enqueued, never on a failed send. */
    public void rebased(P recipient){
        if(first==recipient){
            first=null;
            if(more!=null&&!more.isEmpty()){var iterator=more.iterator();first=iterator.next();iterator.remove();}
        }else if(more!=null)more.remove(recipient);
        if(more!=null&&more.isEmpty())more=null;
    }
    /** Pairing/unpairing establishes a new entity baseline, independent of the previous lease. */
    public void unpaired(P recipient){rebased(recipient);}
    public int pendingRebases(){return (first==null?0:1)+(more==null?0:more.size());}
}
