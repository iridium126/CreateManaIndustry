package com.iridium126.createmanaindustry.client.particles.shaderpack;

import java.util.Set;
import java.util.TreeMap;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;

/** Exact source and effective option pairs accepted by the full shadow draw comparison. */
final class ParticleShadowQualification {
    record Configuration(String sourceDigest,String optionsDigest) {}

    private static final Set<Configuration> QUALIFIED=Set.of();
    private static long revision=Long.MIN_VALUE;
    private static boolean accepted;

    static boolean qualified(Configuration configuration){
        return QUALIFIED.contains(configuration);
    }

    static boolean current(){
        if(QUALIFIED.isEmpty())return false;
        var session=ShaderPackSession.current();
        var pipeline=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if(!session.ownsPipeline(pipeline))return false;
        if(revision!=session.revision()){
            accepted=false;
            revision=session.revision();
            var options=session.options();
            if(options==null)return false;
            var effective=new TreeMap<String,String>();
            var values=options.getOptionValues();
            options.getOptionSet().getBooleanOptions().keySet().forEach(key->effective.put(key,Boolean.toString(values.getBooleanValueOrDefault(key))));
            options.getOptionSet().getStringOptions().keySet().forEach(key->effective.put(key,values.getStringValueOrDefault(key)));
            String digest=java.util.UUID.nameUUIDFromBytes(effective.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            accepted=qualified(new Configuration(session.inventory().digest(),digest));
        }
        return accepted;
    }

}
