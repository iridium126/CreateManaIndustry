package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Lazy, client-thread collision preparation. No world access, waits or uploads on workers. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageCollisionRuntime implements PackageCollisionCaptureSchedule.Work,PackageCollisionUploadSchedule.Work {
    private static final int MAX_SECTIONS=1024,MAX_REQUEST_SECTIONS=64;
    private static volatile PackageCollisionRuntime current;
    private final ClientLevel level;
    private final int configuredSections;
    private int allocatedSections;
    private final ExecutorService workers;
    private final PackageCollisionCache cache;
    private final PackageWorldCollisionSource source;
    private final PackageLightCache lights;
    private final PackageWorldLightSource lightSource;
    private PackageLightGpu lightGpu;
    private final java.util.Set<Long> lightColumns=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> dirtyLightColumns=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int lightPriority;
    private PackageCollisionGpu gpu;
    private final PackageMovingCollisionCache movingCache;
    private PackageMovingCollisionSources movingSources;
    private PackageMovingCollisionGpu movingGpu;
    private boolean movingAvailable,captureMovingFirst=true,uploadMovingFirst=true;
    private final java.util.HashSet<PackageMovingGeometry.Key> movingSeen=new java.util.HashSet<>(PackageMovingCollisionGpu.MAX_STRUCTURES);
    private final java.util.ArrayList<PackageMovingGeometry.Key> movingMissing=new java.util.ArrayList<>(PackageMovingCollisionGpu.MAX_STRUCTURES);
    private final long[] discoverySamples=new long[128];
    private int discoveryCursor,discoveryCount;
    private long lastDiscoveryNanos,discoveryOverruns;
    private boolean gpuRequested,collisionRequested,lightRequested;
    private String gpuError="";
    private final PackageCollisionHistory geometryHistory=new PackageCollisionHistory();
    private int historyTicks=PackageSimulationClock.HISTORY_TICKS;

    private PackageCollisionRuntime(ClientLevel level) {
        this.level=level;
        configuredSections=ClientConfig.packageCollisionMaxSections;
        workers=Executors.newFixedThreadPool(2,task->{
            Thread thread=new Thread(task,"CMI package collision bake");thread.setDaemon(true);return thread;
        });
        cache=new PackageCollisionCache(workers,MAX_SECTIONS);
        cache.listener(new PackageCollisionCache.Listener() {
            @Override public void invalidated(PackageCollisionCache.Section section,long revision){if(gpu!=null)gpu.invalidate(section,revision);}
            @Override public void published(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot){if(gpu!=null)gpu.offer(section,snapshot);}
            @Override public void historical(PackageCollisionCache.Section section,PackageCollisionCache.Snapshot snapshot){if(gpu!=null)gpu.offerHistorical(section,snapshot);}
            @Override public void removed(PackageCollisionCache.Section section){if(gpu!=null)gpu.forget(section);}
            @Override public void cleared(){if(gpu!=null)gpu.clear();}
        });
        source=new PackageWorldCollisionSource(level);
        lights=new PackageLightCache(MAX_SECTIONS);lightSource=new PackageWorldLightSource(level);
        lights.listener(new PackageLightCache.Listener() {
            @Override public void invalidated(PackageCollisionCache.Section section,long revision){if(lightGpu!=null)lightGpu.invalidate(section,revision);}
            @Override public void published(PackageCollisionCache.Section section,PackageLightCache.Snapshot snapshot){if(lightGpu!=null)lightGpu.offer(section,snapshot);}
        });
        movingCache=new PackageMovingCollisionCache(workers,PackageMovingCollisionGpu.MAX_STRUCTURES);
    }
    private static void owner() {
        if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("Package collision preparation off client thread");
    }
    public static PackageCollisionRuntime forLevel(ClientLevel level) {
        owner();if(current!=null && current.level!=level)closeCurrent();
        if(current==null)current=new PackageCollisionRuntime(level);return current;
    }
    /** Only queue section identities. The bounded tick performs every actual world query. */
    public boolean request(AABB sweptBounds) {
        owner();int[] bounds=sections(sweptBounds.inflate(1));if(bounds==null)return false;
        boolean accepted=true;
        for(int x=bounds[0];x<=bounds[3];x++)for(int y=bounds[1];y<=bounds[4];y++)for(int z=bounds[2];z<=bounds[5];z++)
        {
            var section=new PackageCollisionCache.Section(x,y,z);accepted&=requestCollisionSection(section);accepted&=requestLightSection(section);
        }
        return accepted;
    }
    public PackageLightGpu lightGpu(){owner();return lightGpu;}
    /** Light-only requests do not require a collision shape bake (e.g. chain tracks). */
    public boolean requestLight(AABB bounds) {
        owner();int[] sections=sections(bounds);if(sections==null)return false;boolean accepted=true;
        for(int x=sections[0];x<=sections[3];x++)for(int y=sections[1];y<=sections[4];y++)for(int z=sections[2];z<=sections[5];z++) {
            accepted&=requestLightSection(new PackageCollisionCache.Section(x,y,z));
        }
        return accepted;
    }
    /** A chain offer may transfer only once every requested world-light section is uploaded. */
    public boolean gpuLightCovered(AABB bounds) {
        owner();if(lightGpu==null || !gpuError.isEmpty())return false;
        int[] boundsInSections=sections(bounds);if(boundsInSections==null)return false;
        for(int x=boundsInSections[0];x<=boundsInSections[3];x++)
            for(int y=boundsInSections[1];y<=boundsInSections[4];y++)
                for(int z=boundsInSections[2];z<=boundsInSections[5];z++) {
                    var section=new PackageCollisionCache.Section(x,y,z);var snapshot=lights.snapshot(section);
                    if(snapshot==null || !lightGpu.covered(section,snapshot.revision()))return false;
                }
        return true;
    }
    public boolean requestLightSection(PackageCollisionCache.Section section) {
        owner();if(!PackageLightRequests.coordinate(section.x()) || !PackageLightRequests.coordinate(section.y()) || !PackageLightRequests.coordinate(section.z()))return false;
        if(!lights.request(section))return false;gpuRequested=true;lightRequested=true;
        lightColumns.add(PackageLightCache.column(section.x(),section.z()));
        return lightGpu==null || lightGpu.reserve(section,lights.revision(section));
    }
    /** Queue a GPU look-ahead request. This only records a numeric section; capture stays budgeted on tick. */
    public boolean requestCollisionSection(PackageCollisionCache.Section section) {
        owner();if(!PackageLightRequests.coordinate(section.x()) || !PackageLightRequests.coordinate(section.y())
                || !PackageLightRequests.coordinate(section.z()))return false;
        if(!cache.requestDemand(section))return false;
        gpuRequested=true;collisionRequested=true;gpuError="";
        var snapshot=cache.snapshot(section);
        if(snapshot!=null && gpu!=null && !gpu.covered(section,snapshot.revision()))return gpu.offer(section,snapshot);
        return true;
    }
    /** Replace the protected section set only from a completed scan of the current atlas table. */
    public void beginPackageUsage(long tableVersion) {
        owner();if(gpu!=null && gpu.beginPackageUsage(tableVersion))cache.clearPackageUsage();
    }
    public void touchPackageUsage(long tableVersion,int row) {
        owner();if(gpu==null)return;
        var section=gpu.touchPackageUsage(tableVersion,row);if(section!=null)cache.protectPackageUsage(section);
    }
    public static void releasePackageUsage(ClientLevel level) {
        owner();if(current==null || current.level!=level)return;
        current.cache.clearPackageUsage();if(current.gpu!=null)current.gpu.clearPackageUsage();
    }
    /** Coverage includes all touched sections in the latest CPU capture. */
    public boolean covered(AABB sweptBounds) {
        owner();int[] bounds=sections(sweptBounds);if(bounds==null)return false;
        for(int x=bounds[0];x<=bounds[3];x++)for(int y=bounds[1];y<=bounds[4];y++)for(int z=bounds[2];z<=bounds[5];z++)
            if(cache.snapshot(new PackageCollisionCache.Section(x,y,z))==null)return false;
        return true;
    }
    public PackageCollisionCache.Snapshot snapshot(PackageCollisionCache.Section section){owner();return cache.snapshot(section);}
    /** Sweeps need the extra one-cell guard for neighbouring overhanging shapes. */
    public boolean gpuCovered(AABB sweptBounds) {
        owner();if(gpu==null || !gpuError.isEmpty() || !movingAvailable || !movingCache.posesReady() || movingGpu==null || !movingGpu.covered(movingCache.entries(),sweptBounds))return false;
        int[] bounds=sections(sweptBounds.inflate(1));if(bounds==null)return false;
        for(int x=bounds[0];x<=bounds[3];x++)for(int y=bounds[1];y<=bounds[4];y++)for(int z=bounds[2];z<=bounds[5];z++) {
            var section=new PackageCollisionCache.Section(x,y,z);var snapshot=cache.snapshot(section);
            if(snapshot==null) {
                // A block edit clears the current CPU snapshot while the section is recaptured.
                // Keep using the last complete atlas entry during that interval; sections with
                // no prior GPU data still wait for their first complete capture/upload.
                requestCollisionSection(section);
                if(!gpu.covered(section))return false;
                continue;
            }
            if(!gpu.covered(section,snapshot.revision())) {
                requestCollisionSection(section);
                // offer() stages a replacement without removing the visible version. Unsupported
                // geometry explicitly revokes it, so this fallback cannot mask a known bad shape.
                if(!gpu.covered(section))return false;
            }
        }
        return true;
    }
    /** Only call inside an owned GL boundary; creating a view never enables package takeover. */
    public PackageCollisionGpu.View view(int originSectionX,int originSectionY,int originSectionZ) {
        owner();if(gpu==null || !gpuError.isEmpty())throw new IllegalStateException("Package world GPU atlas unavailable");
        return gpu.view(originSectionX,originSectionY,originSectionZ);
    }
    /** Captured poses only: obtaining a scene never queries a mutable world. */
    public MovingScene movingView(int originSectionX,int originSectionY,int originSectionZ) {
        owner();if(movingGpu==null||!gpuError.isEmpty())throw new IllegalStateException("Moving atlas unavailable");
        var views=movingAvailable?movingGpu.views(movingCache.entries(),movingCache.posesReady(),originSectionX*16.,originSectionY*16.,originSectionZ*16.):movingGpu.unavailableViews();
        return new MovingScene(movingGpu,views);
    }
    public boolean hasStaticHistory(long tick){owner();return gpu!=null&&geometryHistory.contains(tick);}
    public void staticGeometryConsumed(long tick){owner();geometryHistory.consumed(tick);}
    public PackageCollisionGpu.View historicalView(long tick,int x,int y,int z){
        owner();var versions=geometryHistory.get(tick);if(versions==null||gpu==null)throw new IllegalStateException("Static geometry history unavailable");
        return gpu.historicalView(x,y,z,versions);
    }
    public boolean hasMovingHistory(long tick){owner();return movingCache.hasHistory(tick);}
    public MovingScene movingView(long tick,int x,int y,int z){
        owner();boolean confirmedEmptyScene=movingAvailable&&movingCache.entries().isEmpty();
        var frame=movingCache.simulationFrame(tick,confirmedEmptyScene);
        return new MovingScene(movingGpu,frame==null?movingGpu.unavailableViews():movingGpu.views(frame,true,x*16.,y*16.,z*16.));
    }
    public static final class MovingScene implements AutoCloseable {
        private final PackageMovingCollisionGpu gpu;private final java.util.List<PackageMovingCollisionGpu.View> views;private boolean closed;
        private MovingScene(PackageMovingCollisionGpu gpu,java.util.List<PackageMovingCollisionGpu.View> views){this.gpu=gpu;this.views=views;}
        public java.util.List<PackageMovingCollisionGpu.View> views(){if(closed)throw new IllegalStateException("Moving scene closed");return views;}
        /** A missing pose bank is a frame-level pause, not evidence that every package is unsafe. */
        public boolean ready(){if(closed)throw new IllegalStateException("Moving scene closed");for(var view:views)if(!view.ready())return false;return true;}
        @Override public void close(){if(!closed){gpu.endViews(views);closed=true;}}
    }
    /** Lazy uploads at the engine frame boundary, never from block events or collision workers. */
    public static boolean pumpGpu() {
        if(current==null || !current.gpuRequested)return false;owner();
        if(Minecraft.getInstance().level!=current.level){closeCurrent();return true;}
        try {
            if(current.collisionRequested && current.gpu==null) {
                current.allocatedSections=PackageCollisionGpu.deviceCapacity(current.configuredSections,PackageCollisionGpu.DEFAULT_SHAPES,
                        org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE));
                if(current.allocatedSections==0)throw new IllegalStateException("Device cannot fit one package collision section");
                current.gpu=new PackageCollisionGpu(current.allocatedSections,PackageCollisionGpu.DEFAULT_SHAPES);
                current.cache.forEachReady(current.gpu::offer);
            }
            if(current.collisionRequested) {
                current.gpu.retainHistory(current.geometryHistory.retained());
                if(current.movingGpu==null)current.movingGpu=new PackageMovingCollisionGpu();
                current.movingGpu.tickRate(current.level.tickRateManager().tickrate());
                current.movingGpu.sync(current.movingCache.entries(),current.movingCache.retainedGeometry());
            }
            if(current.lightRequested && current.lightGpu==null){current.lightGpu=new PackageLightGpu(MAX_SECTIONS);current.lights.forEachRequested(current.lightGpu::reserve);current.lights.snapshots().forEach(current.lightGpu::offer);}
            // A chain-only workload needs light data, not the collision atlas or moving-world scan.
            if(!current.collisionRequested) {
                if(current.lightGpu!=null)current.lightGpu.pump(PackageCollisionGpu.DEFAULT_UPLOAD_BYTES,ClientConfig.packageMainThreadBudgetNanos());
                return true;
            }
            PackageCollisionUploadSchedule.pump(current,current.uploadMovingFirst,current.lightPriority==0,current.lightGpu!=null,
                    PackageCollisionGpu.DEFAULT_UPLOAD_BYTES,ClientConfig.packageMainThreadBudgetNanos(),System::nanoTime);
            current.uploadMovingFirst=!current.uploadMovingFirst;
        }catch(RuntimeException failure) {
            current.gpuError=failure.getClass().getSimpleName()+": "+failure.getMessage();current.gpuRequested=false;
            if(current.gpu!=null){current.gpu.close();current.gpu=null;}
            if(current.movingGpu!=null){current.movingGpu.close();current.movingGpu=null;}
            if(current.lightGpu!=null){current.lightGpu.close();current.lightGpu=null;}
            CreateManaIndustry.LOGGER.error("[CMI packages] collision GPU upload failed; coverage revoked",failure);
        }
        return true;
    }
    private static int[] sections(AABB bounds) {
        double[] coordinates={bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ};
        int[] result=new int[6];long count=1;
        for(int i=0;i<6;i++) {
            double value=Math.floor(coordinates[i]/16);
            // Actual block queries use signed int coordinates (including the tall Allay dimension).
            if(!Double.isFinite(value) || value<Integer.MIN_VALUE/16 || value>Integer.MAX_VALUE/16-1)return null;
            result[i]=(int)value;
        }
        for(int i=0;i<3;i++){long extent=(long)result[i+3]-result[i]+1;if(extent<=0 || extent>MAX_REQUEST_SECTIONS)return null;count*=extent;}
        return count>MAX_REQUEST_SECTIONS?null:result;
    }
    public static void blockChanged(ClientLevel level,BlockPos position) {
        var runtime=current;if(runtime==null||runtime.level!=level)return;
        owner();
        if(runtime.collisionRequested)invalidateStaticCollisionIfChanged(runtime,position);
        if(runtime.movingSources!=null)runtime.movingSources.blockChanged(position);
    }
    public static void blockEntityChanged(ClientLevel level,BlockPos position){
        blockChanged(level,position);
    }
    private static void invalidateStaticCollisionIfChanged(PackageCollisionRuntime runtime,BlockPos position) {
        runtime.cache.invalidateChangedBlock(position.getX(),position.getY(),position.getZ(),runtime.source);
    }
    /** A Sable plot edit changes moving geometry only; do not recapture the parent world's static cells. */
    public static void movingBlockChanged(ClientLevel level,BlockPos position) {
        if(current!=null && current.collisionRequested && current.level==level && current.movingSources!=null)
            current.movingSources.blockChanged(position);
    }
    /** Light engine notifications can arrive off-thread; only coalesce requested numeric columns. */
    public static void lightChanged(ClientLevel level,net.minecraft.core.SectionPos section) {
        var runtime=current;if(runtime==null || runtime.level!=level)return;
        long column=PackageLightCache.column(section.x(),section.z());
        if(runtime.lightColumns.contains(column))runtime.dirtyLightColumns.add(column);
    }
    public static void contraptionChanged(com.simibubi.create.content.contraptions.Contraption contraption){if(current!=null&&current.movingSources!=null){owner();current.movingSources.contraptionChanged(contraption);}}
    public static String report() {
        owner();if(current==null)return "Package collisions: inactive";
        String gpuStatus=current.gpu==null?"GPU "+(!current.gpuError.isEmpty()?current.gpuError:!current.collisionRequested?"collision atlas not requested":current.gpuRequested?"queued":"inactive"):
                gpuReport(current.gpu.stats());
        return "Package collisions: configured/allocated capacity="+current.configuredSections+"/"+current.allocatedSections+"; "+current.cache.readyCount()+"/"+current.cache.size()+" CPU sections ready, capture "
                +String.format(java.util.Locale.ROOT,"%.3f",current.cache.lastCaptureNanos()/1_000_000.0)
                +" ms, p50/p95 "+String.format(java.util.Locale.ROOT,"%.3f/%.3f",current.cache.capturePercentile(.5)/1_000_000.0,current.cache.capturePercentile(.95)/1_000_000.0)
                +String.format(java.util.Locale.ROOT," ms (%.3f ms soft budget), overruns ",ClientConfig.packageMainThreadBudgetMs)+current.cache.overrunCount()+", LRU evictions/rejects "+current.cache.capacityEvictions()+"/"+current.cache.capacityRejections()
                +"; worker bake p50/p95 "+String.format(java.util.Locale.ROOT,"%.3f/%.3f",current.cache.bakePercentile(.5)/1e6,current.cache.bakePercentile(.95)/1e6)+" ms; "+gpuStatus
                +"; moving="+current.movingCache.entries().size()+", poses="+(current.movingAvailable&&current.movingCache.posesReady())
                +", capture="+String.format(java.util.Locale.ROOT,"%.3f",current.movingCache.lastCaptureNanos()/1e6)+" ms, overruns="+current.movingCache.overruns()
                +(current.movingGpu==null?"":", uploads="+current.movingGpu.uploadedBytes()+" B, skipped="+current.movingGpu.skippedViews())
                +String.format(java.util.Locale.ROOT,", discovery p50/p95=%.3f/%.3f ms, last=%.3f ms, overruns=%d",
                        current.discoveryPercentile(.5)/1e6,current.discoveryPercentile(.95)/1e6,
                        current.lastDiscoveryNanos/1e6,current.discoveryOverruns)
                +(current.movingSources==null?"":" "+current.movingSources.error())
                +"; light="+(current.lights.size()-current.lights.pending())+"/"+current.lights.size()
                +String.format(java.util.Locale.ROOT,", capture=%.3f ms, p50/p95=%.3f/%.3f ms, overruns=%d, failures=%d",current.lights.lastNanos()/1e6,current.lights.capturePercentile(.5)/1e6,current.lights.capturePercentile(.95)/1e6,current.lights.overruns(),current.lights.sourceFailures())
                +(current.lightGpu==null?"":", uploads="+current.lightGpu.uploadedBytes()+" B, skipped="+current.lightGpu.skipped());
    }
    private void recordDiscovery(long nanos,long budget) {
        lastDiscoveryNanos=nanos;discoverySamples[discoveryCursor]=nanos;
        discoveryCursor=(discoveryCursor+1)%discoverySamples.length;
        discoveryCount=Math.min(discoveryCount+1,discoverySamples.length);
        if(nanos>budget)discoveryOverruns++;
    }
    private long discoveryPercentile(double q) {
        if(discoveryCount==0)return 0;
        long[] sorted=java.util.Arrays.copyOf(discoverySamples,discoveryCount);
        java.util.Arrays.sort(sorted);
        return sorted[Math.max(0,(int)Math.ceil(discoveryCount*q)-1)];
    }
    private static String gpuReport(PackageCollisionGpu.Stats stats) {
        return String.format(java.util.Locale.ROOT,"GPU %d/%d ready, pending=%d retired=%d, uploads=%d B; upload p50/p95=%.3f/%.3f ms, overruns=%d; LRU evictions=%d; shape/capacity rejects=%d/%d, skipped views=%d",
                stats.ready(),stats.residents(),stats.pending(),stats.retired(),stats.uploadedBytes(),stats.p50Nanos()/1e6,stats.p95Nanos()/1e6,stats.overruns(),stats.evictions(),stats.shapeRejections(),stats.capacityRejections(),stats.skippedViews());
    }
    public static void closeCurrent() {
        owner();if(current==null)return;
        if(current.gpu!=null){current.gpu.close();current.gpu=null;}
        if(current.movingGpu!=null){current.movingGpu.close();current.movingGpu=null;}current.movingCache.clear();
        if(current.lightGpu!=null){current.lightGpu.close();current.lightGpu=null;}current.lights.clear();
        current.cache.clear();
        // Let already queued immutable packing tasks finish, so their global worker accounting
        // is released. Shutdown never waits; no task has a level reference.
        current.workers.shutdown();current=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(current==null)return;
        if(Minecraft.getInstance().level!=current.level){closeCurrent();return;}
        if(!current.gpuRequested)return;
        if(!current.collisionRequested) {
            if(!current.lightRequested)return;
            long started=System.nanoTime(),budget=ClientConfig.packageMainThreadBudgetNanos();
            var dirty=current.dirtyLightColumns.iterator();
            while(dirty.hasNext() && System.nanoTime()-started<budget) {
                long column=dirty.next();current.dirtyLightColumns.remove(column);
                current.lights.invalidateColumn((int)(column>>32),(int)column);
            }
            current.lights.tick(current.lightSource,Math.max(0,budget-(System.nanoTime()-started)));
            return;
        }
        if(current.movingSources==null){current.movingSources=new PackageMovingCollisionSources(current.level);current.movingSources.onInvalidated(current.movingCache::invalidate);}
        long budget=ClientConfig.packageMainThreadBudgetNanos();
        PackageCollisionCaptureSchedule.tick(current,current.captureMovingFirst,current.lightPriority==0,budget,System::nanoTime);
        current.historyTicks=Math.max(current.historyTicks,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming.historyTicks(current.level.tickRateManager().tickrate()));
        current.movingCache.tickRate(current.level.tickRateManager().tickrate());
        var input=PackageClientInputs.current(current.level);
        current.movingCache.captureHistory(input.first(),input.last(),current.movingAvailable);
        var versions=current.cache.versions();
        current.geometryHistory.capture(input.first(),input.last(),versions,current.historyTicks);
        current.captureMovingFirst=!current.captureMovingFirst;
        current.lightPriority=(current.lightPriority+1)%3;
    }
    @Override public long uploaded(PackageCollisionUploadSchedule.Atlas atlas){
        owner();return switch(atlas){case WORLD->gpu.uploadedBytes();case MOVING->movingGpu.uploadedBytes();case LIGHT->lightGpu==null?0:lightGpu.uploadedBytes();};
    }
    @Override public void upload(PackageCollisionUploadSchedule.Atlas atlas,int bytes,long nanos){
        owner();switch(atlas){case WORLD->gpu.pump(bytes,nanos);case MOVING->movingGpu.pump(bytes,nanos);case LIGHT->{if(lightGpu!=null)lightGpu.pump(bytes,nanos);}}
    }
    @Override public void invalidateLight(long deadline) {
        owner();
        var dirty=dirtyLightColumns.iterator();
        while(dirty.hasNext() && System.nanoTime()-deadline<0) {
            long column=dirty.next();dirtyLightColumns.remove(column);
            lights.invalidateColumn((int)(column>>32),(int)column);
        }
    }
    @Override public void captureLight(long budget){owner();if(lightRequested)lights.tick(lightSource,budget);}
    @Override public void captureMoving(long budget){owner();movingCache.tick(budget);}
    @Override public void captureWorld(long budget){owner();cache.tick(source,budget);}
    @Override public void discoverMoving(long deadline) {
        owner();
        long started=System.nanoTime(),budget=Math.max(0,deadline-started);
        try{var found=movingSources.discover(deadline);movingAvailable=movingSources.error().isEmpty();
            if(movingAvailable) {
                movingSeen.clear();movingMissing.clear();
                for(var candidate:found) {
                    if(System.nanoTime()-deadline>=0){movingAvailable=false;break;}
                    movingSeen.add(candidate.key());movingAvailable&=movingCache.offer(candidate);
                }
                if(movingAvailable) {
                    for(var entry:movingCache.entries()) {
                        if(System.nanoTime()-deadline>=0){movingAvailable=false;break;}
                        if(!movingSeen.contains(entry.source.key()))movingMissing.add(entry.source.key());
                    }
                    if(movingAvailable)for(var key:movingMissing)movingCache.remove(key);
                }
            }
        }catch(RuntimeException | LinkageError unavailable){movingAvailable=false;}
        recordDiscovery(System.nanoTime()-started,budget);
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event){chunkChanged(event);}
    @SubscribeEvent public static void chunkUnloaded(ChunkEvent.Unload event){chunkChanged(event);}
    @SubscribeEvent public static void movingEntityJoined(EntityJoinLevelEvent event){movingEntityChanged(event.getLevel(),event.getEntity());}
    @SubscribeEvent public static void movingEntityLeft(EntityLeaveLevelEvent event){movingEntityRemoved(event.getLevel(),event.getEntity());}
    private static void movingEntityChanged(net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity entity) {
        if(current!=null && current.collisionRequested && level==current.level && entity instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity)
            current.movingAvailable=false;
    }
    private static void movingEntityRemoved(net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity entity) {
        if(current==null||!current.collisionRequested||level!=current.level
                ||!(entity instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity contraption))return;
        var sources=current.movingSources;
        if(sources!=null&&sources.forget(contraption)){
            current.movingCache.remove(new PackageMovingGeometry.Key(0,contraption.getUUID()));
            return;
        }
        // If the source was never captured, membership is still unknown and discovery must recover it.
        current.movingAvailable=false;
    }
    private static void chunkChanged(ChunkEvent event) {
        if(current!=null && event.getLevel()==current.level) {
            var position=event.getChunk().getPos();
            if(current.collisionRequested){current.cache.invalidateChunk(position.x,position.z);
                if(current.movingSources!=null)current.movingSources.chunkChanged(position.x,position.z);}
            if(current.lightRequested)current.lights.invalidateColumn(position.x,position.z);
        }
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if(current!=null && event.getLevel()==current.level) {
            PackageAuthorityClient.closeAll("Package world unloaded",true);
            closeCurrent();
        }
    }
    @SubscribeEvent public static void stopped(GameShuttingDownEvent event){PackageAuthorityClient.closeAll("Client shutdown",false);closeCurrent();}
}
