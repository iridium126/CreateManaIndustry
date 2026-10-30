package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import java.util.ArrayList;
import static com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackStatusReport.*;

/** Optional integrations are visited only when present. No renderer is prepared by this query. */
public final class ShaderPackStatusSnapshot {
    private ShaderPackStatusSnapshot(){}
    public static ShaderPackStatusReport capture(){
        var sections=new ArrayList<Section>();
        boolean iris=CreateManaIndustry.IRIS_ACTIVE;
        boolean active=iris&&net.irisshaders.iris.Iris.getCurrentPack().isPresent()
                &&net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline;
        if(iris)sections.add(ShaderPackSession.current().packStatus(active));
        else sections.add(new Section("Pack",State.UNAVAILABLE,"none","Iris unavailable"));
        if(active&&CreateManaIndustry.IRISVEIL_ACTIVE)sections.add(com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.diagnostics());
        else sections.add(new Section("MODEL",!ClientConfig.particleEnabled||!ClientConfig.shaderPackIntegration?State.OFF:active?State.UNAVAILABLE:State.INACTIVE,
                ClientConfig.particleEnabled?"self-drawn":"disabled",active&&ClientConfig.particleEnabled&&ClientConfig.shaderPackIntegration?"Iris/Veil native bridge unavailable":""));
        if(CreateManaIndustry.VEIL_ACTIVE&&active)sections.add(IrisWorldEffects.diagnostics());
        else sections.add(new Section("World",CreateManaIndustry.VEIL_ACTIVE?State.INACTIVE:State.UNAVAILABLE,
                active?"none":"vanilla",CreateManaIndustry.VEIL_ACTIVE?"":"Veil unavailable"));
        if(iris)sections.add(ShaderPackSession.current().skyStatus(active));
        else sections.add(new Section("Sky/Cloud",State.INACTIVE,"vanilla",""));
        if(iris){
            sections.add(active?com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.diagnostics():new Section("Packages",State.INACTIVE,"Create",""));
            if(CreateManaIndustry.HEX_ACTIVE)sections.add(active?com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.diagnostics():new Section("Hex",State.INACTIVE,"native",""));
        }
        if(CreateManaIndustry.VEIL_ACTIVE)sections.add(com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe.diagnostics());
        return new ShaderPackStatusReport(sections);
    }
}
