package com.iridium126.createmanaindustry.client.particles.hex;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.client.ClientRenderHelper;
import at.petrak.hexcasting.api.client.HexPatternRenderHolder;
import at.petrak.hexcasting.client.ClientTickCounter;
import at.petrak.hexcasting.client.render.RenderLib;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;
import java.util.*;

/** Explicit pass ownership. Native cancellation is never inferred from a player's UUID alone. */
public final class HexPatternService {
    private static HexGpuBackend backend;private static int budgetMiB;private static boolean reload,failed;
    private static final ThreadLocal<HexPass> ENTITY=new ThreadLocal<>();private static boolean replay;private static int playerDepth;
    private static final Map<HexPattern,HexShape> shapes=new LinkedHashMap<>(16,.75f,true);
    private static final IdentityHashMap<HexPatternRenderHolder,Orbit> orbits=new IdentityHashMap<>();
    private static final Map<Object,UiCache> uiCaches=new WeakHashMap<>();
    private static final List<HexGpuBackend.Draw> world=new ArrayList<>();private static final List<Player> worldOwners=new ArrayList<>();
    private static final List<Player> worldPlayers=new ArrayList<>();
    private static final Comparator<Player> WORLD_DEPTH=Comparator.comparingDouble(HexPatternService::worldDepth);
    private static final Set<Player> accepted=new HashSet<>(),seen=new HashSet<>();private static final Map<Player,Replay> worldReplay=new HashMap<>();
    private static HexGpuBackend.Prepared worldBatch;private static int depth;private static long frame;private static boolean mainScope,prepared,drawn;
    private static Camera camera;private static DeltaTracker delta;private static ClientLevel level;
    private static final Matrix4f view=new Matrix4f(),projection=new Matrix4f();
    private static UiBatch ui;private static int nestedUi;private static final HexPassState state=new HexPassState();
    private static final class Orbit {final HexGpuBackend.Draw[] draws=new HexGpuBackend.Draw[3];HexPattern pattern;HexShape shape;long seen;int color,time=Integer.MIN_VALUE;Object pigment;}
    private static final class Owner {at.petrak.hexcasting.api.pigment.FrozenPigment pigment;at.petrak.hexcasting.api.pigment.ColorProvider provider;}
    private static final Map<Player,Owner> owners=new WeakHashMap<>();
    private record Replay(Player player,float partial,Matrix4f pose) {}
    private static final class UiCache {final List<HexUiLayout> layouts=new ArrayList<>();final List<HexGpuBackend.Draw> draws=new ArrayList<>();}
    private static final class UiBatch {
        // Retain the UI owner and graphics context for future batch integration.
        @SuppressWarnings("unused")
        final Object owner;
        @SuppressWarnings("unused") // Retain the graphics context for future batch integration.
        final GuiGraphics graphics;
        final UiCache cache;final List<HexGpuBackend.Draw> draws=new ArrayList<>();final List<Runnable> nativeDraws=new ArrayList<>();
        final Matrix4f view=new Matrix4f(RenderSystem.getModelViewMatrix()),projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        int index,patternIndex;boolean failed,flushed;HexMetrics.Scope capture;
        UiBatch(Object owner,GuiGraphics graphics){this.owner=owner;this.graphics=graphics;cache=uiCaches.computeIfAbsent(owner,k->new UiCache());}
    }
    private HexPatternService(){}
    public static boolean enabled(){return ClientConfig.particleEnabled&&ClientConfig.hexPatternRedirect&&CreateManaIndustry.HEX_ACTIVE;}
    private static boolean ready(){if(!enabled()||!org.lwjgl.opengl.GL.getCapabilities().OpenGL45)return false;
        if(backend!=null&&(budgetMiB!=ClientConfig.hexPatternGpuBudgetMiB||reload)){if(!backend.idle())return false;backend.close();backend=null;shapes.clear();orbits.clear();uiCaches.clear();failed=false;reload=false;}
        if(failed)return false;
        if(backend==null){budgetMiB=ClientConfig.hexPatternGpuBudgetMiB;backend=new HexGpuBackend((long)budgetMiB*1024*1024,ParticlePrograms::loadParticlePlain);}return true;
    }
    public static void requestReload(){reload=true;failed=false;if(CreateManaIndustry.IRIS_ACTIVE)com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.invalidate();}
    public static String status(){return backend==null?"Hex GPU idle":"Hex GPU "+backend.allocatedBytes()+"/"+((long)budgetMiB*1024*1024)+"B, cached="+backend.hits()+", generated="+backend.generated()+", source uploads="+backend.uploadedBytes()+"B, parameter uploads="+backend.transferredBytes()+"B, draw submissions="+backend.drawSubmissions()+", fallback="+backend.fallbacks()+" ("+backend.fallbackReason()+")"+(CreateManaIndustry.IRIS_ACTIVE?", shadow="+com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.status():"")+HexMetrics.report();}
    public static void beginFrame(boolean main){if(depth++!=0)return;mainScope=main;frame++;prepared=drawn=false;accepted.clear();seen.clear();worldReplay.clear();camera=null;delta=null;}
    public static void capture(Camera c,Matrix4f v,Matrix4f p,DeltaTracker d){if(depth==1&&mainScope){camera=c;view.set(v);projection.set(p);delta=d;}}
    public static void endFrame(){try{if(depth==1){if(worldBatch!=null){worldBatch.close();worldBatch=null;}camera=null;delta=null;accepted.clear();worldReplay.clear();orbits.values().removeIf(e->e.seen+2<frame);trimShapes();}}finally{if(depth<=0)throw new IllegalStateException("Hex frame scope");depth--;}}
    public static void beginPlayerRender(){playerDepth++;}
    public static void endPlayerRender(){if(playerDepth<=0)throw new IllegalStateException("Hex player scope");playerDepth--;}
    public static HexPass enterEntity(boolean shadow){HexPass previous=ENTITY.get();ENTITY.set(shadow?HexPass.IRIS_SHADOW:HexPass.WORLD_MAIN);return previous;}
    public static void leaveEntity(HexPass previous){if(previous==null)ENTITY.remove();else ENTITY.set(previous);}
    private static HexShape shape(HexPattern pattern){return shapes.computeIfAbsent(pattern,p->{
        var center=p.getCenter(1);float dx=0,dy=0;for(var v:p.toLines(1,Vec2.ZERO)){dx=Math.max(dx,Math.abs(v.x-center.x));dy=Math.max(dy,Math.abs(v.y-center.y));}
        float scale=Math.min(3.8f,Math.min(6.4f/dx,6.4f/dy));var positions=new ArrayList<>(p.toLines(scale,p.getCenter(scale).negated()));
        for(int i=0;i<positions.size();i++)positions.set(i,new Vec2(positions.get(i).x,-positions.get(i).y));
        return new HexShape(positions,RenderLib.findDupIndices(p.positions()),HexShape.Style.ORBIT,true,false);
    });}
    private static void trimShapes(){if(shapes.size()<=HexGpuBackend.MAX_INSTANCES)return;var active=new HashSet<HexPattern>();for(var e:orbits.values())active.add(e.pattern);var it=shapes.entrySet().iterator();while(shapes.size()>HexGpuBackend.MAX_INSTANCES&&it.hasNext())if(!active.contains(it.next().getKey()))it.remove();}
    private static int screen(int color){return (color&0xff000000)|(((color>>16&255)+255)/2<<16)|(((color>>8&255)+255)/2<<8)|((color&255)+255)/2;}
    private static void orbitMatrix(Matrix4f output,Matrix4f base,Player player,float partial,int order,int life){
        float time=(float)player.level().getGameTime()+partial;
        float angle=((time*(float)Math.sin(order*12.543565f)*3.4f*(order/12.43f))%360+(1+order)*45);
        float scale=Math.min(1,life/5f)/24;
        output.set(base).rotateY((float)Math.toRadians(angle));
        output.translate(0,(float)(1+Math.sin(order)*.75),(float)(.75+Math.cos(order/8.0)*.25+Math.cos(time/(7+order/4))*.065));
        output.scale(scale).translate(0,(float)Math.floor(order/8.0),0).translate(0,(float)Math.sin(time/(7.0+order/8.0)),0);
    }
    private static List<HexGpuBackend.Draw> orbitDraws(Player player,float partial,Matrix4f base,HexPass pass){
        var holders=IClientXplatAbstractions.INSTANCE.getClientCastingStack(player).getPatterns();if(holders.size()>HexGpuBackend.MAX_INSTANCES)return null;
        var result=new ArrayList<HexGpuBackend.Draw>(holders.size());var owner=owners.computeIfAbsent(player,p->new Owner());var pigment=IXplatAbstractions.INSTANCE.getPigment(player);
        if(!Objects.equals(owner.pigment,pigment)){owner.pigment=pigment;owner.provider=pigment.getColorProvider();}int k=0;
        for(var holder:holders){Orbit entry=orbits.computeIfAbsent(holder,h->new Orbit());entry.seen=frame;
            HexPattern pattern=holder.getPattern();if(!pattern.equals(entry.pattern)){entry.pattern=pattern;entry.shape=shape(pattern);}
            if(entry.shape.vertices>=HexGpuBackend.VERTEX_STRIDE)return null;
            // Pass-local copies prevent preview transforms from changing a queued world draw.
            int slot=pass==HexPass.WORLD_MAIN?0:pass==HexPass.IRIS_SHADOW?1:2;
            if(entry.draws[slot]==null)entry.draws[slot]=new HexGpuBackend.Draw();var d=entry.draws[slot];d.shape=entry.shape;d.seed=player.hashCode();d.ticks=ClientTickCounter.getTotal();
            int time=Float.floatToIntBits(d.ticks);
            if(entry.time!=time||entry.pigment!=owner.pigment){entry.time=time;entry.pigment=owner.pigment;entry.color=owner.provider.getColor(d.ticks/2f,holder.getColourPos(player.getRandom()));}
            int color=entry.color;
            if(holder.getLifetime()<=60)color=(color&0xffffff)|((int)Math.floor(holder.getLifetime()/60f*255)<<24);
            d.outerTail=d.outerHead=color;d.innerTail=d.innerHead=screen(color);orbitMatrix(d.transform,base,player,partial,k++,holder.getLifetime());result.add(d);
        }return result;
    }
    public static void prepareWorld(){try(var measurement=HexMetrics.phase(HexPass.WORLD_MAIN,"capture+prepare")){prepareWorldData();}}
    private static double worldDepth(Player player){float partial=delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(player));var eye=camera.getPosition();
        double x=player.tickCount==0?player.getX():net.minecraft.util.Mth.lerp(partial,player.xOld,player.getX());double y=player.tickCount==0?player.getY():net.minecraft.util.Mth.lerp(partial,player.yOld,player.getY());double z=player.tickCount==0?player.getZ():net.minecraft.util.Mth.lerp(partial,player.zOld,player.getZ());
        return view.m02()*(x-eye.x)+view.m12()*(y-eye.y)+view.m22()*(z-eye.z)+view.m32();
    }
    private static void prepareWorldData(){if(prepared||depth!=1||!mainScope||camera==null||delta==null)return;prepared=true;
        var mc=Minecraft.getInstance();if(level!=mc.level){level=mc.level;orbits.clear();owners.clear();shapes.clear();}if(!ready()||level==null)return;
        world.clear();worldOwners.clear();accepted.clear();
        // The former pool sorted Hex by owner anchor. Keep far-to-near compositing
        // within the Hex pass without sending any Hex item through particle keygen.
        worldPlayers.clear();worldPlayers.addAll(level.players());worldPlayers.sort(WORLD_DEPTH);
        for(Player player:worldPlayers){
            if(player==camera.getEntity()&&!camera.isDetached()&&!player.isSleeping())continue;
            float partial=delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(player));
            var offset=mc.getEntityRenderDispatcher().getRenderer(player).getRenderOffset(player,partial);var eye=camera.getPosition();
            double x=player.tickCount==0?player.getX():net.minecraft.util.Mth.lerp(partial,player.xOld,player.getX());
            double y=player.tickCount==0?player.getY():net.minecraft.util.Mth.lerp(partial,player.yOld,player.getY());
            double z=player.tickCount==0?player.getZ():net.minecraft.util.Mth.lerp(partial,player.zOld,player.getZ());
            var list=orbitDraws(player,partial,new Matrix4f().translation((float)(x+offset.x-eye.x),(float)(y+offset.y-eye.y),(float)(z+offset.z-eye.z)),HexPass.WORLD_MAIN);
            if(list==null||world.size()+list.size()>HexGpuBackend.MAX_INSTANCES)continue;
            if(!list.isEmpty()){accepted.add(player);for(var d:list){world.add(d);worldOwners.add(player);}}
        }
        if(world.isEmpty())return;
        try(var measurement=HexMetrics.phase(HexPass.WORLD_MAIN,"prepare");var ignored=state.capture()){worldBatch=backend.prepare(world);if(worldBatch==null)accepted.clear();}
        catch(RuntimeException|LinkageError error){accepted.clear();fail(error);}
    }
    public static boolean castingStack(PoseStack pose,Player player,float partial){if(replay||!enabled())return false;HexPass pass=ENTITY.get();
        if(playerDepth>1){if(pass==HexPass.IRIS_SHADOW)return false;pass=HexPass.PLAYER_PREVIEW;}
        if(pass==HexPass.IRIS_SHADOW)return CreateManaIndustry.IRIS_ACTIVE&&com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.accepted(player);
        if(pass==HexPass.WORLD_MAIN){if(depth!=1||!mainScope||worldBatch==null||reload||budgetMiB!=ClientConfig.hexPatternGpuBudgetMiB||level!=Minecraft.getInstance().level||!accepted.contains(player))return false;seen.add(player);worldReplay.putIfAbsent(player,new Replay(player,partial,new Matrix4f(pose.last().pose())));return true;}
        if(!ready())return false;
        try(var measurement=HexMetrics.phase(HexPass.PLAYER_PREVIEW,"prepare+draw");var ignored=state.capture()){
            if(!ready())return false;var list=orbitDraws(player,partial,new Matrix4f(pose.last().pose()),HexPass.PLAYER_PREVIEW);if(list==null||list.isEmpty())return false;
            try(var batch=backend.prepare(list)){if(batch==null)return false;HexPassState.nativeState();backend.drawPlain(batch,new Matrix4f(RenderSystem.getModelViewMatrix()),new Matrix4f(RenderSystem.getProjectionMatrix()));return true;}
        }catch(RuntimeException|LinkageError error){fail(error);return false;}
    }
    public static void drawWorld(){if(drawn||worldBatch==null)return;drawn=true;boolean fallback=false;
        try(var measurement=HexMetrics.phase(HexPass.WORLD_MAIN,"draw");var ignored=state.capture()){
            if(level!=Minecraft.getInstance().level)return;
            if(!enabled()||reload||budgetMiB!=ClientConfig.hexPatternGpuBudgetMiB)fallback=true;
            else {for(int i=0;i<worldOwners.size();i++)if(!seen.contains(worldOwners.get(i)))worldBatch.disable(i);
                worldBatch.uploadCommands();HexPassState.nativeState();backend.drawPlain(worldBatch,view,projection);}
        }catch(RuntimeException|LinkageError error){fail(error);fallback=true;}
        finally{worldBatch.close();worldBatch=null;accepted.clear();orbits.values().removeIf(e->e.seen+2<frame);trimShapes();}
        if(fallback)for(var r:worldReplay.values())replay(r);
    }
    public static List<HexGpuBackend.Draw> shadowDraws(Player player,float partial,Camera camera){var p=camera.getPosition();var mc=Minecraft.getInstance();var o=mc.getEntityRenderDispatcher().getRenderer(player).getRenderOffset(player,partial);
        double x=player.tickCount==0?player.getX():net.minecraft.util.Mth.lerp(partial,player.xOld,player.getX());double y=player.tickCount==0?player.getY():net.minecraft.util.Mth.lerp(partial,player.yOld,player.getY());double z=player.tickCount==0?player.getZ():net.minecraft.util.Mth.lerp(partial,player.zOld,player.getZ());
        return orbitDraws(player,partial,new Matrix4f().translation((float)(x+o.x-p.x),(float)(y+o.y-p.y),(float)(z+o.z-p.z)),HexPass.IRIS_SHADOW);
    }
    public static HexGpuBackend.Prepared prepareBatch(List<HexGpuBackend.Draw> draws){return ready()?backend.prepare(draws):null;}
    public static void bindBatch(HexGpuBackend.Prepared batch,int program){batch.bind(program);}
    private static void replay(Replay r){PoseStack pose=new PoseStack();pose.last().pose().set(r.pose);boolean previous=replay;replay=true;try{ClientRenderHelper.renderCastingStack(pose,r.player,r.partial);}finally{replay=previous;}}
    public static void beginUi(Object owner,GuiGraphics graphics){if(ui!=null){flushUi();nestedUi++;return;}if(!ready())return;try(var ignored=state.capture()){if(ready()){graphics.flush();ui=new UiBatch(owner,graphics);}}catch(RuntimeException|LinkageError error){fail(error);}}
    private static HexGpuBackend.Draw uiDraw(){if(ui.capture==null)ui.capture=HexMetrics.phase(HexPass.SPELL_UI,"capture+prepare+draw");if(ui.index>=HexGpuBackend.MAX_INSTANCES){ui.failed=true;return null;}while(ui.cache.draws.size()<=ui.index)ui.cache.draws.add(new HexGpuBackend.Draw());return ui.cache.draws.get(ui.index++);}
    public static boolean uiPattern(Matrix4f mat,List<Vec2> points,Set<Integer> duplicates,boolean drawLast,int tail,int head,float flow,float readability,float last,double seed,Runnable nativeCall){
        if(replay||ui==null||ui.flushed||nestedUi>0)return false;var style=new HexShape.Style(10,2.5f,.1f,flow,readability,last);int index=ui.patternIndex++;var d=uiDraw();if(d==null){ui.nativeDraws.add(nativeCall);return true;}
        while(ui.cache.layouts.size()<=index)ui.cache.layouts.add(new HexUiLayout());var layout=ui.cache.layouts.get(index);layout.update(points,duplicates,style,drawLast);HexShape shape=layout.shape;
        d.overrideTail=!drawLast&&points.size()>1;d.tailX=points.isEmpty()?0:(points.get(points.size()-1).x-layout.x)/layout.scale;d.tailY=points.isEmpty()?0:(points.get(points.size()-1).y-layout.y)/layout.scale;
        d.shape=shape;d.seed=seed;d.ticks=ClientTickCounter.getTotal();d.transform.set(mat).translate(layout.x,layout.y,0).scale(layout.scale,layout.scale,1);
        d.outer=2.5f/layout.scale;d.inner=1/layout.scale;d.outerZ=0;d.innerZ=1;d.dotRadius=2/layout.scale;
        d.outerTail=tail;d.outerHead=head;d.innerTail=screen(tail);d.innerHead=screen(head);
        d.dotColor=(head&0xff000000)|((int)((head>>16&255)*.9f)<<16)|((int)((head>>8&255)*.9f)<<8)|(int)((head&255)*.9f);
        d.gradient=net.minecraft.client.gui.screens.Screen.hasControlDown()!=at.petrak.hexcasting.api.mod.HexConfig.client().ctrlTogglesOffStrokeOrder();
        ui.draws.add(d);ui.nativeDraws.add(nativeCall);return true;
    }
    private static final HexShape DOT=new HexShape(List.of(Vec2.ZERO),null,new HexShape.Style(1,0,0,0,0,1),true);
    public static boolean uiDot(Matrix4f mat,Vec2 point,float radius,float r,float g,float b,float a,Runnable nativeCall){if(replay||ui==null||ui.flushed||nestedUi>0)return false;var d=uiDraw();if(d==null){ui.nativeDraws.add(nativeCall);return true;}
        d.shape=DOT;d.overrideTail=false;d.seed=0;d.ticks=0;d.transform.set(mat).translate(point.x,point.y,0);d.dotRadius=radius;d.outer=d.inner=0;
        d.dotColor=((int)(a*255)<<24)|((int)(r*255)<<16)|((int)(g*255)<<8)|(int)(b*255);ui.draws.add(d);ui.nativeDraws.add(nativeCall);return true;
    }
    public static void flushUi(){if(ui==null||ui.flushed)return;ui.flushed=true;UiBatch batch=ui;boolean fallback=false;
        if(batch.draws.isEmpty()&&batch.nativeDraws.isEmpty())return;
        try(var measurement=HexMetrics.phase(HexPass.SPELL_UI,"prepare+draw");var ignored=state.capture()){
            if(batch.failed||!ready())fallback=true;
            else try(var prepared=backend.prepare(batch.draws)){if(prepared==null)fallback=true;
                else {HexPassState.nativeState();backend.drawPlain(prepared,batch.view,batch.projection);}}
        }catch(RuntimeException|LinkageError error){fail(error);fallback=true;}
        // Replay after restoring the caller's state, exactly once even if native code throws.
        try{if(fallback)replayUi(batch);}finally{if(batch.capture!=null){batch.capture.close();batch.capture=null;}}
    }
    private static void replayUi(UiBatch batch){boolean prior=replay;replay=true;try{for(var call:batch.nativeDraws)call.run();}finally{replay=prior;}}
    public static void endUi(){if(nestedUi>0){nestedUi--;return;}try{flushUi();}finally{ui=null;}}
    public static void close(){HexMetrics.close();if(worldBatch!=null){worldBatch.close();worldBatch=null;}if(backend!=null){backend.close();backend=null;}orbits.clear();owners.clear();shapes.clear();uiCaches.clear();accepted.clear();reload=failed=false;}
    private static void fail(Throwable error){if(!failed)CreateManaIndustry.LOGGER.warn("[CMI Hex] GPU pass unavailable; preserving native rendering",error);failed=true;}
}
