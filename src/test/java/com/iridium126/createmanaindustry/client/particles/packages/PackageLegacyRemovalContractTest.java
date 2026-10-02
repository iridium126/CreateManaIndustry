package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Build resources must contain only the record protocol, never the retired entity handback path. */
class PackageLegacyRemovalContractTest {
    @Test void deletedEntityPathsAndShadersAreAbsentFromProductionResources() throws Exception {
        var loader=getClass().getClassLoader();
        for(String type:List.of("client/particles/packages/PackageFreeNativeRecovery",
                "client/particles/packages/PackageFreeCheckpointGpu","client/particles/packages/PackagePoseCheckpointGpu",
                "client/particles/packages/PackageNativeObserverClient","client/particles/packages/PackageNativeObserverController",
                "client/particles/packages/PackageNativeObserverCommands","client/particles/packages/PackageNativeObserverPatch",
                "content/logistics/gpupackage/PackageNativeDownlink","content/logistics/gpupackage/PackageNativeMembershipRegistry",
                "content/logistics/gpupackage/PackageSableForceHooks"))
            assertNull(loader.getResource("com/iridium126/createmanaindustry/"+type+".class"),type);
        for(String shader:List.of("observer_native_apply.comp","observer_native_validate.comp","observer_native_state.glsl"))
            assertNull(loader.getResource("assets/createmanaindustry/shaders/particles/packages/"+shader),shader);
        try(var input=loader.getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(input);var config=JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();
            var removed=List.of("NativePackagePacketMixin","NativeMotionPacketAccessor","NativePackageLifecycleMixin",
                    "PackageClientSimulationMixin","PackageLivingEntityOwnershipMixin","PackageVisualOwnershipMixin",
                    "PackageRendererOwnershipMixin","PackageEntityRenderOwnershipMixin","PackageFreeGpuPickFilterMixin",
                    "PackageExternalImpulseMixin","PackageLightNativeCollisionMixin","PackageNativeDownlinkMixin");
            for(String section:List.of("mixins","client"))for(var entry:config.getAsJsonArray(section))
                for(String name:removed)assertNotEquals("packages."+name,entry.getAsString());
        }
    }
}
