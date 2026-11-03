package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.util.*;

/** Qualification for the R5 experiment, not permission to change rendering. */
public final class TargetSkyMaskPreflight {
    private TargetSkyMaskPreflight() {}
    public record Decision(boolean eligible,String reason,List<Integer> retainedOutputs) {}
    public static Decision inspect(String family,Collection<Integer> retainedOutputs,boolean laterConsumers) {
        var retained=retainedOutputs.stream().sorted().distinct().toList();
        if(!family.equals("complementary"))return new Decision(false,"unsupported native sky contract",retained);
        if(!retained.isEmpty())return new Decision(false,"terminal writes retained attachments "+retained,retained);
        if(laterConsumers)return new Decision(false,"later target passes consume terminal outputs",retained);
        // The absence of known history is insufficient to prove the sampler footprint.
        return new Decision(false,"consumer sampler footprint and full replay not qualified",retained);
    }
    public static Map<String,Object> metadata(Decision decision) {
        return Map.of("candidate","target-terminal-mask-r5","requested",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,
                "effective",false,"status","rejected","reason",decision.reason(),"retained_outputs",decision.retainedOutputs());
    }
    public static Map<String,Object> inactiveMetadata() {
        return Map.of("requested",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,"effective",false,"reason","target sky not constructed");
    }
}
