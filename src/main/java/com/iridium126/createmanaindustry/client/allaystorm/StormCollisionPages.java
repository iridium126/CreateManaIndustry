package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;

/** Compact immutable occupancy, independent of GL, world access and package residency. */
public final class StormCollisionPages {
    public static final int WORDS=128, BYTES=512, UNKNOWN=-1, AIR=-2, SOLID=-3;
    public record Section(int x,int y,int z) {}
    public record Page(long revision,int kind,int[] words) {
        public Page {if(kind>=0&&(words==null||words.length!=WORDS))throw new IllegalArgumentException("Occupancy page");}
        public boolean solid(int x,int y,int z){int i=(x&15)|((z&15)<<4)|((y&15)<<8);return kind==SOLID||kind>=0&&(words[i>>>5]&(1<<(i&31)))!=0;}
    }
    public static Page page(long revision,int[] bits){
        boolean air=true,solid=true;for(int b:bits){air&=b==0;solid&=b==-1;}
        return new Page(revision,air?AIR:solid?SOLID:0,air||solid?null:bits.clone());
    }
    public static final int MAX_SECTIONS=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.STORM_TERRAIN_MAX_SECTIONS;
    public static final int MAX_ROWS=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.STORM_TERRAIN_MAX_ROWS;
    public static final Comparator<Section> ORDER=Comparator.comparingInt(Section::x).thenComparingInt(Section::y).thenComparingInt(Section::z);
    private static int mix(int v){v=(v^(v>>>16))*0x7feb352d;v=(v^(v>>>15))*0x846ca68b;return v^(v>>>16);}
    /** Unsigned FP-free arithmetic, identical in GLSL and CUDA. */
    public static int hash(int x,int y,int z){return mix(mix(x+0x9e3779b9)^Integer.rotateLeft(mix(y+0x85ebca6b),11)^Integer.rotateLeft(mix(z+0xc2b2ae35),22));}
    public record Index(int[] words,int primaryRows){
        public int overflow(){return words.length/4-primaryRows;}
    }
    /** Bounded construction; adversarial collisions go to an exact sorted overflow. */
    public static Index table(Map<Section,Integer> rows){
        if(rows.size()>MAX_SECTIONS)throw new IllegalStateException("Storm collision sections="+rows.size()+" exceed limit="+MAX_SECTIONS);
        int size=16;while(size<rows.size()*2)size*=2;
        int[] out=new int[(size+rows.size())*4];for(int p=3;p<out.length;p+=4)out[p]=UNKNOWN;
        var overflow=new ArrayList<Map.Entry<Section,Integer>>();
        for(var entry:rows.entrySet()){
            if(entry.getValue()==UNKNOWN)continue;
            Section k=entry.getKey();int row=hash(k.x,k.y,k.z)&(size-1),probe=0;
            while(probe<8&&out[row*4+3]!=UNKNOWN){row=(row+1)&(size-1);probe++;}
            if(probe==8){overflow.add(entry);continue;}
            put(out,row,k,entry.getValue());
        }
        overflow.sort(Map.Entry.comparingByKey(ORDER));
        for(int i=0;i<overflow.size();i++){var e=overflow.get(i);put(out,size+i,e.getKey(),e.getValue());}
        return new Index(Arrays.copyOf(out,(size+overflow.size())*4),size);
    }
    private static void put(int[] out,int row,Section k,int value){int p=row*4;out[p]=k.x;out[p+1]=k.y;out[p+2]=k.z;out[p+3]=value;}
    public static int lookup(Index index,Section key){
        int[] table=index.words();int mask=index.primaryRows()-1,row=hash(key.x,key.y,key.z)&mask;
        for(int i=0;i<8;i++,row=(row+1)&mask){int p=row*4;if(table[p+3]==UNKNOWN)break;if(table[p]==key.x&&table[p+1]==key.y&&table[p+2]==key.z)return table[p+3];}
        int lo=index.primaryRows(),hi=table.length/4;
        while(lo<hi){int mid=(lo+hi)>>>1,p=mid*4;int c=Integer.compare(table[p],key.x);if(c==0)c=Integer.compare(table[p+1],key.y);if(c==0)c=Integer.compare(table[p+2],key.z);if(c<0)lo=mid+1;else if(c>0)hi=mid;else return table[p+3];}
        return UNKNOWN;
    }
    private StormCollisionPages(){}
}
