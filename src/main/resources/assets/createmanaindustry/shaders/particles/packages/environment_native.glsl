// One journal record represents all native callbacks in a logical tick. It preserves X/Y/Z
// traversal and avoids one SSBO sample (and one packet) per overlapping voxel.
bool nativeEnvironmentStep(inout Body b,inout PackageEnvironment e,uint i,uint effects,bool nativeMaterial,float landedDistance) {
    uint workspace=environmentWorkspace(i);
    bool nativeBody=environmentSamples[workspace].timeline.w!=0u||nativeMaterial;
    if(!nativeBody)return false;
    environmentSamples[workspace].timeline.w=1u;uint age=++environmentSamples[workspace].timeline.x;
    ivec4 contact=environmentSamples[workspace].contact;
    bool timed=int(e.control.x)>0||contact.x!=0||contact.w!=0||(e.control.w&24u)!=0u;
    // Ordinary air/ground performs no environment readback or server work.
    if(!nativeMaterial&&!timed)return true;
    uint written=e.lease.z;
    environmentSamples[environmentRecord(i,written)]=PackageEnvironmentSample(
        ivec4(floatBitsToInt(b.velocityGround.xyz),32),uvec4(uSimulationStep,effects,floatBitsToUint(max(landedDistance,intBitsToFloat(contact.z)))),
        vec4(b.positionMass.xyz,uintBitsToFloat(age)));
    e.lease.z++;
    environmentSamples[workspace].contact.w=0;
    // Pure deterministic material effects keep running. Callbacks that can change the world,
    // health or inventory require a canonical server result before dependent motion continues.
    bool wait=(effects&((1u<<29u)|(1u<<30u)))!=0u;
    if(wait||int(e.control.x)>0||(e.control.w&24u)!=0u||(contact.x>=140&&age%40u==0u)){
        // This can be sampled before another predict dispatch. Record the cause
        // now without rewinding the completed move or losing its previous pose.
        recordPackageFreeze(b,b.positionMass.xyz,FREEZE_ENV_CONFIRMATION,FREEZE_ENVIRONMENT,
                ivec4(e.lease.z-e.lease.w,e.lease.z,e.lease.w,floatBitsToUint(e.state.x)));
        e.control.y=2u;b.previousSleep.w=PACKAGE_COLLISION_FROZEN;
    }
    return true;
}
