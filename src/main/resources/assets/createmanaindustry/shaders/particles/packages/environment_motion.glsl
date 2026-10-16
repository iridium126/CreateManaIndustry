// Minecraft velocities are blocks/tick; Body velocities are blocks/second.
uniform bool uFallMirrorReady;
uniform uint uFallMirrorOffset;
uniform bool uFullBoundingBoxLadders;
uint materialAt(ivec3 block) {
    int lookup=worldSlot((block>>4)+uWorldOriginSection);
    if(lookup==WORLD_EMPTY||(lookup>=0&&(worldLookupFlags&4u)==0u))return 0u;
    ivec3 cached=ivec3(0);int slot=-2;uvec4 cell;uint base;
    return worldCell(block,vec3(block),vec3(block+1),cached,slot,cell,base)?cell.w:0u;
}
bool climbableMaterial(uint metadata){
    uint kind=(metadata>>16u)&255u;
    return kind==9u||(kind==2u&&(metadata&(4u<<24u))==0u);
}
bool onEnvironmentClimbable(Body b,uint feetMetadata){
    if(!uFullBoundingBoxLadders)return climbableMaterial(feetMetadata);
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    ivec3 first=ivec3(floor(lo)),last=ivec3(ceil(hi))-ivec3(1);
    if(worldHasNoMaterials(first,last))return false;
    ivec3 cached=ivec3(0);int slot=-2;
    // NeoForge CommonHooks.isLivingOnLadder traverses Y, X, Z with open upper faces.
    for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++)for(int z=first.z;z<=last.z;z++){
        uvec4 cell;uint base;
        if(worldCell(ivec3(x,y,z),lo,hi,cached,slot,cell,base)&&climbableMaterial(cell.w))return true;
    }
    return false;
}
vec3 prepareEnvironmentMotion(inout Body b,uint i) {
    vec3 motion=b.velocityGround.xyz*uDt;
    if(!uEnvironmentReady||environment[i].control.z==0u)return motion;
    uint at=environmentWorkspace(i);
    uint metadata=materialAt(ivec3(floor(b.positionMass.xyz-vec3(0,b.extentYaw.y,0))));
    bool climbing=onEnvironmentClimbable(b,metadata);
    vec3 stuck=environmentSamples[at].position.xyz;
    environmentSamples[at].position.w=b.velocityGround.y;
    if(int(environment[i].control.x)>0)environmentSamples[at].contact.x=0;
    // Air and ordinary ground need only the incoming vertical velocity for a
    // possible landing. Do not read/write the full 48-byte motion workspace.
    if(!climbing&&dot(stuck,stuck)<=1e-7)return motion;
    PackageEnvironmentSample state=environmentSamples[at];
    if(climbing){
        b.velocityGround.xz=clamp(b.velocityGround.xz,vec2(-3),vec2(3));b.velocityGround.y=max(b.velocityGround.y,-3.);motion=b.velocityGround.xyz*uDt;state.contact.z=floatBitsToInt(0.);
#ifdef CMI_ACTIVE_BODIES
        links[environmentMirrorIndex(i)]=0u;
#endif
    }
    state.position.w=b.velocityGround.y;
    if(dot(state.position.xyz,state.position.xyz)>1e-7){motion*=state.position.xyz;b.velocityGround.xyz=vec3(0);state.position.xyz=vec3(0);state.contact.w=1;}
    environmentSamples[at]=state;return motion;
}
void finishEnvironmentMotion(inout Body b,uint i,out bool powder,out bool berryDamage,out uint effects,out bool nativeMaterial,out float landedDistance) {
    powder=false;berryDamage=false;effects=0u;nativeMaterial=false;landedDistance=0.;if(!uEnvironmentReady)return;
    uint at=environmentWorkspace(i);PackageEnvironmentSample state=environmentSamples[at];
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz+1e-7,hi=b.positionMass.xyz+b.extentYaw.xyz-1e-7;
    // Subtracting 1e-7 from an integral face rounds back to the integer in float.
    // ceil(max)-1 preserves Entity's open upper contact boundary at section-sized coordinates.
    ivec3 first=ivec3(floor(lo)),last=ivec3(ceil(b.positionMass.xyz+b.extentYaw.xyz))-ivec3(1),cached=ivec3(0);int slot=-2;
    bool noMaterials=worldHasNoMaterials(ivec3(floor(lo-vec3(0,.2,0))),last);
    bool resetFall=false,freezing=false;
    ivec3 insideFeet=ivec3(floor(b.positionMass.xyz-vec3(0,b.extentYaw.y,0)));
    // Match Entity.checkInsideBlocks: X outermost, Z innermost; the last stuck multiplier wins.
    if(!noMaterials)for(int x=first.x;x<=last.x;x++)for(int y=first.y;y<=last.y;y++)for(int z=first.z;z<=last.z;z++){
        ivec3 block=ivec3(x,y,z);uvec4 cell;uint base;if(!worldCell(block,lo,hi,cached,slot,cell,base))continue;
        uint material=(cell.w>>16u)&255u;
        if(material!=0u){nativeMaterial=true;effects|=1u<<uint((x-first.x)*9+(y-first.y)*3+z-first.z);}
        if(material>=12u&&material!=45u)effects|=1u<<30u;
        if(material==1u){powder=true;freezing=freezing||((cell.w>>24u)&2u)==0u;
            if(all(equal(block,insideFeet))){state.position.xyz=vec3(.9,1.5,.9);resetFall=true;}}
        else if(material==3u){state.position.xyz=vec3(.25,.05,.25);resetFall=true;}
        else if(material==4u){state.position.xyz=vec3(.8,.75,.8);resetFall=true;
            berryDamage=berryDamage||((cell.w>>24u)>0u&&any(greaterThanEqual(abs(b.positionMass.xz-b.previousSleep.xz),vec2(.003))));}
        else if(material==5u&&b.velocityGround.w==0.&&b.positionMass.y-b.extentYaw.y-float(y)<=.9375-1e-7&&b.velocityGround.y< -1.6){
            vec2 distance=abs(vec2(x,z)+.5-b.positionMass.xz);
            if(any(greaterThan(distance+1e-7,vec2(.4375+b.extentYaw.x)))){
                if(b.velocityGround.y< -2.6)b.velocityGround.xz*= -1./b.velocityGround.y;
                b.velocityGround.y=-1.;resetFall=true;effects|=1u<<31u;
            }
        } else if(material==11u){
            bool down=((cell.w>>24u)&1u)!=0u;
            bool top=((cell.w>>24u)&2u)!=0u;
            b.velocityGround.y=down?max(top?-18.:-6.,b.velocityGround.y-.6):min(top?36.:14.,b.velocityGround.y+(top?2.:1.2));
            resetFall=true;
        }
    }
    float fall=max(0.,intBitsToFloat(state.contact.z));
    fall=resetFall?0.:fall+max(0.,b.previousSleep.y-b.positionMass.y);
    if(b.velocityGround.w!=0.){landedDistance=fall;fall=0.;}
    state.contact.z=floatBitsToInt(fall);
    if(uFallMirrorReady)links[uFallMirrorOffset+i]=floatBitsToUint(fall);
    if(state.contact.x!=0)state.contact.w=1;
    state.contact.x=freezing?min(140,state.contact.x+1):max(0,state.contact.x-2);
    ivec3 feet=ivec3(floor(b.positionMass.xyz-vec3(0,b.extentYaw.y+.2,0)));
    uint groundMaterial=noMaterials?0u:(materialAt(feet)>>16u)&255u;
    if(b.velocityGround.w!=0.){effects|=1u<<27u;if(groundMaterial!=0u)nativeMaterial=true;}
    if(b.velocityGround.w!=0.&&groundMaterial>=12u&&groundMaterial!=45u)effects|=1u<<30u;
    if(landedDistance>0.)effects|=1u<<28u;
    if(b.velocityGround.w!=0.){
        if((groundMaterial==6u||groundMaterial==7u||groundMaterial==45u)&&state.position.w<0.)b.velocityGround.y=-state.position.w*(groundMaterial==6u?1.:groundMaterial==7u?.66:.26);
        if(groundMaterial==6u&&abs(b.velocityGround.y)<2.)b.velocityGround.xz*=.4+abs(b.velocityGround.y)*.01;
    }
    uint insideMaterial=noMaterials?0u:(materialAt(ivec3(floor(b.positionMass.xyz-vec3(0,b.extentYaw.y,0))))>>16u)&255u;
    if(insideMaterial==5u||insideMaterial==8u||groundMaterial==5u||groundMaterial==8u)b.velocityGround.xz*=.4;
    if(state.contact.y!=0){
        uint feetMetadata=materialAt(insideFeet);
        bool walkablePowder=((feetMetadata>>16u)&255u)==1u&&(feetMetadata&(1u<<24u))!=0u;
        // LivingEntity.handleRelativeFrictionAndCalculateMovement applies this
        // after move (and its inside callbacks), before the next gravity stage.
        if(onEnvironmentClimbable(b,feetMetadata)||walkablePowder)b.velocityGround.y=4.;
        state.contact.y=0;
    }
    environmentSamples[at].contact=state.contact;
    if(!noMaterials)environmentSamples[at].position=state.position;
}
