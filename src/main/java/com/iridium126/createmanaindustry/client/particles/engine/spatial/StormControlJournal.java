package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient;
import com.iridium126.createmanaindustry.content.allaystorm.HelixGatherFrame;
import com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery;
import com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall;
import com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnSnapshot;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/**
 * A render-thread timeline independent of the three native work slots.
 * Submission advances a send cursor; only a successful, ordered receipt
 * advances the committed cursor. A fault retains the uncommitted suffix;
 * restoring it also requires the corresponding committed member state.
 */
final class StormControlJournal {
    record Float4(float x,float y,float z,float w) {
        private static final Float4 ZERO=new Float4(0,0,0,0);
        private static Float4 at(float[] values,int offset){
            return new Float4(values[offset],values[offset+1],values[offset+2],values[offset+3]);
        }
        private void write(ByteBuffer out){out.putFloat(x).putFloat(y).putFloat(z).putFloat(w);}
    }
    record Command(int operation,int member,int span,int flags,long scope,Float4 a,Float4 b,long generation) {
        Command(int operation,int member,int span,int flags,long scope,Float4 a,Float4 b){
            this(operation,member,span,flags,scope,a,b,0);
        }
        Command inGeneration(long generation){
            return new Command(operation,member,span,flags,scope,a,b,generation);
        }
        static Command lifecycle(boolean active){
            return new Command(STORM_CONTROL_LIFECYCLE,0,1,active?1:0,0,Float4.ZERO,Float4.ZERO);
        }
        static Command spawn(int first,int count,boolean ring){
            return new Command(STORM_CONTROL_SPAWN,first,count,ring?1:0,0,Float4.ZERO,Float4.ZERO);
        }
        static Command damage(int member,float amount,float knockbackX,float knockbackZ,boolean died){
            return damage(member,amount,knockbackX,knockbackZ,0,died);
        }
        static Command damage(int member,float amount,float knockbackX,float knockbackZ,float light,boolean died){
            return new Command(STORM_CONTROL_DAMAGE,member,1,died?1:0,0,new Float4(amount,knockbackX,knockbackZ,light),Float4.ZERO);
        }
        static Command correction(int member,Float4 positionAndTime,Float4 velocityAndStrength){
            return new Command(STORM_CONTROL_CORRECTION,member,1,0,0,positionAndTime,velocityAndStrength);
        }
        static Command outer(int member,int cycle,Float4 target){
            return new Command(target==null?STORM_CONTROL_OUTER_RELEASE:STORM_CONTROL_OUTER_START,
                    member,1,cycle,0,target==null?Float4.ZERO:target,Float4.ZERO);
        }
        static Command domeState(int id,int slot,int phase,boolean create,Float4 centerYaw,float start,
                                 float lockElapsed,float endElapsed,float radius){
            int flags=phase|(create?STORM_DOME_CONTROL_CREATE:0);
            return new Command(STORM_CONTROL_DOME_STATE,0,1,flags,attackScope(id,slot),centerYaw,
                    new Float4(start,lockElapsed,endElapsed,radius));
        }
        static Command domeAssign(int id,int slot,int member,int rank){
            return new Command(STORM_CONTROL_DOME_ASSIGN,member,1,rank,attackScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        static Command domePause(int id,int slot,int flow,float elapsed){
            return new Command(STORM_CONTROL_DOME_PAUSE,0,1,flow,attackScope(id,slot),new Float4(elapsed,0,0,0),Float4.ZERO);
        }
        static Command domeRelease(int id,int slot,int member){
            return new Command(STORM_CONTROL_DOME_RELEASE,member,1,0,attackScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        static Command helixState(int id,int slot,int phase,boolean create,int frameCount,float start,
                                  float thrust,float end,float reference,float speed,float cap,float exitPlane,boolean terrainReady){
            int flags=phase|(create?STORM_DOME_CONTROL_CREATE:0);
            return new Command(STORM_CONTROL_HELIX_STATE,frameCount,1,flags,attackScope(id,slot),
                    new Float4(start,thrust,end,reference),new Float4(speed,cap,exitPlane,terrainReady?1:0));
        }
        static Command helixCancel(int id,int slot){
            return new Command(STORM_CONTROL_HELIX_STATE,0,1,0,attackScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        static Command helixAssign(int id,int slot,int member,int rank){
            var local=new Float4(HelixTemplate.coordinate(rank,0),HelixTemplate.coordinate(rank,1),
                    HelixTemplate.coordinate(rank,2),0);
            return new Command(STORM_CONTROL_HELIX_ASSIGN,member,1,rank,attackScope(id,slot),local,Float4.ZERO);
        }
        static void helixFrames(List<Command> out,int id,int slot,HelixGatherFrame[] frames){
            if(frames.length>STORM_HELIX_FRAMES)throw new IllegalArgumentException("Too many Helix reference frames");
            long scope=attackScope(id,slot);
            for(int frame=0;frame<frames.length;frame++){
                float[] words=frames[frame].words();
                for(int part=0;part<STORM_HELIX_FRAME_ROWS/2;part++){
                    int offset=part*8;
                    out.add(new Command(STORM_CONTROL_HELIX_FRAME,frame*(STORM_HELIX_FRAME_ROWS/2)+part,
                            1,0,scope,Float4.at(words,offset),Float4.at(words,offset+4)));
                }
            }
        }
        static void helixRecovery(List<Command> out,int id,int slot,HelixGatherRecovery recovery){
            long scope=attackScope(id,slot);
            if(recovery==null){
                out.add(new Command(STORM_CONTROL_HELIX_RECOVERY,0,1,0,scope,Float4.ZERO,Float4.ZERO));
                return;
            }
            float[] words=recovery.words();
            for(int row=0;row<STORM_HELIX_RECOVERY_ROWS;row+=2){
                var second=row+1<STORM_HELIX_RECOVERY_ROWS?Float4.at(words,(row+1)*4):Float4.ZERO;
                out.add(new Command(STORM_CONTROL_HELIX_RECOVERY,row,1,0,scope,Float4.at(words,row*4),second));
            }
        }
        static Command columnState(int slot,ClientboundStormColumnPacket packet,boolean create){
            long scope=columnScope(packet.id(),slot);
            if(packet.abort())return new Command(STORM_CONTROL_COLUMN_STATE,0,1,0,scope,Float4.ZERO,Float4.ZERO);
            int flags=(packet.resume()?2:1)|(create?STORM_DOME_CONTROL_CREATE:0);
            return new Command(STORM_CONTROL_COLUMN_STATE,packet.seed(),1,flags,scope,
                    new Float4(packet.x(),packet.y(),packet.z(),packet.gatherY()),
                    new Float4(packet.start(),AllayStarfall.elapsed(packet.assemble(),packet.start()),
                            AllayStarfall.elapsed(packet.touch(),packet.start()),AllayStarfall.elapsed(packet.end(),packet.start())));
        }
        static Command columnAssign(int id,int slot,int member,int rank){
            return new Command(STORM_CONTROL_COLUMN_ASSIGN,member,1,rank,columnScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        static Command columnRelease(int id,int slot,int member){
            return new Command(STORM_CONTROL_COLUMN_RELEASE,member,1,0,columnScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        static void columnRestore(List<Command> out,int id,int slot,int token,float sampleElapsed,StarfallColumnSnapshot snapshot){
            long scope=columnScope(id,slot);
            out.add(new Command(STORM_CONTROL_COLUMN_RESTORE,token,STORM_COLUMN_MEMBERS,0,scope,
                    new Float4(sampleElapsed,0,0,0),Float4.ZERO));
            float[] words=snapshot.values();
            for(int rank=0;rank<STORM_COLUMN_MEMBERS;rank++){
                int base=rank*StarfallColumnSnapshot.STRIDE;
                for(int part=0;part<STORM_COLUMN_RESTORE_PARTS;part++){
                    int offset=base+part*8;
                    var second=part+1<STORM_COLUMN_RESTORE_PARTS?Float4.at(words,offset+4)
                            :new Float4(words[offset+4],words[offset+5],0,0);
                    out.add(new Command(STORM_CONTROL_COLUMN_RESTORE_ROW,rank*STORM_COLUMN_RESTORE_PARTS+part,
                            1,0,scope,Float4.at(words,offset),second));
                }
            }
        }
        private static long columnScope(int id,int slot){
            if(id<=0||slot<0||slot>=STORM_COLUMN_SLOTS)throw new IllegalArgumentException("Invalid Starfall column identity");
            return Integer.toUnsignedLong(id)|((long)slot<<32);
        }
        static Command domeCancel(int id,int slot){
            return new Command(STORM_CONTROL_DOME_STATE,0,1,0,attackScope(id,slot),Float4.ZERO,Float4.ZERO);
        }
        private static long attackScope(int id,int slot){
            if(id<=0||slot<0||slot>=STORM_DOME_SLOTS)throw new IllegalArgumentException("Invalid regional attack identity");
            return Integer.toUnsignedLong(id)|((long)slot<<32);
        }
        private void write(ByteBuffer out,long identity){
            out.putInt(operation).putInt(member).putInt(span).putInt(flags).putLong(generation==0?identity:generation).putLong(scope);
            a.write(out);b.write(out);
        }
    }
    record Parameters(long identity,boolean active,int population,int seed,float x,float y,float z,float radius,
                      float rotation,float conveyor,float conveyorRate,float omega,float seconds,float speed,boolean domeAvailable) {
        static Parameters inactive(long identity,float seconds){
            return new Parameters(identity,false,0,0,0,0,0,2,0,0,0,0,seconds,6,false);
        }
    }
    record Target(int id,float x0,float y0,float z0,float x1,float y1,float z1) {
        static final Target ABSENT=new Target(0,0,0,0,0,0,0);
        private void write(ByteBuffer out){
            out.putFloat(x0).putFloat(y0).putFloat(z0).putInt(0)
                    .putFloat(x1).putFloat(y1).putFloat(z1).putInt(id);
        }
    }
    record Geometry(long revision,int count,byte[] rows) {}
    record Tick(long sequence,long tick,long gameTime,Parameters parameters,List<Command> commands,StormControlTerrain terrain,
                Target target,long projectedSequence,List<Float4> players,boolean positionSnapshot,Geometry geometry,
                List<ColumnSnapshotClient.NativeCapture> columnCaptures,boolean featherImpacts) {
        int bytes(){
            return STORM_TICK_HEADER_BYTES+commands.size()*STORM_CONTROL_BYTES+(terrain==null?0:terrain.bytes())
                    +players.size()*STORM_PLAYER_BYTES;
        }
        int encode(ByteBuffer out){
            var p=parameters;
            out.clear();
            out.putInt(STORM_TICK_MAGIC).putInt(ABI).putInt(commands.size()).putInt(p.population())
                    .putLong(sequence).putLong(tick).putLong(p.identity()).putInt((p.active()?1:0)|(p.domeAvailable()?2:0)).putInt(p.seed())
                    .putFloat(p.x()).putFloat(p.y()).putFloat(p.z()).putFloat(p.radius())
                    .putFloat(p.rotation()).putFloat(p.conveyor()).putFloat(p.conveyorRate()).putFloat(p.omega())
                    .putFloat(p.seconds()).putFloat(p.speed()).putInt(terrain==null?0:terrain.rows()).putInt(terrain==null?0:terrain.pages());
            target.write(out);
            out.putLong(projectedSequence).putLong(projectedSequence==0?0:p.identity());
            out.putInt(players.size()).putInt(positionSnapshot?1:0).putInt(columnCaptures.size()).putInt(featherImpacts?1:0);
            for(int i=0;i<STORM_COLUMN_CAPTURE_LIMIT;i++){
                if(i<columnCaptures.size()){
                    var capture=columnCaptures.get(i);
                    out.putInt(capture.slot()).putInt(capture.request().id()).putInt(capture.request().token()).putInt(0);
                }else out.putLong(0).putLong(0);
            }
            for(var command:commands)command.write(out,p.identity());
            if(terrain!=null)terrain.write(out);
            for(var player:players)player.write(out);
            return out.position();
        }
    }
    private final ArrayDeque<Tick> log=new ArrayDeque<>();
    private final ArrayDeque<Tick> unsent=new ArrayDeque<>();
    private final ArrayDeque<Tick> inFlight=new ArrayDeque<>(RT_SLOTS);
    private long written,committed,received,lastTick,lastIdentity;
    private boolean failed,lastActive;

    void append(long tick,Parameters parameters,List<Command> commands){
        append(tick,parameters,commands,null);
    }
    void append(long tick,Parameters parameters,List<Command> commands,StormControlTerrain terrain){
        append(tick,parameters,commands,terrain,Target.ABSENT);
    }
    void append(long tick,Parameters parameters,List<Command> commands,StormControlTerrain terrain,Target target){
        append(tick,parameters,commands,terrain,target,0);
    }
    void append(long tick,Parameters parameters,List<Command> commands,StormControlTerrain terrain,Target target,
                long projectedSequence){
        append(tick,parameters,commands,terrain,target,projectedSequence,List.of(),false);
    }
    void append(long tick,Parameters parameters,List<Command> commands,StormControlTerrain terrain,Target target,
                long projectedSequence,List<Float4> players,boolean positionSnapshot){
        append(tick,parameters,commands,terrain,target,projectedSequence,players,positionSnapshot,null,List.of());
    }
    void append(long tick,Parameters parameters,List<Command> commands,StormControlTerrain terrain,Target target,
                long projectedSequence,List<Float4> players,boolean positionSnapshot,Geometry geometry,
                List<ColumnSnapshotClient.NativeCapture> columnCaptures){
        append(tick,tick,parameters,commands,terrain,target,projectedSequence,players,positionSnapshot,geometry,columnCaptures,false);
    }
    long nextLogicalTick(){return written==0?1:lastTick+1;}
    long nextSequence(){return written+1;}
    void appendWorldSample(long gameTime,Parameters parameters,List<Command> commands,StormControlTerrain terrain,
                           Target target,long projectedSequence,List<Float4> players,boolean positionSnapshot,
                           Geometry geometry,List<ColumnSnapshotClient.NativeCapture> columnCaptures,boolean featherImpacts){
        append(nextLogicalTick(),gameTime,parameters,commands,terrain,target,projectedSequence,players,
                positionSnapshot,geometry,columnCaptures,featherImpacts);
    }
    private void append(long tick,long gameTime,Parameters parameters,List<Command> commands,StormControlTerrain terrain,
                        Target target,long projectedSequence,List<Float4> players,boolean positionSnapshot,Geometry geometry,
                        List<ColumnSnapshotClient.NativeCapture> columnCaptures,boolean featherImpacts){
        boolean idleBoundary=!lastActive&&parameters.identity()>lastIdentity;
        boolean tickOrder=written==0||tick>lastTick&&(tick==lastTick+1||idleBoundary);
        if(tick<0||!tickOrder)
            throw new IllegalArgumentException("Out-of-order Storm logical tick "+tick+" after "+lastTick);
        if(parameters.identity()<=0||commands.size()>STORM_MAX_CONTROLS)
            throw new IllegalArgumentException("Invalid Storm input identity="+parameters.identity()+", controls="+commands.size());
        if(players.size()>STORM_PLAYER_LIMIT||positionSnapshot&&players.isEmpty())
            throw new IllegalArgumentException("Invalid Storm player sample");
        if(columnCaptures.size()>STORM_COLUMN_CAPTURE_LIMIT)
            throw new IllegalArgumentException("Too many Starfall column captures");
        var input=new Tick(written+1,tick,gameTime,parameters,List.copyOf(commands),terrain,target,projectedSequence,
                List.copyOf(players),positionSnapshot,geometry,List.copyOf(columnCaptures),featherImpacts);
        log.addLast(input);unsent.addLast(input);written++;lastTick=tick;
        lastIdentity=parameters.identity();lastActive=parameters.active();
    }
    Tick sendable(){return !failed&&inFlight.size()<RT_SLOTS?unsent.peekFirst():null;}
    void sent(Tick input){
        if(sendable()!=input)throw new IllegalStateException("Storm inputs must be sent in order");
        unsent.removeFirst();inFlight.addLast(input);
    }
    Tick awaitingReceipt(){return inFlight.peekFirst();}
    void receive(long sequence,boolean applied){
        var head=inFlight.peekFirst();
        if(head==null||head.sequence()!=sequence||sequence!=received+1)
            throw new IllegalStateException("Storm receipt out of order");
        inFlight.removeFirst();received++;
        if(applied&&sequence==committed+1){log.removeFirst();committed=sequence;}
        if(!applied)failed=true;
    }
    void stop(){failed=true;}
    long committed(){return committed;}
    long received(){return received;}
    int retained(){return log.size();}
    int queued(){return unsent.size();}
    int inFlight(){return inFlight.size();}
}
