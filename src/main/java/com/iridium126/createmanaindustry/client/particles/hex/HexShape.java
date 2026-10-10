package com.iridium126.createmanaindustry.client.particles.hex;

import java.util.*;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;

/** Immutable native topology. No noise evaluation or triangle objects on the CPU. */
public final class HexShape {
    public record Style(int hops,float variance,float speed,float flow,float readability,float lastLength) {
        public static final Style ORBIT=new Style(5,.65f,.1f,.2f,0,1);
    }
    public final Style style;
    public final float[] bare;
    public final Set<Integer> duplicates;
    public final int points,nodes,vertices,tailStart;
    public final ByteBuffer sources;
    public HexShape(List<Vec2> positions,Set<Integer> duplicates,Style style,boolean drawLast) {this(positions,duplicates,style,drawLast,true);}
    public HexShape(List<Vec2> positions,Set<Integer> duplicates,Style style,boolean drawLast,boolean dots) {
        this.style=style;this.duplicates=duplicates==null?Set.of():Set.copyOf(duplicates);
        bare=new float[positions.size()*2];for(int i=0;i<positions.size();i++){bare[2*i]=positions.get(i).x;bare[2*i+1]=positions.get(i).y;}
        var rows=new ArrayList<float[]>();int segment=0,last=0;boolean start=true;
        if(positions.size()==1&&duplicates==null)rows.add(new float[]{bare[0],bare[1],bare[0],bare[1],0,0,0,0});
        for(int i=0;i+1<positions.size();i++) {
            float ax=bare[2*i],ay=bare[2*i+1],bx=bare[2*i+2],by=bare[2*i+3],dx=bx-ax,dy=by-ay;
            if(start&&i>0&&this.duplicates.contains(i)){ax+=dx*style.readability;ay+=dy*style.readability;}
            if(i!=positions.size()-2&&this.duplicates.contains(i+1)){bx-=dx*style.readability;by-=dy*style.readability;}
            if(start){rows.add(new float[]{ax,ay,ax,ay,0,0,0,0});segment=0;start=false;}
            last=rows.size();float variance=(float)Math.sqrt((bx-ax)*(bx-ax)+(by-ay)*(by-ay))/style.hops*style.variance;
            int hops=i==positions.size()-2?Math.round(style.lastLength*style.hops):style.hops;
            for(int j=1;j<=hops;j++)rows.add(new float[]{ax,ay,bx,by,segment,(float)j/(style.hops+1),variance,j});
            if(hops==style.hops)rows.add(new float[]{bx,by,bx,by,0,0,0,0});segment++;
            if(this.duplicates.contains(i+1))start=true;
        }
        points=rows.size();nodes=dots?Math.max(0,positions.size()-(drawLast?0:1)):0;tailStart=last;
        int layer=points<=1?0:(points-1)*42+60;vertices=Math.addExact(layer*2,nodes*18);
        sources=BufferUtils.createByteBuffer(points*32+nodes*16);
        for(float[] row:rows)for(float v:row)sources.putFloat(v);
        for(int i=0;i<nodes;i++)sources.putFloat(bare[2*i]).putFloat(bare[2*i+1]).putFloat(0).putFloat(0);
        sources.flip();
    }
    public boolean matchesPrefix(List<Vec2> positions,Set<Integer> d,Style s,boolean drawLast) {
        if(!style.equals(s)||bare.length!=positions.size()*2||nodes!=Math.max(0,positions.size()-(drawLast?0:1))||!duplicates.equals(d==null?Set.of():d)||positions.size()<2)return false;
        for(int i=0;i+1<positions.size();i++)if(bare[i*2]!=positions.get(i).x||bare[i*2+1]!=positions.get(i).y)return false;
        return true;
    }
    public boolean matches(List<Vec2> positions,Set<Integer> d,Style s,boolean drawLast) {
        if(!style.equals(s)||bare.length!=positions.size()*2||nodes!=Math.max(0,positions.size()-(drawLast?0:1))||!duplicates.equals(d==null?Set.of():d))return false;
        for(int i=0;i<positions.size();i++)if(bare[i*2]!=positions.get(i).x||bare[i*2+1]!=positions.get(i).y)return false;
        return true;
    }
}
