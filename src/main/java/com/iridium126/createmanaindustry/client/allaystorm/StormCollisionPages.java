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
    public static int hash(int x,int y,int z){int h=x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f;return h^(h>>>16);}
    /** At most eight probes in the shader; grow the table rather than dropping colliding keys. */
    public static int[] table(Map<Section,Integer> rows){
        int size=16;while(size<rows.size()*2)size*=2;
        while(true){
            int[] out=new int[size*4];for(int p=3;p<out.length;p+=4)out[p]=UNKNOWN;
            boolean fits=true;
            for(var entry:rows.entrySet()){
                Section k=entry.getKey();int row=hash(k.x,k.y,k.z)&(size-1),probe=0;
                while(out[row*4+3]!=UNKNOWN&&probe<8){row=(row+1)&(size-1);probe++;}
                if(probe==8){fits=false;break;}
                int p=row*4;out[p]=k.x;out[p+1]=k.y;out[p+2]=k.z;out[p+3]=entry.getValue();
            }
            if(fits)return out;size=Math.multiplyExact(size,2);
        }
    }
    public static int lookup(int[] table,Section key){int mask=table.length/4-1,row=hash(key.x,key.y,key.z)&mask;for(int i=0;i<8;i++,row=(row+1)&mask){int p=row*4;if(table[p+3]==UNKNOWN)return UNKNOWN;if(table[p]==key.x&&table[p+1]==key.y&&table[p+2]==key.z)return table[p+3];}return UNKNOWN;}
    private StormCollisionPages(){}
}
