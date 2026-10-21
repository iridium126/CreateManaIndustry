package com.iridium126.createmanaindustry.content.allaystorm;

import java.io.*;
import net.minecraft.world.phys.Vec3;

/** C2 inverse-arc tables: runtime sampling needs no quadrature or root solve. */
public final class DomeTemplate {
    public static final double HANDOFF_SECONDS=1.25;
    public static final int VERSION=4,COUNT=12288,RECORD_WORDS=16,NODE_WORDS=12,WORDS=4+COUNT*RECORD_WORDS;
    private static final String RESOURCE="/data/createmanaindustry/dome-template-v4.bin";
    private static final float[] DATA=loadMetadata();
    private static volatile int[] keys;
    private static volatile boolean keyLoadFailed;
    private static final java.util.concurrent.atomic.AtomicBoolean PRELOADING=new java.util.concurrent.atomic.AtomicBoolean();
    private DomeTemplate() {}
    public static boolean available(){return DATA.length==WORDS;}
    public static float word(int i){if(!available())throw new IllegalStateException("Dome template unavailable");return DATA[i];}
    public static int keyCount(){return (int)word(0);}
    public static boolean keysReady(){return keys!=null;}
    public static boolean keysFailed(){return keyLoadFailed;}
    public static void preloadKeys(){if(available()&&PRELOADING.compareAndSet(false,true))java.util.concurrent.CompletableFuture.runAsync(DomeTemplate::keys).exceptionally(error->{com.mojang.logging.LogUtils.getLogger().error("Dome trajectory preload failed",error);return null;});}
    private static int record(int rank){if(rank<0||rank>=COUNT)throw new IndexOutOfBoundsException(rank);return 4+rank*RECORD_WORDS;}
    public static float duration(int rank){return word(record(rank)+2);}
    public static int ringBase(int rank){return (int)word(record(rank)+3);}
    public static int ringCount(int rank){return (int)word(record(rank)+7);}
    public static double radius(int rank){return word(record(rank)+4);}
    public static double height(int rank){return word(record(rank)+5);}
    public static double angle(int rank){return (rank-ringBase(rank))*2*Math.PI/ringCount(rank);}
    public static int curtain(int rank){return (rank-ringBase(rank))*12/ringCount(rank);}
    public static boolean coreChannel(int rank){return (ringBase(rank)/12+(rank-ringBase(rank))%(ringCount(rank)/12))%16==0;}
    public static boolean passedExitPlane(int rank,Vec3 localBodyCenter){int r=record(rank);return localBodyCenter.x*word(r+13)+localBodyCenter.y*word(r+14)+localBodyCenter.z*word(r+15)>=word(r+6)-.00001;}
    public static double gatherSpeed(int rank){return Math.abs(word(record(rank)+9));}
    public static double length(int rank){return word(record(rank)+11);}
    public static int mappedRank(int rank,double seconds){int r=record(rank),n=ringCount(rank),base=ringBase(rank);long shift=(long)Math.floor(word(r+9)*seconds/word(r+10)*n+.5);return base+(int)Math.floorMod(rank-base+shift,n);}
    public static double distanceAt(double seconds,double initialSpeed){double t=Math.max(0,seconds),u=Math.min(t/.5,1);return initialSpeed*t+(6-initialSpeed)*.5*(Math.pow(u,6)-3*Math.pow(u,5)+2.5*Math.pow(u,4))+Math.max(0,t-.5)*(6-initialSpeed);}
    public static double speedAt(double seconds,double initialSpeed){double u=Math.clamp(seconds/.5,0,1);return initialSpeed+(6-initialSpeed)*u*u*u*(10-15*u+6*u*u);}
    public static Vec3 gatherPoint(int rank,double seconds){return gather(rank,seconds,0);}
    public static Vec3 gatherVelocity(int rank,double seconds){return gather(rank,seconds,1);}
    private static Vec3 gather(int rank,double seconds,int derivative){int r=record(rank);double speed=word(r+9),length=word(r+10),phase=(rank-ringBase(rank))/(double)ringCount(rank)+speed*seconds/length;phase-=Math.floor(phase);return lookup((int)word(r+8),(int)word(r+12),phase*length,derivative).scale(Math.pow(speed,derivative));}
    public static Vec3 point(int rank,double distance){return sampleArc(rank,distance,0);}
    public static Vec3 sampleArc(int rank,double distance,int derivative){int r=record(rank);return lookup((int)word(r),(int)word(r+1),Math.clamp(distance,0,length(rank)),derivative);}
    public static Vec3 lockedPoint(int rank,double seconds,double gatherSeconds){return locked(rank,seconds,gatherSeconds,0);}
    public static Vec3 lockedVelocity(int rank,double seconds,double gatherSeconds){return locked(rank,seconds,gatherSeconds,1);}
    private static Vec3 locked(int rank,double seconds,double gatherSeconds,int derivative){
        int mapped=mappedRank(rank,gatherSeconds);double speed=gatherSpeed(rank),s=distanceAt(seconds,speed);
        Vec3 value=sampleArc(mapped,s,derivative).scale(derivative==0?1:speedAt(seconds,speed));
        double u=Math.clamp(seconds/HANDOFF_SECONDS,0,1);if(u>=1)return value;
        Vec3 p=gatherPoint(rank,gatherSeconds).subtract(point(mapped,0));
        Vec3 v=gatherVelocity(rank,gatherSeconds).subtract(sampleArc(mapped,0,1).scale(speed));
        Vec3 a=gather(rank,gatherSeconds,2).subtract(sampleArc(mapped,0,2).scale(speed*speed));
        double u2=u*u,u3=u2*u,u4=u3*u,u5=u4*u;
        return derivative==0?value.add(p.scale(1-10*u3+15*u4-6*u5)).add(v.scale(HANDOFF_SECONDS*(u-6*u3+8*u4-3*u5))).add(a.scale(HANDOFF_SECONDS*HANDOFF_SECONDS*.5*(u2-3*u3+3*u4-u5))):value.add(p.scale((-30*u2+60*u3-30*u4)/HANDOFF_SECONDS)).add(v.scale(1-18*u2+32*u3-15*u4)).add(a.scale(HANDOFF_SECONDS*.5*(2*u-9*u2+12*u3-5*u4)));
    }
    private static float node(int base,int node,int component){return Float.intBitsToFloat(keys()[(base+node)*NODE_WORDS+component]);}
    private static Vec3 vector(int base,int node,int component){return new Vec3(node(base,node,component),node(base,node,component+1),node(base,node,component+2));}
    private static Vec3 lookup(int base,int count,double distance,int derivative){
        int lo=0,hi=count-1;while(hi-lo>1){int mid=(lo+hi)/2;if(node(base,mid,3)<distance)lo=mid;else hi=mid;}
        double length=node(base,hi,3)-node(base,lo,3),u=Math.clamp((distance-node(base,lo,3))/length,0,1);
        Vec3 p=vector(base,lo,0),q=vector(base,hi,0),v=vector(base,lo,4).scale(length),w=vector(base,hi,4).scale(length),a=vector(base,lo,8).scale(length*length),b=vector(base,hi,8).scale(length*length),delta=q.subtract(p);
        Vec3 c3=delta.scale(10).subtract(v.scale(6)).subtract(w.scale(4)).subtract(a.scale(1.5)).add(b.scale(.5));
        Vec3 c4=delta.scale(-15).add(v.scale(8)).add(w.scale(7)).add(a.scale(1.5)).subtract(b);
        Vec3 c5=delta.scale(6).subtract(v.scale(3)).subtract(w.scale(3)).subtract(a.scale(.5)).add(b.scale(.5));
        if(derivative==2)return c5.scale(20*u).add(c4.scale(12)).scale(u).add(c3.scale(6)).scale(u).add(a).scale(1/(length*length));
        if(derivative==1)return c5.scale(5*u).add(c4.scale(4)).scale(u).add(c3.scale(3)).scale(u).add(a).scale(u).add(v).scale(1/length);
        return c5.scale(u).add(c4).scale(u).add(c3).scale(u).add(a.scale(.5)).scale(u).add(v).scale(u).add(p);
    }
    public static int[] keys(){var result=keys;if(result!=null)return result;synchronized(DomeTemplate.class){if(keys!=null)return keys;
        try(var in=open()){in.skipNBytes(16L+COUNT*RECORD_WORDS*4L);result=new int[Math.multiplyExact(keyCount(),2)];for(int i=0;i<result.length;i++){result[i]=in.readInt();if(!Float.isFinite(Float.intBitsToFloat(result[i])))throw new IOException("Nonfinite Dome curve");}if(in.read()!=-1)throw new IOException("Trailing Dome data");
            for(int rank=0;rank<COUNT;rank++){int r=record(rank),base=(int)word(r),count=(int)word(r+1);float previous=-1;for(int k=0;k<count;k++){float arc=Float.intBitsToFloat(result[(base+k)*NODE_WORDS+3]);if(arc<=previous)throw new IOException("Dome arc order");previous=arc;}float penultimate=Float.intBitsToFloat(result[(base+count-2)*NODE_WORDS+3]);if(word(r+11)>previous+.00002||word(r+11)<=penultimate)throw new IOException("Dome active arc window");}
            return keys=result;
        }catch(IOException failure){keyLoadFailed=true;throw new IllegalStateException("Dome curve resource failed",failure);}
    }}
    private static DataInputStream open()throws IOException{var stream=DomeTemplate.class.getResourceAsStream(RESOURCE);if(stream==null)throw new IOException("Missing Dome v4 template");return new DataInputStream(new BufferedInputStream(stream));}
    private static float[] loadMetadata(){try(var in=open()){
        int version=in.readInt(),count=in.readInt(),keyCount=in.readInt(),stride=in.readInt();if(version!=VERSION||count!=COUNT||keyCount<COUNT*12||keyCount>67108864||stride!=6)throw new IOException("Dome v4 header");
        float[] result=new float[WORDS];result[0]=keyCount;result[1]=stride;result[2]=count;result[3]=version;
        for(int rank=0;rank<COUNT;rank++){int r=4+rank*RECORD_WORDS;for(int k=0;k<RECORD_WORDS;k++){result[r+k]=in.readFloat();if(!Float.isFinite(result[r+k]))throw new IOException("Nonfinite Dome metadata");}
            double normal=result[r+13]*result[r+13]+result[r+14]*result[r+14]+result[r+15]*result[r+15];if(Math.abs(normal-1)>.0001)throw new IOException("Dome exit-plane normal");
            int base=(int)result[r+3],n=(int)result[r+7];if(result[r]<0||(result[r]+result[r+1])*6>keyCount||result[r+1]<2||result[r+1]>4096||result[r+2]<=.5||result[r+2]>60||base<0||base>rank||n<12||n%12!=0||rank>=base+n||base+n>COUNT||Math.abs(result[r+9])<4||Math.abs(result[r+9])>6||result[r+10]<=0||result[r+11]<=3||result[r+12]<2||(result[r+8]+result[r+12])*6>keyCount)throw new IOException("Dome v4 record");
        }return result;
    }catch(IOException failure){com.mojang.logging.LogUtils.getLogger().error("Dome disabled: invalid C2 template",failure);return new float[0];}}
}
