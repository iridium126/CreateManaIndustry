package com.iridium126.createmanaindustry.content.allaystorm;

/** Stable wire kinds and common authority rules. */
public final class StormAttack {
 public static final int STARFALL=0,DOME=1,HELIX=2;
 private StormAttack() {}
 public static int choose(double roll,double dome,double helix){
  dome=Math.max(0,Math.min(1,dome));helix=Math.max(0,Math.min(1,helix));double sum=dome+helix;
  if(sum>1){dome/=sum;helix/=sum;}
  return roll<helix?HELIX:roll<helix+dome?DOME:STARFALL;
 }
 public static boolean targetRequired(int kind,boolean locked){return kind==STARFALL||kind==HELIX&&!locked;}
 public static int quota(int alive,double fraction,int limit){return Math.min(limit,(int)Math.floor(Math.max(0,alive)*Math.max(0,Math.min(1,fraction))));}
 public static boolean regional(int kind){return kind==DOME||kind==HELIX;}
}
