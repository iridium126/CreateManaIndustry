package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.util.ArrayDeque;

/** Submission order and resource ownership are independent of simulation age. */
public final class OrderedResultRing {
    public record Ticket(int slot,long nonce,long tick,long identity,int kind) {}
    private final Ticket[] owners;
    private final boolean[] consumed;
    private final ArrayDeque<Ticket> pending=new ArrayDeque<>();
    private long serial,submitted,consumptions,retired;
    public OrderedResultRing(int slots){
        if(slots<1||slots>3)throw new IllegalArgumentException("One to three slots required");
        owners=new Ticket[slots];consumed=new boolean[slots];
    }
    public Ticket acquire(long tick,long identity,int kind){
        for(int i=0;i<owners.length;i++)if(owners[i]==null){
            var ticket=new Ticket(i,++serial,tick,identity,kind);owners[i]=ticket;return ticket;
        }
        return null;
    }
    private void owns(Ticket ticket){
        if(ticket==null||ticket.slot()<0||ticket.slot()>=owners.length||owners[ticket.slot()]!=ticket)
            throw new IllegalStateException("RT ticket ownership");
    }
    public void submit(Ticket ticket){
        owns(ticket);
        if(consumed[ticket.slot()]||pending.contains(ticket))throw new IllegalStateException("Duplicate RT submission");
        pending.addLast(ticket);submitted++;
    }
    public void cancel(Ticket ticket){
        owns(ticket);
        if(consumed[ticket.slot()]||pending.contains(ticket))throw new IllegalStateException("Submitted work must be consumed");
        owners[ticket.slot()]=null;
    }
    public Ticket head(){return pending.peekFirst();}
    public void consume(Ticket ticket){
        owns(ticket);
        if(pending.peekFirst()!=ticket)throw new IllegalStateException("Out of order or duplicate RT consumption");
        pending.removeFirst();consumed[ticket.slot()]=true;consumptions++;
    }
    public void retire(Ticket ticket){
        owns(ticket);
        if(!consumed[ticket.slot()])throw new IllegalStateException("RT slot retired before consumption");
        consumed[ticket.slot()]=false;owners[ticket.slot()]=null;retired++;
    }
    public int pending(){return pending.size();}
    public int occupied(){int n=0;for(var owner:owners)if(owner!=null)n++;return n;}
    public long submitted(){return submitted;}
    public long consumptions(){return consumptions;}
    public long retired(){return retired;}
}
