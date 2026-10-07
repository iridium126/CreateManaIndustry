package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Numeric, immutable diagnostics. Decoding never looks up a potentially recycled body. */
public final class PackageFreezeReport {
    public static final int STATE_BYTES=64,EVENT_BYTES=128,MAX_EVENTS=16,REASON_COUNT=32;
    public static final int EVENT_OFFSET=16+REASON_COUNT*4,BYTES=EVENT_OFFSET+MAX_EVENTS*EVENT_BYTES;
    private PackageFreezeReport(){}
    public enum Reason {
        UNKNOWN,WORLD_NOT_READY,SECTION_MISSING,WORLD_UNSUPPORTED,WORLD_METADATA,WORLD_BOUNDS,WORLD_OPPOSING,
        MOVING_POSE,MOVING_GEOMETRY,MOVING_BUDGET,MOVING_BVH,MOVING_SWEEP,MOVING_UNSUPPORTED,
        SUPPORT_ANCESTOR,SUPPORT_HEIGHT,SUPPORT_CEILING,ENV_BACKPRESSURE,ENV_WATER,ENV_HEALTH,SECTION_VERSION;
        public static Reason from(int code){return code>=0&&code<values().length?values()[code]:UNKNOWN;}
    }
    public record Event(Reason reason,String stage,long step,boolean stillFrozen,int body,long id,long generation,long lease,int index,long revision,
                        int a,int b,int c,int d,float x,float y,float z,float halfHeight,float vx,float vy,float vz,long requiredWorldRevision,int worldWait) {
        public String context(){return switch(reason){
            case SECTION_MISSING,WORLD_NOT_READY -> "section="+a+","+b+","+c;
            case SECTION_VERSION -> "section="+a+","+b+","+c+" requiredRevision="+Long.toUnsignedString(requiredWorldRevision)+" worldWait="+
                    switch(worldWait){case 1->"capture";case 2->"upload";case 3->"superseded-or-unavailable";default->"unknown";};
            case WORLD_UNSUPPORTED,WORLD_METADATA -> "block="+a+","+b+","+c+" flags/subtype="+d;
            case WORLD_BOUNDS -> "querySize="+a+","+b+","+c+" subtype="+d;
            case WORLD_OPPOSING -> "opposingAxes="+a+","+b+","+c;
            case MOVING_GEOMETRY -> "movingId="+Integer.toUnsignedString(a)+" geometryRevision="+
                    Long.toUnsignedString(Integer.toUnsignedLong(b)|(Integer.toUnsignedLong(c)<<32))+" geometryWait="+
                    switch(d){case 1->"capture";case 2->"worker";case 3->"upload";case 4->"historical-version";case 5->"unsupported";default->"unknown";};
            case MOVING_POSE,MOVING_BUDGET,MOVING_BVH,MOVING_SWEEP,MOVING_UNSUPPORTED ->
                    "movingId="+Integer.toUnsignedString(a)+" node="+b+" detail="+c+","+d+
                    (reason==Reason.MOVING_SWEEP?" iterationLimit="+c+" time="+Float.intBitsToFloat(d):"");
            case SUPPORT_ANCESTOR,SUPPORT_HEIGHT,SUPPORT_CEILING -> "ancestor="+Integer.toUnsignedString(a)+
                    " separation="+Float.intBitsToFloat(b)+" height="+Float.intBitsToFloat(c)+" supportVy="+Float.intBitsToFloat(d);
            case ENV_BACKPRESSURE,ENV_WATER,ENV_HEALTH -> "pending="+Integer.toUnsignedString(a)+
                    " written="+Integer.toUnsignedString(b)+" acknowledged="+Integer.toUnsignedString(c)+" health="+Float.intBitsToFloat(d);
            default -> "detail="+a+","+b+","+c+","+d;
        };}
    }
    public record Summary(int frozen,int changed,int deferred,int[] reasons) {
        public Summary{reasons=reasons.clone();}
        @Override public int[] reasons(){return reasons.clone();}
        public String description(){
            var text=new StringBuilder();
            for(int i=0;i<reasons.length;i++)if(reasons[i]!=0){if(!text.isEmpty())text.append(", ");text.append(Reason.from(i)).append('=').append(reasons[i]);}
            return text.toString();
        }
    }
    public static Summary decode(ByteBuffer data,Consumer<Event> consumer){
        if(data.remaining()!=BYTES)throw new IllegalArgumentException("Freeze snapshot length");
        var bytes=data.slice().order(ByteOrder.nativeOrder());int changed=bytes.getInt(0),frozen=bytes.getInt(4);
        if(changed<0||frozen<0)throw new IllegalArgumentException("Freeze snapshot counts");
        int[] counts=new int[REASON_COUNT];long sum=0;
        for(int i=0;i<counts.length;i++){counts[i]=bytes.getInt(16+i*4);if(counts[i]<0)throw new IllegalArgumentException("Freeze reason count");sum+=counts[i];}
        if(sum!=frozen)throw new IllegalArgumentException("Freeze reason total");
        for(int i=0;i<Math.min(changed,MAX_EVENTS);i++){
            int p=EVENT_OFFSET+i*EVENT_BYTES,code=bytes.getInt(p),stage=(code>>>16)&255;
            String phase=switch(stage){case 1->"predict";case 2->"solve";case 3->"carry";case 4->"moving";case 5->"support";case 6->"environment";case 7->"resume";default->"unknown";};
            consumer.accept(new Event(Reason.from(code&65535),phase,bytes.getLong(p+8),bytes.getInt(p+112)!=0,bytes.getInt(p+96),
                    bytes.getLong(p+64),bytes.getLong(p+72),bytes.getLong(p+80),bytes.getInt(p+100)-1,bytes.getLong(p+104),
                    bytes.getInt(p+16),bytes.getInt(p+20),bytes.getInt(p+24),bytes.getInt(p+28),
                    bytes.getFloat(p+32),bytes.getFloat(p+36),bytes.getFloat(p+40),bytes.getFloat(p+44),
                    bytes.getFloat(p+48),bytes.getFloat(p+52),bytes.getFloat(p+56),
                    (code&65535)==19?Integer.toUnsignedLong(bytes.getInt(p+60))|(Integer.toUnsignedLong(bytes.getInt(p+28))<<32):0,
                    (code>>>24)&127));
        }
        return new Summary(frozen,changed,Math.max(0,changed-MAX_EVENTS),counts);
    }
}
