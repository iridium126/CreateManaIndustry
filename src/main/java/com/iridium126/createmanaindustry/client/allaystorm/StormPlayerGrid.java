package com.iridium126.createmanaindustry.client.allaystorm;
import java.util.Arrays;
/** Reusable 6m cell hash. Packed after the unsized player array, no player-count truncation. */
public final class StormPlayerGrid {
 private float[] packed=new float[128];private int[] heads=new int[32],xs=new int[32],ys=new int[32],zs=new int[32];private int words;
 public float[] data(){return packed;}public int words(){return words;}
 public void build(float[] xyz,int n){build(xyz,xyz,xyz,null,n,null);}
 /** Eye positions plus reconstructible feet/centres. The cell index is built from feet. */
 public void build(float[] centers,float[] eyes,float[] feet,int[] ids,int n,float[] attackEyes){int table=1;while(table<Math.max(2,n*2))table<<=1;
  int meta=n+1+table,attacks=meta+n;
  words=(attacks+68)*4;if(packed.length<words)packed=new float[Integer.highestOneBit(words-1)<<1];
  if(heads.length<table){heads=new int[table];xs=new int[table];ys=new int[table];zs=new int[table];}else Arrays.fill(heads,0,table,0);
  float height=0;
  for(int i=0;i<n;i++){int x=(int)Math.floor(feet[i*3]/6),y=(int)Math.floor(feet[i*3+1]/6),z=(int)Math.floor(feet[i*3+2]/6),h=(x*73856093^y*19349663^z*83492791)&(table-1);
   while(heads[h]!=0&&(xs[h]!=x||ys[h]!=y||zs[h]!=z))h=(h+1)&(table-1);xs[h]=x;ys[h]=y;zs[h]=z;
   packed[i*4]=eyes[i*3];packed[i*4+1]=eyes[i*3+1];packed[i*4+2]=eyes[i*3+2];packed[i*4+3]=heads[h];heads[h]=i+1;
   int m=(meta+i)*4;packed[m]=feet[i*3+1]-eyes[i*3+1];packed[m+1]=centers[i*3+1]-eyes[i*3+1];packed[m+2]=Float.intBitsToFloat(ids==null?i+1:ids[i]);packed[m+3]=0;
   height=Math.max(height,Math.abs(centers[i*3+1]-feet[i*3+1]));
  }packed[n*4]=table;packed[n*4+1]=meta;packed[n*4+2]=attacks;packed[n*4+3]=height;
  for(int h=0;h<table;h++){int at=(n+1+h)*4;packed[at]=Float.intBitsToFloat(xs[h]);packed[at+1]=Float.intBitsToFloat(ys[h]);packed[at+2]=Float.intBitsToFloat(zs[h]);packed[at+3]=heads[h];}
  Arrays.fill(packed,attacks*4,words,0);if(attackEyes!=null)System.arraycopy(attackEyes,0,packed,attacks*4,Math.min(272,attackEyes.length));
 }
}
