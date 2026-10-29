package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.*;
import java.util.function.IntPredicate;

/** Server admission only. Positions, flight and arrival are GPU-owned. */
public final class StormOuterLoop {
    public record Flight(int member,int cycle,float x,float y,float z) {}
    private final ArrayDeque<Integer> queue=new ArrayDeque<>();
    private final BitSet queued=new BitSet();
    private final LinkedHashMap<Integer,Flight> flights=new LinkedHashMap<>();
    private double rate,credit;
    private int limit,serial;
    public void configure(int generated,int maximum,int alive,double growth) {
        rate=generated>=maximum?Math.clamp(growth,0,2048):0;
        limit=(int)Math.min(Math.max(0,alive),Math.ceil(rate*10));
    }
    public boolean offer(int member,BitSet reserved) {
        if(rate<=0||queue.size()>=limit||queued.get(member)||flights.containsKey(member))return false;
        queue.addLast(member);queued.set(member);reserved.set(member);return true;
    }
    public List<Flight> tick(IntPredicate living,BitSet reserved,float x,float y,float z,float radius) {
        if(queue.isEmpty()){credit=0;return List.of();}
        credit=Math.min(Math.max(1,rate),credit+rate/20.);
        var out=new ArrayList<Flight>();
        while(credit>=1&&!queue.isEmpty()){
            int member=queue.removeFirst();queued.clear(member);
            if(!living.test(member)){reserved.clear(member);continue;}
            credit-=1;
            if(serial==Integer.MAX_VALUE){reserved.clear(member);continue;}
            int cycle=++serial;
            float angle=member*2.399963f,r=120+radius;
            var flight=new Flight(member,cycle,x+(float)Math.cos(angle)*r,y,z+(float)Math.sin(angle)*r);
            flights.put(member,flight);out.add(flight);
        }
        if(queue.isEmpty())credit=0;
        return out;
    }
    public boolean arrived(int member,int cycle,BitSet reserved) {
        var flight=flights.get(member);
        if(flight==null||flight.cycle()!=cycle)return false;
        flights.remove(member);reserved.clear(member);return true;
    }
    public Flight dead(int member,BitSet reserved) {
        if(queued.get(member)){queued.clear(member);queue.remove(member);reserved.clear(member);}
        var old=flights.remove(member);if(old!=null)reserved.clear(member);return old;
    }
    public Collection<Flight> flights(){return Collections.unmodifiableCollection(flights.values());}
    public int waiting(){return queue.size();}
    public void clear(){queue.clear();queued.clear();flights.clear();rate=credit=0;limit=0;}
}
