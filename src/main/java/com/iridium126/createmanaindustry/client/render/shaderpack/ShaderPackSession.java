package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.materialmap.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.file.Path;
import java.util.*;

/** One loading/pipeline generation. All source IO is performed by begin(), never by draw callers. */
public final class ShaderPackSession {
    private static long sequence;
    private static boolean bypassNext;
    private static String retryCause="";
    private static ShaderPackSession current=empty();
    private final long generation=++sequence;
    private final Path root;
    private final ShaderPackInventory inventory;
    private final ShaderPatchPlan patch;
    private final NativeRiftPatchPlan rift;
    private final PackCapabilities capabilities;
    private ShaderPackOptions options;
    private Object pipeline;
    private int width=-1,height=-1;
    private long revision;
    private boolean mapsApplied, retryUsed;
    private String failure="";
    private long nativeRevision=-1;
    private boolean nativeReady;
    private float nativeGamma=1;
    private String nativeReason="active programs not checked";
    private boolean riftReady;
    private String riftReason="effective sky programs not checked";
    private int cloudPrograms;
    public void linkedCloudProgram(){cloudPrograms++;}
    private ShaderPackSession(Path root,ShaderPackInventory inventory,boolean bypass) {
        this.root=root;this.inventory=inventory;
        patch=bypass?ShaderPatchPlan.disabled("unmodified retry"):ShaderPatchPlan.prepare(inventory);
        var combined=new TreeMap<>(inventory.sources());combined.putAll(patch.edits());
        rift=bypass?NativeRiftPatchPlan.disabled("unmodified retry"):NativeRiftPatchPlan.prepare(ShaderPackInventory.of(combined));
        capabilities=PackCapabilities.detect(inventory,patch);
    }
    private static ShaderPackSession empty(){return new ShaderPackSession(null,ShaderPackInventory.of(Map.of()),true);}
    public static ShaderPackSession current(){return current;}
    static ShaderPackSession candidate(ShaderPackInventory inventory){return new ShaderPackSession(null,inventory,false);}
    public static void begin(Path root) {
        boolean bypass=bypassNext;bypassNext=false;
        String cause=retryCause;retryCause="";
        IrisShadowTextures.clear();
        try {current=new ShaderPackSession(root,ShaderPackInventory.read(root),bypass);}
        catch(Exception failure){current=empty();current.failure="inventory unavailable: "+failure.getMessage();}
        if(bypass)current.failure=cause;
        CreateManaIndustry.LOGGER.info("[CMI shaderpack] generation {}: {} ({})",current.generation,current.capabilities.family(),current.patch.reason());
    }
    public static void options(ShaderPackOptions options){current.options=options;}
    public long generation(){return generation;}
    public long revision(){return generation*1_000_003L+revision;}
    public ShaderPackInventory inventory(){return inventory;}
    public ShaderPatchPlan patch(){return patch;}
    public NativeRiftPatchPlan rift(){return rift;}
    public boolean riftReady(){return rift.enabled()&&riftReady;}
    public String riftReason(){return rift.enabled()?riftReason:rift.reason();}
    public void verifyRiftPrograms(net.irisshaders.iris.shaderpack.programs.ProgramSet programs) {
        riftReady=false;
        if(!rift.enabled()){riftReason=rift.reason();return;}
        for(var requirement:rift.programs()) {
            var sources=programs.getComposite(net.irisshaders.iris.shaderpack.loading.ProgramArrayId.valueOf(requirement.array()));
            var source=requirement.index()<sources.length?sources[requirement.index()]:null;
            if(source==null||!source.isValid()) {
                if(!requirement.required())continue;
                riftReason="required sky "+requirement.array()+requirement.index()+" disabled/unavailable";return;
            }
            if(!source.getFragmentSource().orElse("").contains(requirement.marker())) {
                riftReason="active "+requirement.array()+requirement.index()+" lacks rift contract "+requirement.marker();return;
            }
        }
        riftReady=true;riftReason="native "+rift.family()+" sky/celestial/cloud programs confirmed";
    }
    public PackCapabilities capabilities(){return capabilities;}
    public ShaderPackOptions options(){return options;}
    public void observe(Object pipeline,int width,int height) {
        if(this.pipeline!=pipeline||this.width!=width||this.height!=height){this.pipeline=pipeline;this.width=width;this.height=height;revision++;}
    }
    public void verifyNativePrograms(net.irisshaders.iris.shaderpack.programs.ProgramSet programs){
        if(nativeRevision==revision())return;
        nativeRevision=revision();nativeReady=false;nativeGamma=1;
        var contract=capabilities.nativeScene();
        if(contract==null){nativeReady=true;nativeReason="existing native contract";return;}
        var composites=programs.getComposite(net.irisshaders.iris.shaderpack.loading.ProgramArrayId.Composite);
        int index=contract.compositeIndex();
        if(index>=composites.length||composites[index]==null||!composites[index].isValid()){nativeReason="native composite"+index+" disabled/unavailable";return;}
        String source=composites[index].getFragmentSource().orElse("");
        if(!contract.acceptsActiveSource(source)){nativeReason="effective composite"+index+" no longer satisfies "+contract.id();return;}
        for(var replacement:programs.getPackDirectives().getTextureMap().keySet())if(replacement.first().equals("colortex"+contract.buffer())&&replacement.second()==net.irisshaders.iris.gl.texture.TextureType.TEXTURE_2D&&replacement.third()==net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL){nativeReason="native scene sampler overridden by custom 2D texture";return;}
        if(!programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().containsKey(contract.buffer())){nativeReason="native colortex"+contract.buffer()+" not declared by active pipeline";return;}
        nativeGamma=contract.effectiveGamma();nativeReady=true;nativeReason="effective composite"+index+" confirmed; colortex"+contract.buffer()+" gamma="+nativeGamma;
    }
    public boolean nativeReady(){return capabilities.nativeScene()==null||nativeReady;}
    public float nativeGamma(){return nativeGamma;}
    public String nativeReason(){return nativeReason;}
    public String transform(Path path,String original) {
        if(root==null||!path.getFileSystem().equals(root.getFileSystem())||!path.normalize().startsWith(root.normalize()))return original;
        String relative=root.relativize(path).toString();
        return rift.transform(relative,patch.transform(relative,original));
    }
    /** Copies maps before validation. A rejection aborts this candidate, never publishes a partial map. */
    public BlockIdMapContext apply(BlockIdMapContext original) {
        if(!patch.enabled())return original;
        var blocks=new Int2ObjectLinkedOpenHashMap<List<BlockEntry>>(original.blockProperties());
        var items=new Object2IntOpenHashMap<NamespacedId>(original.itemIds());items.defaultReturnValue(-1);
        int id=patch.blockId();
        if(patch.donorId()>=0) {
            var donor=blocks.get(patch.donorId());var replacement=blocks.get(patch.replacementId());
            if(donor==null||donor.isEmpty()||replacement==null||replacement.isEmpty())throw reject("effective donor mapping absent");
            var merged=new ArrayList<>(replacement);merged.addAll(donor);blocks.put(patch.replacementId(),List.copyOf(merged));
            for(var entry:items.object2IntEntrySet())if(entry.getIntValue()==patch.donorId())entry.setValue(patch.replacementId());
        } else if(blocks.containsKey(id))throw reject("effective material ID collision: "+id);
        blocks.put(id,List.of(new BlockEntry(new NamespacedId("createmanaindustry","molten_salt_fuel_tank"),Map.of("lit","true"))));
        mapsApplied=true;return new BlockIdMapContext(blocks,items);
    }
    private IllegalStateException reject(String reason){failure=reason;return new IllegalStateException("CMI shader patch rejected: "+reason);}
    public boolean requestUnmodifiedRetry() {
        if((!patch.enabled()&&!rift.enabled())||retryUsed)return false;
        retryUsed=true;bypassNext=true;retryCause="candidate source/material loading failed; see Iris log";return true;
    }
    public static void finishRetry(){bypassNext=false;retryCause="";}
    public String report() {
        return "session="+generation+"/"+revision+" source="+inventory.digest().substring(0,12)
                +" family="+capabilities.family()+" world="+capabilities.worldPath()
                +" colortex="+inventory.colorBuffers()+"\ncolored-light="+(mapsApplied?"candidate applied":patch.reason())
                +"; "+capabilities.evidence()+(capabilities.nativeScene()==null?"":"; native-program="+nativeReason)
                +"\nrift-sky="+rift.family()+"; "+riftReason()+(failure.isEmpty()?"":"; failure="+failure)
                +"\ntarget-sky="+(net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof RiftIrisPipeline p?p.cmi$targetSkyReport():"no active Iris pipeline")
                +"\ntarget-cloud=native pack definitions/options in isolated target sky; no replacement cloud layer"
                +"\nstorm-cloud="+(cloudPrograms>0?"native linked programs="+cloudPrograms:"no active native cloud uniforms; clouds may be disabled")+"; visibility follows effective pack cloud options; height=pack native; phase=integrated x0.1; radius=clamp(8R,96,384)";
    }
    /** Iris calls this factory later than source loading; compiled-program failure retries the original pack once. */
    public static void pipelineFailed() {
        ShaderPackSession failed=current;
        if((!failed.patch.enabled()&&!failed.rift.enabled())||failed.retryUsed)return;
        failed.retryUsed=true;
        net.minecraft.client.Minecraft.getInstance().tell(()->{
            if(current!=failed||Iris.getCurrentPack().isEmpty())return;
            bypassNext=true;
            retryCause="candidate pipeline compilation failed; original pack restored (see Iris log)";
            try {Iris.reload();}catch(Exception failure){CreateManaIndustry.LOGGER.warn("[CMI shaderpack] unmodified reload failed",failure);}
            finally {finishRetry();}
        });
    }
}
