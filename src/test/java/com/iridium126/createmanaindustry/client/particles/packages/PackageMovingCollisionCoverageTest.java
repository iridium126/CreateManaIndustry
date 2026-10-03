package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.AABB;
import org.objectweb.asm.tree.MethodInsnNode;
import org.junit.jupiter.api.Test;

class PackageMovingCollisionCoverageTest {
    private static final PackageMovingGeometry.Pose IDENTITY =
            new PackageMovingGeometry.Pose(1,0,0,0,1,0,0,0,1,0,0,0);

    @Test void distantUnavailableMovingGeometryDoesNotOverlapAnUnrelatedPackage() {
        var local = new PackageMovingGeometry.Bounds(-4,-2,-4,4,3,4);
        var distant = new PackageMovingGeometry.Pose(1,0,0,0,1,0,0,0,1,512,0,0);
        assertFalse(PackageMovingCollisionCoverage.intersects(local,distant,distant,
                new AABB(0,0,0,1,1,1)));
        assertTrue(PackageMovingCollisionCoverage.intersects(local,distant,distant,
                new AABB(507,-1,-5,517,5,5)));
    }

    @Test void stationarySourceUsesItsTightTransformedBounds() {
        var local = new PackageMovingGeometry.Bounds(0,0,0,16,1,1);
        assertFalse(PackageMovingCollisionCoverage.intersects(local,IDENTITY,IDENTITY,
                new AABB(0,12,0,1,13,1)));
    }

    @Test void sweptBoundsIncludeIntermediateRotationAndTranslation() {
        var local = new PackageMovingGeometry.Bounds(0,0,0,16,1,1);
        var rotated = new PackageMovingGeometry.Pose(0,1,0,-1,0,0,0,0,1,128,0,0);
        var query = new AABB(127,15,-2,130,18,3);
        assertTrue(PackageMovingCollisionCoverage.intersects(local,IDENTITY,rotated,query));
    }

    @Test void gpuReceivesSourceLocalBoundsForMovingPoseShaderToTransform() throws Exception {
        var gpu = PackageCollisionHookContractTest.type(
                "com/iridium126/createmanaindustry/client/particles/packages/PackageMovingCollisionGpu");
        var views = gpu.methods.stream().filter(method -> method.name.equals("views")).findFirst().orElseThrow();
        boolean uploadsLocalBounds=false;
        for(var instruction:views.instructions)if(instruction instanceof MethodInsnNode call
                &&call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageMovingGeometry$Bounds"))
            uploadsLocalBounds|=call.name.equals("x0")||call.name.equals("y0")||call.name.equals("z0")
                    ||call.name.equals("x1")||call.name.equals("y1")||call.name.equals("z1");
        assertTrue(uploadsLocalBounds,"moving_prepare.comp needs local bounds to compute the transformed GPU coarse bound");
        assertFalse(java.util.stream.StreamSupport.stream(views.instructions.spliterator(),false)
                        .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                        .anyMatch(call -> call.owner.equals("com/iridium126/createmanaindustry/client/particles/packages/PackageMovingCollisionCoverage")
                                && call.name.equals("gpuSweptBounds")),
                "pre-transformed world bounds must not be transformed a second time by the shader");
    }
}
