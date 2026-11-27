#pragma once
#include "../particle_common/StormStep.h"
#include "../particle_common/StormTerrain.h"
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>

namespace stormInput {
inline bool vectorFinite(float4 v) {
    return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z)&&std::isfinite(v.w);
}
inline bool targetPointValid(float4 v){
    return std::isfinite(v.x)&&std::abs(v.x)<=31000000.f
        &&std::isfinite(v.y)&&std::abs(v.y)<=31000000.f
        &&std::isfinite(v.z)&&std::abs(v.z)<=31000000.f;
}
inline size_t rowBytes(unsigned count){return size_t(count)*CMI_RESOURCE_STORM_TERRAIN_ROW_BYTES;}
inline size_t cellBytes(unsigned count){return size_t(count)*CMI_RESOURCE_STORM_TERRAIN_PAGE_BYTES;}
inline size_t commandBytes(unsigned count){return size_t(count)*sizeof(StormControl);}
inline size_t playerBytes(unsigned count){return size_t(count)*sizeof(float4);}
inline size_t batchBytes(const StormTick& input){
    if(input.columnCaptureCount)return sizeof(StormDomeBatch);
    return input.positionSnapshot?offsetof(StormDomeBatch,columnCaptures):offsetof(StormDomeBatch,positions);
}
inline size_t maxTerrainUpload(){return rowBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS+CMI_RESOURCE_STORM_TERRAIN_MAX_SECTIONS)+cellBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES);}
inline bool ringCommand(StormControl op){
    return op.op>=CMI_RESOURCE_STORM_CONTROL_RING_STATE&&op.op<=CMI_RESOURCE_STORM_CONTROL_RING_ASSIGN;
}
inline bool regionalCommand(StormControl op){
    return (op.op>=CMI_RESOURCE_STORM_CONTROL_DOME_STATE&&op.op<=CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW)||ringCommand(op);
}
inline bool columnCommand(StormControl op){
    return (op.op>=CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE&&op.op<=CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW)||ringCommand(op);
}
inline bool outerCommand(StormControl op){
    return op.op==CMI_RESOURCE_STORM_CONTROL_OUTER_START||op.op==CMI_RESOURCE_STORM_CONTROL_OUTER_RELEASE;
}
inline bool attackWide(StormControl op){
    switch(op.op){
        case CMI_RESOURCE_STORM_CONTROL_DOME_STATE:
        case CMI_RESOURCE_STORM_CONTROL_DOME_PAUSE:
        case CMI_RESOURCE_STORM_CONTROL_HELIX_STATE:
        case CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME:
        case CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY:
        case CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE:
        case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE:
        case CMI_RESOURCE_STORM_CONTROL_RING_STATE:
        case CMI_RESOURCE_STORM_CONTROL_RING_BASIS:return true;
        case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW:return true;
        default:return false;
    }
}
inline bool memberOnly(StormControl op){
    return op.span==1&&!attackWide(op);
}
inline bool payloadPart(StormControl op){
    return op.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME||op.op==CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY
        ||op.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW;
}
inline void validateHeader(const StormTick& input,size_t bytes,unsigned capacity,
        uint64_t submitted,uint64_t tick,uint64_t identity,bool active,bool domeStaged) {
    auto reject=[&](const char* reason){
        throw std::runtime_error(std::string("Storm input ")+reason+" (sequence="+std::to_string(input.sequence)
            +", logical tick="+std::to_string(input.tick)+", identity="+std::to_string(input.identity)+")");
    };
    bool idleBoundary=!active&&input.identity>identity;
    bool tickOrder=!submitted||input.tick>tick&&(input.tick==tick+1||idleBoundary);
    if(input.sequence!=submitted+1||!tickOrder||input.tick>uint64_t(std::numeric_limits<int64_t>::max()))reject("order");
    if(input.identity==0||input.identity<identity||(submitted&&input.identity==identity&&(input.active&1u)&&!active)
        ||(input.projectedSequence?(input.projectedIdentity!=input.identity||input.projectedSequence>=input.sequence):input.projectedIdentity!=0))reject("identity or display cursor");
    if(!std::isfinite(input.seconds)||input.seconds<0.f||input.seconds>=104857.6f)reject("world clock");
    if(input.magic!=CMI_RESOURCE_STORM_TICK_MAGIC||input.abi!=CMI_RESOURCE_ABI||input.controls>CMI_RESOURCE_STORM_MAX_CONTROLS
        ||input.terrainRows>CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS||input.terrainPages>CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES
        ||(input.terrainRows==0&&input.terrainPages!=0)
        ||input.playerCount>CMI_RESOURCE_STORM_PLAYER_LIMIT||input.positionSnapshot>1u
        ||input.columnCaptureCount>CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT
        ||input.featherImpacts>1u
        ||input.terrainEditBytes>CMI_RESOURCE_STORM_TERRAIN_EDIT_MAX_BYTES||input.editReserved[0]||input.editReserved[1]||input.editReserved[2]
        ||(input.positionSnapshot&&!input.playerCount)||(!(input.active&1u)&&(input.playerCount||input.columnCaptureCount))
        ||bytes!=sizeof(StormTick)+commandBytes(input.controls)+rowBytes(input.terrainRows)+cellBytes(input.terrainPages)
            +playerBytes(input.playerCount)+input.terrainEditBytes)reject("layout or feedback counts");
    if(input.active>3||(input.active&2u&&!domeStaged)||input.population>capacity||input.seed>0xffffffu||(!(input.active&1u)&&input.population)
        ||!std::isfinite(input.centerX)||std::abs(input.centerX)>31000000.f
        ||!std::isfinite(input.centerY)||std::abs(input.centerY)>31000000.f
        ||!std::isfinite(input.centerZ)||std::abs(input.centerZ)>31000000.f
        ||!std::isfinite(input.radius)||input.radius<2.f||input.radius>4096.f
        ||!std::isfinite(input.speed)||input.speed<.5f||input.speed>128.f
        ||!std::isfinite(input.rotation)||!std::isfinite(input.conveyor)||!std::isfinite(input.conveyorRate)
        ||!std::isfinite(input.omega)||!targetPointValid(input.targetBefore)||!targetPointValid(input.targetAfter)||input.targetBefore.w!=0.f)
        reject("simulation parameters");
}
inline void validatePayload(const StormTick& input,const unsigned char* commands,unsigned capacity) {
    for(unsigned i=0;i<input.columnCaptureCount;i++){
        auto row=input.columnCaptures[i];
        if(row.x>=columnSlots||!row.y||row.y>=0x1000000u||!row.z||row.w)
            throw std::runtime_error("Invalid Starfall capture request");
    }
    for(unsigned i=0;i<input.controls;i++){
        StormControl op;std::memcpy(&op,commands+i*sizeof(op),sizeof(op));
        if(!op.identity||op.identity>input.identity||op.span==0||!vectorFinite(op.a)||!vectorFinite(op.b))
            throw std::runtime_error("Storm command has invalid identity or range");
        if(op.op==CMI_RESOURCE_STORM_CONTROL_LIFECYCLE){
            if(op.member||op.span!=1||op.scope||op.flags>1
                ||(op.identity==input.identity&&op.flags!=(input.active&1u)))
                throw std::runtime_error("Invalid Storm lifecycle command");
            continue;
        }
        if(op.identity<input.identity)continue; // The containing tick still acknowledges this retired generation.
        if(!(input.active&1u))throw std::runtime_error("Active command on an inactive Storm");
        unsigned memberLimit=op.op==CMI_RESOURCE_STORM_CONTROL_CORRECTION?capacity:input.population;
        if(!attackWide(op)&&(op.member>=memberLimit||op.span>memberLimit-op.member))
            throw std::runtime_error("Storm command exceeds member range");
        if(regionalCommand(op)){
        unsigned limit=ringCommand(op)?CMI_RESOURCE_STORM_RING_SLOTS:columnCommand(op)?columnSlots:domeSlots;
            unsigned span=op.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE?columnMembers:1;
            if(unsigned(op.scope)==0||op.scope>>32>=limit||op.span!=span)
                throw std::runtime_error("Invalid regional attack identity");
        }else if(op.scope!=0)throw std::runtime_error("Unexpected Storm command scope");
        switch(op.op){
            case CMI_RESOURCE_STORM_CONTROL_SPAWN:
                if(op.flags>1)throw std::runtime_error("Invalid Storm birth flags");
                break;
            case CMI_RESOURCE_STORM_CONTROL_DAMAGE:
                if(op.flags>1||op.span!=1||op.a.x<0.f)throw std::runtime_error("Invalid Storm damage");
                break;
            case CMI_RESOURCE_STORM_CONTROL_CORRECTION:
                if(op.flags||op.span!=1||op.b.w<0.f)throw std::runtime_error("Invalid Storm correction");
                break;
            case CMI_RESOURCE_STORM_CONTROL_OUTER_START:
            case CMI_RESOURCE_STORM_CONTROL_OUTER_RELEASE:
                if(op.flags==0||op.flags>0x7fffffffu||op.span!=1||!targetPointValid(op.a))
                    throw std::runtime_error("Invalid Storm outer flight");
                break;
            case CMI_RESOURCE_STORM_CONTROL_DOME_STATE: {
                unsigned phase=op.flags&3u;
                bool malformedTimeline=phase!=0&&(op.b.x<0.f||op.b.x>=104857.6f||op.b.y<0.f
                    ||op.b.z<op.b.y||op.b.z>600.f||op.b.w<=0.f);
                if(op.member||phase>2||(op.flags&~7u)||malformedTimeline)
                    throw std::runtime_error("Invalid Dome state");
                break;
            }
            case CMI_RESOURCE_STORM_CONTROL_DOME_ASSIGN:
                if(op.flags>=domeMembers)throw std::runtime_error("Invalid Dome member rank");
                break;
            case CMI_RESOURCE_STORM_CONTROL_DOME_PAUSE:
                if(op.member||op.flags>=domeFlows||op.a.x<0.f||op.a.x>600.f)
                    throw std::runtime_error("Invalid Dome flow pause");
                break;
            case CMI_RESOURCE_STORM_CONTROL_DOME_RELEASE:
                if(op.flags)throw std::runtime_error("Invalid Dome release");
                break;
            case CMI_RESOURCE_STORM_CONTROL_HELIX_STATE:{
                unsigned phase=op.flags&3u;
                bool invalidTimeline=phase&&(op.a.x<0.f||op.a.x>=104857.6f||op.a.y<0.f
                    ||op.a.z<op.a.y||op.a.z>600.f||op.a.w<0.f||op.a.w>600.f
                    ||op.b.x<=0.f||op.b.y<op.b.x||op.b.y>128.f);
                if(op.member>CMI_RESOURCE_STORM_HELIX_FRAMES||phase>2||(op.flags&~7u)||invalidTimeline)
                    throw std::runtime_error("Invalid Helix state");
                break;
            }
            case CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME:
                if(op.flags||op.member>=CMI_RESOURCE_STORM_HELIX_FRAMES*4
                    ||(op.member%4==0&&(op.a.x<0.f||op.a.x>=16777216.f||op.a.y<0.f||op.a.y>600.f
                        ||op.a.z!=.25f||op.a.w!=1.f)))
                    throw std::runtime_error("Invalid Helix reference segment");
                break;
            case CMI_RESOURCE_STORM_CONTROL_HELIX_ASSIGN:
                if(op.flags>=CMI_RESOURCE_STORM_HELIX_MEMBERS||!targetPointValid(op.a))
                    throw std::runtime_error("Invalid Helix roster");
                break;
            case CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY:
                if(op.flags||op.member>=CMI_RESOURCE_STORM_HELIX_RECOVERY_ROWS||(op.member&1))
                    throw std::runtime_error("Invalid Helix recovery");
                break;
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE: {
                unsigned state=op.flags&3u;
                bool invalidTimeline=state&&(op.b.x<0.f||op.b.x>=104857.6f||op.b.y<=0.f
                    ||op.b.z<=op.b.y||op.b.w<=op.b.z||op.b.w>600.f||op.a.w<=op.a.y+3.f
                    ||!targetPointValid(op.a));
                if(op.member>0xffffffu||state>2u||(op.flags&~7u)||invalidTimeline)
                    throw std::runtime_error("Invalid Starfall column state");
                break;
            }
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_ASSIGN:
                if(op.flags>=columnMembers)throw std::runtime_error("Invalid Starfall column rank");
                break;
        case CMI_RESOURCE_STORM_CONTROL_RING_STATE:
            if(op.member||op.flags>4u||!targetPointValid(op.a))throw std::runtime_error("Invalid Storm ring state");
            break;
        case CMI_RESOURCE_STORM_CONTROL_RING_BASIS:
            if(op.member||op.flags)throw std::runtime_error("Invalid Storm ring basis");
            break;
        case CMI_RESOURCE_STORM_CONTROL_RING_ASSIGN:
            if(op.flags>=CMI_RESOURCE_STORM_RING_MEMBERS)throw std::runtime_error("Invalid Storm ring rank");
            break;
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_RELEASE:
                if(op.flags)throw std::runtime_error("Invalid Starfall column release");
                break;
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE:
                if(op.flags||!op.member||op.a.x<0.f||op.a.x>600.f)
                    throw std::runtime_error("Invalid Starfall restore token or clock");
                break;
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW:
                if(op.flags||op.member>=columnMembers*CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS
                    ||(op.member%CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS==0
                        &&op.a.x!=0.f&&op.a.x!=2.f&&op.a.x!=4.f&&op.a.x!=5.f&&op.a.x!=6.f))
                    throw std::runtime_error("Invalid Starfall restore row");
                break;
            default:throw std::runtime_error("Unsupported Storm control opcode");
        }
    }
    auto rows=commands+commandBytes(input.controls);
    for(unsigned i=0;i<input.terrainRows;i++){
        int4 row;std::memcpy(&row,rows+rowBytes(i),sizeof(row));
        if(row.w< -3||(row.w>=0&&unsigned(row.w)>=input.terrainPages))
            throw std::runtime_error("Invalid Storm voxel page reference");
    }
    auto players=rows+rowBytes(input.terrainRows)+cellBytes(input.terrainPages);
    validateStormTerrainEdits(players+playerBytes(input.playerCount),input.terrainEditBytes);
    for(unsigned i=0;i<input.playerCount;i++){
        float4 row;std::memcpy(&row,players+playerBytes(i),sizeof(row));
        if(!targetPointValid(row)||!std::isfinite(row.w)||std::abs(row.w-row.y)>8.f)
            throw std::runtime_error("Invalid Storm player sample");
    }
}
}
