#pragma once
#include <vector_types.h>
#include <cstdint>
#include <cstddef>
#include "ResourceLayout.h"

// Identity is scoped to the session. Sequence starts at one. The logical tick
// orders simulation; seconds carries the independently synchronized world clock.
struct StormTick {
    uint32_t magic, abi, controls, population;
    uint64_t sequence, tick, identity;
    uint32_t active, seed;
    float centerX, centerY, centerZ, radius;
    float rotation, conveyor, conveyorRate, omega;
    float seconds, speed;
    uint32_t terrainRows, terrainPages;
    float4 targetBefore, targetAfter;
    uint64_t projectedSequence, projectedIdentity;
    uint32_t playerCount, positionSnapshot, columnCaptureCount, featherImpacts;
    uint4 columnCaptures[CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT];
};
struct StormControl {
    uint32_t op, member, span, flags;
    uint64_t identity, scope;
    float4 a, b;
};
struct StormStepReceipt { uint64_t sequence, tick, identity; uint32_t displayRecords; };
struct StormDisplayHeader {
    uint32_t magic, abi, count, capacity;
    uint64_t sequence, tick, identity;
    uint32_t ready, reserved[5];
};
struct StormDisplayRecord {
    float4 position, previous, velocity, appearance;
    uint4 key; // member, attack, attack rank, lifecycle/phase flags
};
struct StormColumnDisplay {
    float4 originDistance, directionRadius;
    uint4 key; // attack, phase, bloom angle bits, reserved
    float arc[CMI_RESOURCE_STORM_DISPLAY_COLUMN_ARC_NODES];
};
struct StormTerrain {
    const int4* rows;
    const uint32_t* cells;
    uint32_t mask, revision;
};

constexpr unsigned domeSlots=CMI_RESOURCE_STORM_DOME_SLOTS;
constexpr unsigned domeMembers=CMI_RESOURCE_STORM_DOME_MEMBERS;
constexpr unsigned domeFlows=CMI_RESOURCE_STORM_DOME_FLOWS;
constexpr unsigned domeBitmapWords=domeMembers/32;
enum StormDomePhase : uint32_t { DomeUnassigned, DomeGather, DomeReleasePending, DomeAttack, DomeReturn, DomeHome };
enum StormRegionalKind : uint32_t { RegionalDome=1, RegionalHelix=2, RegionalColumn=3 };
constexpr unsigned columnSlots=CMI_RESOURCE_STORM_COLUMN_SLOTS;
constexpr unsigned columnMembers=CMI_RESOURCE_STORM_COLUMN_MEMBERS;
struct StormDome {
    uint64_t identity;
    uint32_t id, state, kind;
    float4 centerYaw;
    float start, lockedAt, end, radius;
    float paused[domeFlows];
};
struct StormHelixSample {
    float4 center, velocity, omega, rotation;
    uint32_t version, valid;
    float seconds;
};
struct StormHelix {
    float4 frames[CMI_RESOURCE_STORM_HELIX_FRAMES][CMI_RESOURCE_STORM_HELIX_FRAME_ROWS];
    float4 recovery[CMI_RESOURCE_STORM_HELIX_RECOVERY_ROWS];
    float speed, cap, targetArc, freeze;
    uint32_t count;
    bool terrainReady;
    StormHelixSample current, previous;
};
struct StormColumn {
    uint64_t identity;
    uint32_t id, seed, state, soundPlayed, restoreToken;
    float4 landingTop;
    uint32_t roster[columnMembers];
    float start, assemble, touch, end, restoreElapsed;
};
struct StormColumnCurve {
    float radius, root, angle, endAngle, height, endHeight, radial, angular, vertical, arcWarp;
};
struct StormColumnMember {
    uint64_t identity;
    uint32_t id, phase, restoreToken;
    float4 origin, initialVelocity, tangent, descentOrigin, descentDirection;
    StormColumnCurve curve;
    float arc[65];
    float touchdown, release, distance, bloomRadius, bloomAngle, replayCursor, descentStart;
    bool replaying;
};
struct StormColumnRestore {
    float4 rows[CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS*2];
    uint64_t identity;
    uint32_t id, token, parts;
};
struct StormDomeMember {
    uint64_t identity;
    uint32_t id, slot, rank, phase, kind;
    float4 joinOffset, joinVelocity;
    float4 helixLocal, helixBlocked;
    float joinTime, blockedSeconds, pauseSeen, initialError, progressSeconds;
    uint32_t streak;
    bool tracked, inside, entered;
};
struct StormRoute {
    float4 origin, goal, heading;
    uint16_t waypoints[8];
    uint64_t retryAt;
    uint32_t count, next, revision;
    float blockedSeconds;
    bool searched, noRoute;
};
struct StormDomeHit {
    uint32_t target, reserved[3];
    float4 pointTime;
};
struct StormHelixReference {
    uint32_t version, valid;
    float seconds;
    uint32_t reserved;
};
struct StormColumnBatch {
    uint32_t id, restoreToken, completed[2], installed[2], reserved[2];
    StormDomeHit contact;
    float4 sound;
};
struct StormOuterArrival { uint32_t member, cycle; };
struct StormOuterBatch {
    uint32_t count, reserved[3];
    StormOuterArrival rows[CMI_RESOURCE_STORM_OUTER_BATCH_LIMIT];
};
struct StormPositionRow {
    float member, reserved, x, y, z, vx, vy, vz;
};
struct StormPositionBatch {
    uint32_t count, reserved[3];
    StormPositionRow rows[CMI_RESOURCE_STORM_POSITION_BATCH_LIMIT];
};
struct StormDomeBatch {
    uint32_t attackIds[domeSlots];
    uint32_t ready[domeSlots][domeBitmapWords];
    uint32_t completed[domeSlots][domeBitmapWords];
    uint32_t blocked[domeSlots], unknown[domeSlots];
    float paused[domeSlots][domeFlows];
    StormDomeHit contacts[domeSlots];
    StormHelixReference helixReferences[domeSlots];
    StormColumnBatch columns[columnSlots];
    StormOuterBatch outer;
    StormPositionBatch positions;
    float columnCaptures[CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT][columnMembers][CMI_RESOURCE_STORM_COLUMN_CAPTURE_FLOATS];
};
constexpr unsigned featherImpactLimit=CMI_RESOURCE_STORM_FEATHER_IMPACT_LIMIT;
struct StormFeatherCell { int state,x,y,z; float4 impact; };
struct StormFeatherReceipt { uint32_t count,reserved[3]; float4 rows[featherImpactLimit]; };
struct StormDomeView {
    const float* records;
    const uint32_t* keys;
    const uint32_t* flows;
    StormDome* attacks;
    StormHelix* helixes;
    StormColumn* columns;
    StormColumnMember* columnStates;
    StormColumnRestore* columnRestores;
    StormDomeMember* assignments;
    StormRoute* routes;
    uint32_t* pathSearches;
    StormDomeBatch* batch;
    StormFeatherCell* featherCells;
};

// Member storage is stable across ticks. Identity guards births, deaths,
// feedback and the future GL projection; the layout is not a query row.
struct StormStepMember {
    float4 position, velocity, previous;
    float4 correctionPositionTime, correctionVelocityStrength;
    uint64_t identity, birthSequence;
    uint32_t state, terrainState;
    float seed, hp, hurt, age;
    uint64_t recoveryTick, poofSequence;
    float damageLight;
    float4 outerTarget, spatialSteering;
    uint32_t outerCycle, outerState;
};
static_assert(sizeof(StormTick) == CMI_RESOURCE_STORM_TICK_HEADER_BYTES);
static_assert(offsetof(StormTick,sequence)==CMI_RESOURCE_STORM_TICK_SEQUENCE_OFFSET);
static_assert(offsetof(StormTick,targetBefore)==CMI_RESOURCE_STORM_TICK_TARGET_OFFSET);
static_assert(offsetof(StormTick,projectedSequence)==CMI_RESOURCE_STORM_TICK_PROJECTION_OFFSET);
static_assert(offsetof(StormTick,playerCount)==CMI_RESOURCE_STORM_TICK_PLAYER_COUNT_OFFSET);
static_assert(offsetof(StormTick,columnCaptures)==CMI_RESOURCE_STORM_TICK_COLUMN_CAPTURE_OFFSET);
static_assert(sizeof(float4)==CMI_RESOURCE_STORM_PLAYER_BYTES);
static_assert(sizeof(StormDisplayHeader)==CMI_RESOURCE_STORM_DISPLAY_HEADER_BYTES);
static_assert(offsetof(StormDisplayHeader,sequence)==CMI_RESOURCE_STORM_DISPLAY_SEQUENCE_OFFSET);
static_assert(offsetof(StormDisplayHeader,ready)==CMI_RESOURCE_STORM_DISPLAY_READY_OFFSET);
static_assert(sizeof(StormDisplayRecord)==CMI_RESOURCE_STORM_DISPLAY_RECORD_BYTES);
static_assert(offsetof(StormDisplayRecord,appearance)==CMI_RESOURCE_STORM_DISPLAY_APPEARANCE_OFFSET);
static_assert(offsetof(StormDisplayRecord,key)==CMI_RESOURCE_STORM_DISPLAY_KEY_OFFSET);
static_assert(sizeof(StormColumnDisplay)==CMI_RESOURCE_STORM_DISPLAY_COLUMN_BYTES);
static_assert(offsetof(StormColumnDisplay,arc)==CMI_RESOURCE_STORM_DISPLAY_COLUMN_ARC_OFFSET);
static_assert(sizeof(StormControl) == CMI_RESOURCE_STORM_CONTROL_BYTES);
static_assert(offsetof(StormControl, identity) == 16 && offsetof(StormControl, a) == 32);
static_assert(sizeof(StormStepMember) == CMI_RESOURCE_STORM_MEMBER_BYTES);
static_assert(sizeof(int4) == CMI_RESOURCE_STORM_TERRAIN_ROW_BYTES);
static_assert(sizeof(StormDomeBatch) == CMI_RESOURCE_STORM_DOME_BATCH_BYTES);
static_assert(sizeof(StormDomeHit) == CMI_RESOURCE_STORM_DOME_CONTACT_BYTES);
static_assert(offsetof(StormDomeBatch,ready) == CMI_RESOURCE_STORM_DOME_READY_OFFSET);
static_assert(offsetof(StormDomeBatch,completed) == CMI_RESOURCE_STORM_DOME_COMPLETED_OFFSET);
static_assert(offsetof(StormDomeBatch,blocked) == CMI_RESOURCE_STORM_DOME_BLOCKED_OFFSET);
static_assert(offsetof(StormDomeBatch,unknown) == CMI_RESOURCE_STORM_DOME_UNKNOWN_OFFSET);
static_assert(offsetof(StormDomeBatch,paused) == CMI_RESOURCE_STORM_DOME_PAUSED_OFFSET);
static_assert(offsetof(StormDomeBatch,contacts) == CMI_RESOURCE_STORM_DOME_CONTACT_OFFSET);
static_assert(offsetof(StormDomeBatch,helixReferences) == CMI_RESOURCE_STORM_HELIX_REFERENCE_OFFSET);
static_assert(sizeof(StormHelixReference) == CMI_RESOURCE_STORM_HELIX_REFERENCE_BYTES);
static_assert(offsetof(StormDomeBatch,columns) == CMI_RESOURCE_STORM_COLUMN_BATCH_OFFSET);
static_assert(offsetof(StormDomeBatch,outer) == CMI_RESOURCE_STORM_OUTER_BATCH_OFFSET);
static_assert(offsetof(StormDomeBatch,positions) == CMI_RESOURCE_STORM_POSITION_BATCH_OFFSET);
static_assert(offsetof(StormDomeBatch,columnCaptures) == CMI_RESOURCE_STORM_COLUMN_CAPTURE_OFFSET);
static_assert(sizeof(StormPositionRow) == CMI_RESOURCE_STORM_POSITION_ROW_BYTES);
static_assert(sizeof(StormColumnBatch) == CMI_RESOURCE_STORM_COLUMN_BATCH_STRIDE);
enum StormMemberState : uint32_t { StormAbsent, StormAlive, StormCorpse, StormDead };
enum StormTerrainState : uint32_t {
    StormCoverageUnknown=1u, StormTerrainClipped=CMI_RESOURCE_STORM_DISPLAY_TERRAIN_CLIPPED,
    StormEmbedded=4u, StormDiscontinuity=CMI_RESOURCE_STORM_DISPLAY_DISCONTINUITY
};
