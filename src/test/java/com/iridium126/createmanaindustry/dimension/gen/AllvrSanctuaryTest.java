package com.iridium126.createmanaindustry.dimension.gen;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

class AllvrSanctuaryTest {
    @Test void platformCalmLandAndBlendHaveNoBoundaryStep() {
        for (int original : new int[]{-30, 63, 95, 180, 300}) {
            for (int z = -90; z <= 90; z++) for (int x = -90; x <= 90; x++)
                if (Math.hypot(x, z) <= 90) assertEquals(95, AllvrSanctuary.surface(x, z, original));
            for (int x = 0; x <= 500; x++)
                assertTrue(Math.abs(AllvrSanctuary.surface(x, 0, original) - 95) <= 4);
            assertEquals(original, AllvrSanctuary.surface(700, 0, original));
            assertEquals(original, AllvrSanctuary.surface(701, 0, original));
            for (int boundary : new int[]{90, 220, 500, 700})
                assertTrue(Math.abs(AllvrSanctuary.surface(boundary - 1, 0, original)
                    - AllvrSanctuary.surface(boundary + 1, 0, original)) <= 1);
        }
    }

    @Test void threeDimensionalRoutesHaveSlopesSagAndAnchoredLowerRoots() {
        for(long seed:new long[]{0,137,-1}) {
            var n=new SanctuaryNetwork(seed);
            assertEquals(6,n.routes().stream().filter(r->r.kind().equals("wall-bridge")).count());
            assertEquals(4,n.routes().stream().filter(r->r.kind().equals("root-bridge")).count());
            assertEquals(8,n.routes().stream().filter(r->r.kind().equals("ramp")).count());
            assertEquals(10,n.routes().stream().filter(r->r.kind().equals("entrance")).count());
            assertEquals(2,n.geodes().size());
            assertEquals(10,n.roots().size());
            for(var root:n.roots()) {
                var first=root.points().getFirst();var last=root.points().getLast();
                assertTrue(Math.hypot(first.x(),first.z())<90);
                assertTrue(Math.hypot(last.x(),last.z())>214);
                assertTrue(first.y()>last.y()+30);
                assertTrue(first.y()+root.initialRadius()<45,"Root must occupy the lower half");
            }
            for(var r:n.routes()) {
                var points=r.points();
                if(r.kind().contains("bridge")) {
                    double ends=(points.getFirst().y()+points.getLast().y())/2;
                    assertTrue(ends-points.get(points.size()/2).y()>=3.9,"Bridge lacks sag");
                }
                for(int i=1;i<points.size();i++) {
                    var a=points.get(i-1);var b=points.get(i);
                    double distance=Math.hypot(a.x()-b.x(),a.z()-b.z());
                    assertTrue(Math.abs(a.y()-b.y())<=distance*.8+.01,"Unwalkable slope in "+r.kind());
                }
            }
        }
    }

    @Test void actualWalkwayVoxelsAreReachableAndHaveHeadroom() {
        for(long seed:new long[]{0,42,137,-1}) verifyReachable(new SanctuaryNetwork(seed));
    }
    private static void verifyReachable(SanctuaryNetwork n) {
        // Construct a graph of actual floor blocks. Half-slabs and full steps are distinct surfaces.
        var floors=new java.util.HashMap<Long,Double>();
        n.forEach((x,y,z,m)->{
            if(m<SanctuaryNetwork.PATH || m>SanctuaryNetwork.DECK_SLAB) return;
            int above=n.get(x,y+1,z),head=n.get(x,y+2,z);
            if(above!=SanctuaryNetwork.CLEAR || head!=SanctuaryNetwork.CLEAR) return;
            floors.put(key(x,y,z),y+(m==SanctuaryNetwork.PATH_SLAB || m==SanctuaryNetwork.DECK_SLAB?.5:1));
        });
        var queue=new ArrayDeque<Long>();var seen=new java.util.HashSet<Long>();
        var first=n.routes().stream().filter(r->r.kind().equals("entrance")).findFirst().orElseThrow().points().getFirst();
        long start=nearest(floors,first);queue.add(start);seen.add(start);
        while(!queue.isEmpty()) {
            long current=queue.remove();int x=(int)(current>>40),y=(int)((current>>20)&0xfffff)-512,z=(int)(current&0xfffff)-512;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int dy=-1;dy<=1;dy++) {
                long next=key(x+d[0],y+dy,z+d[1]);
                if(floors.containsKey(next) && Math.abs(floors.get(current)-floors.get(next))<=1 && seen.add(next)) queue.add(next);
            }
        }
        for(var r:n.routes()) {
            assertTrue(seen.contains(nearest(floors,r.points().getFirst())),"Unreachable start of "+r.kind()+" "+r.points().getFirst()+" reached="+seen.size()+"/"+floors.size());
            assertTrue(seen.contains(nearest(floors,r.points().get(r.points().size()/2))),"Unreachable middle of "+r.kind());
            assertTrue(seen.contains(nearest(floors,r.points().getLast())),"Unreachable end of "+r.kind());
        }
    }
    private static long key(int x,int y,int z) { return ((long)x<<40)|((long)(y+512)<<20)|(z+512); }
    private static long nearest(java.util.Map<Long,Double> floors,SanctuaryNetwork.Point p) {
        double distance=Double.POSITIVE_INFINITY;long best=0;
        for(int dz=-3;dz<=3;dz++) for(int dx=-3;dx<=3;dx++) for(int dy=-3;dy<=3;dy++) {
            int x=(int)Math.round(p.x())+dx,y=(int)Math.floor(p.y())+dy,z=(int)Math.round(p.z())+dz;
            long k=key(x,y,z);if(!floors.containsKey(k)) continue;
            double d=(x-p.x())*(x-p.x())+(floors.get(k)-p.y())*(floors.get(k)-p.y())+(z-p.z())*(z-p.z());
            if(d<distance) { distance=d;best=k; }
        }
        assertTrue(distance<9,"Missing walkable surface near "+p);return best;
    }

    @Test void karstPreservesPlatformAndDepth() {
        var f=new AllvrSanctuary(137);
        for(int z=-210;z<=210;z+=3) for(int x=-210;x<=210;x+=3) {
            var c=f.column(x,z);assertTrue(c.floor()>=-5 && c.floor()<=2);assertFalse(f.cavity(c,-5));
            if(c.radius()<=90) for(int y=-5;y<=95;y++) assertFalse(f.cavity(c,y));
        }
    }
}
