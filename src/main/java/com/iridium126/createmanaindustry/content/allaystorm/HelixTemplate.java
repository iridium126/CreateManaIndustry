package com.iridium126.createmanaindustry.content.allaystorm;
import java.io.*;
import java.util.*;
/** Versioned offline Poisson template. No search, exp or random sampling in the frame loop. */
public final class HelixTemplate {
 public static final float SPACING=1.4f;
 public static final int VERSION=1,COUNT=4096;private static final float[] POINTS=load();
 private HelixTemplate(){}
 public static boolean available(){return POINTS.length==COUNT*3;}
 public static float coordinate(int rank,int axis){if(!available())throw new IllegalStateException("Helix template unavailable");return POINTS[rank*3+axis];}
 private record Cell(int x,int y,int z){}
 private static float[] load(){
  try(var stream=HelixTemplate.class.getResourceAsStream("/data/createmanaindustry/helix-template-v1.bin")){
   if(stream==null)throw new IOException("Missing helix template");var in=new DataInputStream(new BufferedInputStream(stream));if(in.readInt()!=VERSION||in.readInt()!=COUNT)throw new IOException("Helix template version");
   float[] points=new float[COUNT*3];Map<Cell,List<Integer>> grid=new HashMap<>();
   for(int i=0;i<COUNT;i++){for(int a=0;a<3;a++){points[i*3+a]=in.readFloat();if(!Float.isFinite(points[i*3+a]))throw new IOException("Nonfinite helix point");}
    float x=points[i*3],y=points[i*3+1],z=points[i*3+2];double u=(32-x)/64.;if(u<0||u>1||Math.hypot(y,z)>.6*Math.pow(32/.6,u)+.0001)throw new IOException("Helix envelope");
    var cell=new Cell((int)Math.floor(x/SPACING),(int)Math.floor(y/SPACING),(int)Math.floor(z/SPACING));
    for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int j:grid.getOrDefault(new Cell(cell.x+dx,cell.y+dy,cell.z+dz),List.of())){
     double a=x-points[j*3],b=y-points[j*3+1],c=z-points[j*3+2];if(a*a+b*b+c*c<SPACING*SPACING-.00001)throw new IOException("Overlapping helix template");
    }grid.computeIfAbsent(cell,k->new ArrayList<>()).add(i);
   }if(in.read()!=-1)throw new IOException("Trailing helix template data");return points;
  }catch(IOException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Helix disabled: invalid fixed 4096-member template",failure);return new float[0];}
 }
}
