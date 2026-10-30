package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.LongSupplier;

/** Owner-thread, bounded column work. No GL or Minecraft objects are required by the scheduler. */
public final class StormSurfaceCache {
    public static final int WIDTH=256,CELLS=65536,TILES=256,BIAS=1<<25;
    public static final int CPU=0x40000000,VOXEL=0x80000000,EMPTY=1;
    public static final int TILE_WORD=CELLS,MISSING_WORD=TILE_WORD+TILES*4,DIRTY_WORD=MISSING_WORD+CELLS/32,PATCH_WORD=DIRTY_WORD+8;
    public static final int PATCH_CAP=1024,FEEDBACK_BYTES=(TILES*4+CELLS/32)*4,TOTAL_BYTES=(PATCH_WORD+PATCH_CAP*4)*4;
    public interface Source {
        int minY();
        int startY(int x,int z,int top);
        /** -1 unknown, 0 empty, 1 occupied. airFloor may skip a certified empty section. */
        int occupied(int x,int y,int z);
        default int airFloor(){return Integer.MAX_VALUE;}
    }
    public final int[] tileX=new int[TILES],tileZ=new int[TILES],versions=new int[TILES];
    public final BitSet active=new BitSet(TILES),dirty=new BitSet(TILES),resetCpu=new BitSet(TILES);
    private final BitSet queued=new BitSet(CELLS),blocked=new BitSet(CELLS),started=new BitSet(CELLS),patches=new BitSet(CELLS),finished=new BitSet(CELLS);
    private final int[] scanY=new int[CELLS],above=new int[CELLS],values=new int[CELLS];
    public int originX,originZ,top;
    private int blockX=Integer.MAX_VALUE,blockZ,extent,sequence;
    public long epoch=1,queries,completed,captureNanos;
    private final LongSupplier clock;
    public StormSurfaceCache(){this(System::nanoTime);}
    public StormSurfaceCache(LongSupplier clock){this.clock=clock;Arrays.fill(tileX,Integer.MAX_VALUE);}
    public static int cell(int x,int z){return (x&255)+((z&255)<<8);}
    public static int tile(int x,int z){return (x&15)+((z&15)<<4);}
    public static int cellTile(int cell){return ((cell&255)>>4)+((cell>>12)<<4);}
    public static int encode(int y,int source){if(y < -BIAS||y>=BIAS)throw new IllegalArgumentException("Surface height");return source|(y+BIAS);}
    public static int height(int word){return (word&0xc0000000)==0?Integer.MIN_VALUE:(word&0x3ffffff)-BIAS;}
    public void configure(double x,double y,double z,double radius){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z),ny=(int)Math.floor(y-.02),r=(int)Math.ceil(radius*2)+1;
        if(bx==blockX&&bz==blockZ&&ny==top&&r==extent)return;
        boolean heightChanged=top!=ny;blockX=bx;blockZ=bz;top=ny;extent=r;originX=(bx>>4)*16-128;originZ=(bz>>4)*16-128;
        var next=new BitSet(TILES);
        for(int tz=originZ>>4;tz<(originZ>>4)+16;tz++)for(int tx=originX>>4;tx<(originX>>4)+16;tx++){
            double dx=Math.max(0,Math.max(tx*16-bx,bx-(tx*16+16))),dz=Math.max(0,Math.max(tz*16-bz,bz-(tz*16+16)));
            if(dx*dx+dz*dz>r*(double)r)continue;
            int t=tile(tx,tz);next.set(t);
            if(!active.get(t)||tileX[t]!=tx||tileZ[t]!=tz||heightChanged){tileX[t]=tx;tileZ[t]=tz;invalidate(t,true);}
        }
        var left=(BitSet)active.clone();left.andNot(next);for(int t=left.nextSetBit(0);t>=0;t=left.nextSetBit(t+1))clearColumns(t);
        active.clear();active.or(next);dirty.and(active);resetCpu.and(active);
    }
    private void clearColumns(int t){for(int z=0;z<16;z++){int start=((t&15)*16)+(((t>>4)*16+z)<<8);queued.clear(start,start+16);blocked.clear(start,start+16);started.clear(start,start+16);patches.clear(start,start+16);finished.clear(start,start+16);}}
    private void invalidate(int t,boolean cpu){versions[t]=++sequence;dirty.set(t);if(cpu)resetCpu.set(t);clearColumns(t);}
    public void invalidateAll(boolean cpu){for(int t=active.nextSetBit(0);t>=0;t=active.nextSetBit(t+1))invalidate(t,cpu);}
    public void changed(int tx,int tz,boolean cpu){int t=tile(tx,tz);if(active.get(t)&&tileX[t]==tx&&tileZ[t]==tz)invalidate(t,cpu);}
    public void voxelChanged(int tx,int ty,int tz){if(ty*16<=top)changed(tx,tz,false);}
    public void feedback(long readEpoch,ByteBuffer data){
        if(readEpoch!=epoch)return;
        for(int t=active.nextSetBit(0);t>=0;t=active.nextSetBit(t+1)){
            int b=t*16;if(data.getInt(b)!=tileX[t]||data.getInt(b+4)!=tileZ[t]||data.getInt(b+8)!=versions[t]||data.getInt(b+12)!=top)continue;
            for(int z=0;z<16;z++){
                int start=(t&15)*16+(((t>>4)*16+z)<<8),word=data.getInt(TILES*16+(start>>>5)*4),shift=start&31;
                int missing=(word>>>shift)&65535;
                for(int x=0;x<16;x++){int c=start+x;if((missing&(1<<x))==0){queued.clear(c);started.clear(c);patches.clear(c);}else if(!blocked.get(c)&&!finished.get(c))queued.set(c);}
            }
        }
    }
    public void capture(Source source,long nanos){
        if(nanos<=0||queued.isEmpty())return;
        long began=clock.getAsLong();int columns=0,probes=0;
        outer:for(int c=queued.nextSetBit(0);c>=0&&columns<256;c=queued.nextSetBit(c+1)){
            columns++;int t=cellTile(c),x=tileX[t]*16+(c&15),z=tileZ[t]*16+((c>>8)&15);
            if(clock.getAsLong()-began>=nanos)break;
            if(!started.get(c)){scanY[c]=source.startY(x,z,top);above[c]=2;started.set(c);}
            while(scanY[c]>=source.minY()){
                if(probes>=4096||clock.getAsLong()-began>=nanos)break outer;
                int y=scanY[c],state=source.occupied(x,y,z);queries++;probes++;
                if(state<0){queued.clear(c);started.clear(c);blocked.set(c);continue outer;}
                if(state==1&&above[c]==0&&y+1<=top){values[c]=encode(y+1,CPU);patches.set(c);finished.set(c);queued.clear(c);started.clear(c);completed++;continue outer;}
                above[c]=state;scanY[c]=y-1;
                if(state==0)scanY[c]=Math.min(scanY[c],source.airFloor()-1);
            }
            if(scanY[c]<source.minY()){values[c]=EMPTY;patches.set(c);finished.set(c);queued.clear(c);started.clear(c);completed++;}
        }
        captureNanos+=clock.getAsLong()-began;
    }
    public int writePatches(ByteBuffer out,int limit){
        out.clear();int n=appendPatches(out,limit);out.flip();acceptPatches(out,0,n);return n;
    }
    public int appendPatches(ByteBuffer out,int limit){
        int n=0;
        for(int c=patches.nextSetBit(0);c>=0&&n<limit;c=patches.nextSetBit(c+1)){
            int t=cellTile(c);out.putInt(c).putInt(values[c]).putInt(versions[t]).putInt(0);n++;
        }
        return n;
    }
    public void acceptPatches(ByteBuffer data,int offset,int count){for(int i=0;i<count;i++)patches.clear(data.getInt((offset+i)*16));}
    public boolean pending(){return !queued.isEmpty();}
    public boolean hasPatches(){return !patches.isEmpty();}
    public int queued(){return queued.cardinality();}
    public int blocked(){return blocked.cardinality();}
    public void reset(){epoch++;blockX=Integer.MAX_VALUE;active.clear();dirty.clear();resetCpu.clear();queued.clear();blocked.clear();started.clear();patches.clear();finished.clear();Arrays.fill(tileX,Integer.MAX_VALUE);}
}
