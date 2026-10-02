package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class PackageSableCompatibilityTest {
    @Test void absentModDoesNotLinkTheTypedAdapter() {
        // Sable is compileOnly. This JVM has the math companion for other tests,
        // but none of the Sable sublevel/physics/loader implementation classes.
        assertNull(getClass().getResource("/dev/ryanhcode/sable/Sable.class"));
        assertNull(getClass().getResource("/dev/ryanhcode/sable/sublevel/ClientSubLevel.class"));
        assertNull(PackageMovingCollisionSources.optionalBridge(false, null));
        assertNull(PackageForceClient.optionalBridge(false));
        assertNull(PackageChainFrameScene.optionalBridge(false));
    }

    @Test void commonSourcesHaveNoExternalSableTypesAndAdapterHasNoReflection() throws Exception {
        String root = "/com/iridium126/createmanaindustry/client/particles/packages/";
        for (String suffix : new String[]{"", "$Base", "$CreateSource", "$OptionalBridge"}) {
            try (var stream = getClass().getResourceAsStream(root + "PackageMovingCollisionSources" + suffix + ".class")) {
                assertNotNull(stream);
                assertFalse(new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1).contains("dev/ryanhcode/sable/"));
            }
        }
        for (String name : new String[]{"PackageSableCollisionSources", "PackageSableCollisionSources$Source", "PackageSablePose", "PackageSableForceSources", "PackageSableChainFrames"}) {
            try (var stream = getClass().getResourceAsStream(root + name + ".class")) {
                assertNotNull(stream);
                String bytecode = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("java/lang/reflect/"));
                assertFalse(bytecode.contains("forName"));
                assertFalse(bytecode.contains("getMethod"));
                assertFalse(bytecode.contains("getField"));
            }
        }
        try(var stream=getClass().getResourceAsStream(root+"PackageSableCollisionSources.class")) {
            assertNotNull(stream);String bytecode=new String(stream.readAllBytes(),StandardCharsets.ISO_8859_1);
            assertTrue(bytecode.contains("BlockSubLevelDynamicCollider"));
            assertTrue(bytecode.contains("buildBoxes"));
            assertTrue(bytecode.contains("VoxelColliderData"));
        }
        for(String path:new String[]{root+"PackageForceClient.class",root+"PackageForceClient$OptionalBridge.class",root+"PackageForceScene.class",
                "/com/iridium126/createmanaindustry/content/logistics/gpupackage/PackageForceHooks.class"}){
            try(var stream=getClass().getResourceAsStream(path)){assertNotNull(stream);assertFalse(new String(stream.readAllBytes(),StandardCharsets.ISO_8859_1).contains("dev/ryanhcode/sable/"));}
        }

    }

    @Test void plotBlockChangesInvalidateOnlyMovingCollisionAndMixinIsVersionGated() throws Exception {
        var mixin=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/mixin/packages/sable/PackageSableCollisionPlotMixin");
        var hook=mixin.methods.stream().filter(method->method.name.equals("cmi$invalidateMovingCollision")).findFirst().orElseThrow();
        var inject=hook.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertTrue(PackageCollisionHookContractTest.annotationValueContains(inject,"method",
                "onBlockChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"));
        assertTrue(PackageCollisionHookContractTest.annotationAt(inject,"TAIL"));
        boolean movingOnly=false;
        for(var instruction:hook.instructions)if(instruction instanceof MethodInsnNode call
                && call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageCollisionRuntime")
                && call.name.equals("movingBlockChanged"))movingOnly=true;
        assertTrue(movingOnly,"Sable plot edits should invalidate moving geometry without static-world recapture");

        try(var input=getClass().getClassLoader().getResourceAsStream("createmanaindustry.mixins.json")) {
            assertNotNull(input);var json=JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();
            var clients=json.getAsJsonArray("client").toString();var common=json.getAsJsonArray("mixins").toString();
            assertTrue(clients.contains("packages.sable.PackageSableCollisionPlotMixin"));
            assertFalse(common.contains("PackageSableCollisionPlotMixin"));
        }
        var plugin=PackageCollisionHookContractTest.type("com/iridium126/createmanaindustry/mixin/CMIMixinPlugin");
        var gate=plugin.methods.stream().filter(method->method.name.equals("shouldApplyMixin")).findFirst().orElseThrow();
        boolean packageGate=false,sableGate=false;
        for(var instruction:gate.instructions) {
            if(instruction instanceof LdcInsnNode constant && ".packages.sable.".equals(constant.cst))sableGate=true;
            if(instruction instanceof MethodInsnNode call && call.name.equals("isSupportedVersion")
                    && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Z"))packageGate=true;
        }
        assertTrue(sableGate&&packageGate,"the Sable-specific hook must be gated before its optional target is loaded");
    }
}
