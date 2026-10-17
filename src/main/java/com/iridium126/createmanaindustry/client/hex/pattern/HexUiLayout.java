package com.iridium126.createmanaindustry.client.hex.pattern;
import java.util.*;
import net.minecraft.world.phys.Vec2;
/** Translation/GUI-size changes update a matrix, not immutable GPU topology. */
public final class HexUiLayout {
    public HexShape shape;public float x,y,scale=1;
    public boolean update(List<Vec2> points,Set<Integer> duplicates,HexShape.Style style,boolean drawLast){
        x=points.isEmpty()?0:points.get(0).x;y=points.isEmpty()?0:points.get(0).y;scale=1;
        for(int i=1;i<points.size();i++){float dx=points.get(i).x-x,dy=points.get(i).y-y;float length=(float)Math.sqrt(dx*dx+dy*dy);if(length>1e-6f){scale=length;break;}}
        boolean same=shape!=null&&shape.style.equals(style)&&shape.bare.length==points.size()*2&&shape.nodes==Math.max(0,points.size()-(drawLast?0:1))&&shape.duplicates.equals(duplicates==null?Set.of():duplicates);
        if(same){int n=drawLast?points.size():Math.max(0,points.size()-1);for(int i=0;i<n;i++)if(Math.abs(shape.bare[2*i]-(points.get(i).x-x)/scale)>1e-5f||Math.abs(shape.bare[2*i+1]-(points.get(i).y-y)/scale)>1e-5f){same=false;break;}}
        if(same)return false;var normalized=new ArrayList<Vec2>(points.size());for(var p:points)normalized.add(new Vec2((p.x-x)/scale,(p.y-y)/scale));shape=new HexShape(normalized,duplicates,style,drawLast);return true;
    }
}
