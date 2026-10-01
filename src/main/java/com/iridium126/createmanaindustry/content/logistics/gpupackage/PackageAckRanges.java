package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Arrays;

/** Immutable exact ACK runs, never a cumulative watermark. Gaps are not acknowledged.
 * Long sequence identity is preserved; limits bound decoder allocation and client mailbox work. */
public final class PackageAckRanges {
    public static final int MAX_RUNS=128,MAX_ACKS=2048;
    private final long[] endpoints;
    private final int count;
    public PackageAckRanges(long... endpoints) {
        if(endpoints==null || endpoints.length==0 || endpoints.length%2!=0 || endpoints.length>MAX_RUNS*2)
            throw new IllegalArgumentException("Package ACK run count");
        this.endpoints=endpoints.clone();long previous=-2,total=0;
        for(int i=0;i<this.endpoints.length;i+=2) {
            long start=this.endpoints[i],end=this.endpoints[i+1];
            if(start<0 || end<start || start<=previous || previous!=Long.MAX_VALUE && start==previous+1
                    || end-start>=MAX_ACKS)throw new IllegalArgumentException("Package ACK run bounds/order");
            total+=end-start+1;if(total>MAX_ACKS)throw new IllegalArgumentException("Package ACK work bound");previous=end;
        }
        count=(int)total;
    }
    public int runs(){return endpoints.length/2;}
    public int count(){return count;}
    public long start(int run){return endpoints[run*2];}
    public long end(int run){return endpoints[run*2+1];}
    public int length(int run){return (int)(end(run)-start(run)+1);}
    @Override public boolean equals(Object value){return value instanceof PackageAckRanges ranges && Arrays.equals(endpoints,ranges.endpoints);}
    @Override public int hashCode(){return Arrays.hashCode(endpoints);}
    @Override public String toString(){return Arrays.toString(endpoints);}

    /** Server-thread primitive accumulator. Overflow returns false WITHOUT consuming the ACK;
     * enqueue the immutable snapshot, clear after successful send, then retry that sequence. */
    public static final class Builder {
        private final long[] endpoints=new long[MAX_RUNS*2];
        private int runs,count;
        public boolean add(long sequence) {
            if(sequence<0)throw new IllegalArgumentException("Package ACK sequence");
            if(runs>0 && sequence>=endpoints[(runs-1)*2]) {
                long last=endpoints[runs*2-1];if(sequence<=last)return true;
                if(count==MAX_ACKS)return false;
                if(last!=Long.MAX_VALUE && sequence==last+1){endpoints[runs*2-1]=sequence;count++;return true;}
                if(runs==MAX_RUNS)return false;
                endpoints[runs*2]=endpoints[runs*2+1]=sequence;runs++;count++;return true;
            }
            int next=0;while(next<runs && endpoints[next*2+1]<sequence)next++;
            if(next<runs && endpoints[next*2]<=sequence)return true;
            if(count==MAX_ACKS)return false;
            boolean left=next>0 && endpoints[next*2-1]!=Long.MAX_VALUE && endpoints[next*2-1]+1==sequence;
            boolean right=next<runs && sequence!=Long.MAX_VALUE && sequence+1==endpoints[next*2];
            if(left && right) {
                endpoints[next*2-1]=endpoints[next*2+1];
                System.arraycopy(endpoints,(next+1)*2,endpoints,next*2,(runs-next-1)*2);runs--;
            }else if(left)endpoints[next*2-1]=sequence;
            else if(right)endpoints[next*2]=sequence;
            else {
                if(runs==MAX_RUNS)return false;
                System.arraycopy(endpoints,next*2,endpoints,(next+1)*2,(runs-next)*2);
                endpoints[next*2]=endpoints[next*2+1]=sequence;runs++;
            }
            count++;return true;
        }
        public boolean empty(){return count==0;}
        public int count(){return count;}
        public int runs(){return runs;}
        public PackageAckRanges snapshot(){if(empty())throw new IllegalStateException("Empty package ACK batch");return new PackageAckRanges(Arrays.copyOf(endpoints,runs*2));}
        public void clear(){runs=count=0;}
    }
}
