#include "StormLayout.hlsli"

cbuffer StormDispatch : register(b0) {
    uint capacity, terrainMask, terrainRevision, terrainOverflow;
    uint commandFirst, commandCount, commandMode, featherEnabled;
    uint publishDisplay, simulationProbes, terrainBoxes, hasRegional;
    uint2 unitBlas,terrainBlas;
    uint terrainEdited;
    uint3 editPad;
};

StructuredBuffer<StormTick> tickInput : register(t0);
StructuredBuffer<StormControl> controls : register(t1);
StructuredBuffer<float4> players : register(t2);
StructuredBuffer<int4> terrainRows : register(t3);
StructuredBuffer<uint> terrainCells : register(t4);
StructuredBuffer<float> domeRecords : register(t5);
StructuredBuffer<uint> domeKeys : register(t6);
StructuredBuffer<uint> domeFlowsDevice : register(t7);
RaytracingAccelerationStructure scene : register(t8);
StructuredBuffer<uint> terrainEdits : register(t9);

RWStructuredBuffer<StormStepMember> members : register(u0);
RWStructuredBuffer<StormDomeMember> assignments : register(u1);
RWStructuredBuffer<StormRoute> routes : register(u2);
RWStructuredBuffer<StormDome> attacks : register(u3);
RWStructuredBuffer<StormHelix> helixes : register(u4);
RWStructuredBuffer<StormColumn> columns : register(u5);
RWStructuredBuffer<StormColumnMember> columnStates : register(u6);
RWStructuredBuffer<StormColumnRestore> restores : register(u7);
RWByteAddressBuffer batches : register(u8);
globallycoherent RWStructuredBuffer<StormFeatherCell> featherCells : register(u9);
RWByteAddressBuffer feathers : register(u10);
RWStructuredBuffer<uint> attempts : register(u11);
RWByteAddressBuffer snapshot : register(u12);
RWByteAddressBuffer receipt : register(u13);
RWByteAddressBuffer instances : register(u14);
RWStructuredBuffer<float4> hints : register(u15);
RWStructuredBuffer<uint> domePauseBits : register(u16);

uint domeBitmapOffset(uint base,uint slot,uint word) { return base+(slot*domeBitmapWords+word)*4u; }
uint domeContactOffset(uint slot) { return CMI_RESOURCE_STORM_DOME_CONTACT_OFFSET+slot*CMI_RESOURCE_STORM_DOME_CONTACT_BYTES; }
uint columnBatchOffset(uint slot) { return CMI_RESOURCE_STORM_COLUMN_BATCH_OFFSET+slot*CMI_RESOURCE_STORM_COLUMN_BATCH_STRIDE; }

bool claimSearch(uint kind,uint limit) {
    uint previous;
    InterlockedAdd(attempts[kind],1u,previous);
    return previous < limit;
}

typedef float3 vec3;
float3 stormVec3(float x) { return x.xxx; }
float3 stormVec3(float4 x) { return x.xyz; }
float3 stormVec3(float x,float y,float z) { return float3(x,y,z); }
#define vec3(...) stormVec3(__VA_ARGS__)
#define fract frac
#define CMI_STORM_FN
#define CMI_STORM_OUT(T) out T
#include "storm_home.glsl"
#undef CMI_STORM_FN
#undef CMI_STORM_OUT


#include "StormCollision.hlsli"
#include "StormNavigation.hlsli"
#include "StormDome.hlsli"
#include "StormHelix.hlsli"
#include "StormContact.hlsli"
#include "StormColumn.hlsli"
#include "StormMotion.hlsli"
#include "StormControls.hlsli"
#include "StormAdvance.hlsli"

#include "StormSpatial.hlsli"
#include "StormPublication.hlsli"
