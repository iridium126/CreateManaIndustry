#ifndef CMI_STORM_DXR_LAYOUT
#define CMI_STORM_DXR_LAYOUT
#include "../particle_common/ResourceLayout.h"

struct StormTick {
    uint magic, abi, controls, population;
    uint64_t sequence, tick, identity;
    uint activity, seed;
    float centerX, centerY, centerZ, radius;
    float rotation, conveyor, conveyorRate, omega;
    float seconds, speed;
    uint terrainRows, terrainPages;
    float4 targetBefore, targetAfter;
    uint64_t projectedSequence, projectedIdentity;
    uint playerCount, positionSnapshot, columnCaptureCount, featherImpacts;
    uint4 columnCaptures[CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT];
    uint64_t terrainBaseRevision, terrainRevision;
};
struct StormControl {
    uint op, member, span, flags;
    uint64_t identity, scope;
    float4 a, b;
};
struct StormStepReceipt { uint64_t sequence, tick, identity; uint displayRecords; };
struct StormDisplayHeader {
    uint magic, abi, count, capacity;
    uint64_t sequence, tick, identity;
    uint ready, columnMask[2], reserved[3];
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

static const uint domeSlots=CMI_RESOURCE_STORM_DOME_SLOTS;
static const uint domeMembers=CMI_RESOURCE_STORM_DOME_MEMBERS;
static const uint domeFlows=CMI_RESOURCE_STORM_DOME_FLOWS;
static const uint domeBitmapWords=domeMembers/32;
enum StormDomePhase  { DomeUnassigned, DomeGather, DomeReleasePending, DomeAttack, DomeReturn, DomeHome };
enum StormRegionalKind  { RegionalDome=1, RegionalHelix=2, RegionalColumn=3 };
static const uint columnSlots=CMI_RESOURCE_STORM_COLUMN_SLOTS;
static const uint columnMembers=CMI_RESOURCE_STORM_COLUMN_MEMBERS;
struct StormDome {
    uint64_t identity;
    uint id, state, kind;
    float4 centerYaw;
    float start, lockedAt, end, radius;
    float paused[domeFlows];
};
struct StormHelixSample {
    float4 center, velocity, omega, rotation;
    uint version, valid;
    float seconds;
};
struct StormHelix {
    float4 frames[CMI_RESOURCE_STORM_HELIX_FRAMES][CMI_RESOURCE_STORM_HELIX_FRAME_ROWS];
    float4 recovery[CMI_RESOURCE_STORM_HELIX_RECOVERY_ROWS];
    float speed, cap, targetArc, freeze;
    uint count;
    bool terrainReady;
    StormHelixSample current, previous;
};
struct StormColumn {
    uint64_t identity;
    uint id, seed, state, soundPlayed, restoreToken;
    float4 landingTop;
    uint roster[columnMembers];
    float start, assemble, touch, end, restoreElapsed;
};
struct StormColumnCurve {
    float radius, root, angle, endAngle, height, endHeight, radial, angular, vertical, arcWarp;
};
struct StormColumnMember {
    uint64_t identity;
    uint id, phase, restoreToken;
    float4 origin, initialVelocity, tangent, descentOrigin, descentDirection;
    StormColumnCurve curve;
    float arc[65];
    float touchdown, release, distance, bloomRadius, bloomAngle, replayCursor, descentStart;
    bool replaying;
};
struct StormColumnRestore {
    float4 rows[CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS*2];
    uint64_t identity;
    uint id, token, parts;
};
struct StormDomeMember {
    uint64_t identity;
    uint id, slot, rank, phase, kind;
    float4 joinOffset, joinVelocity;
    float4 helixLocal, helixBlocked;
    float joinTime, blockedSeconds, pauseSeen, initialError, progressSeconds;
    uint streak;
    bool tracked, inside, entered;
};
struct StormRoute {
    float4 origin, goal, heading;
    uint waypoints[8];
    uint64_t retryAt;
    uint count, next, revision;
    float blockedSeconds;
    bool searched, noRoute;
};
struct StormDomeHit {
    uint target, reserved[3];
    float4 pointTime;
};
struct StormHelixReference {
    uint version, valid;
    float seconds;
    uint reserved;
};
struct StormColumnBatch {
    uint id, restoreToken, completed[2], installed[2], reserved[2];
    StormDomeHit contact;
    float4 sound;
};
struct StormOuterArrival { uint member, cycle; };
struct StormOuterBatch {
    uint count, reserved[3];
    StormOuterArrival rows[CMI_RESOURCE_STORM_OUTER_BATCH_LIMIT];
};
struct StormPositionRow {
    float member, reserved, x, y, z, vx, vy, vz;
};
struct StormPositionBatch {
    uint count, reserved[3];
    StormPositionRow rows[CMI_RESOURCE_STORM_POSITION_BATCH_LIMIT];
};
struct StormDomeBatch {
    uint attackIds[domeSlots];
    uint ready[domeSlots][domeBitmapWords];
    uint completed[domeSlots][domeBitmapWords];
    uint blocked[domeSlots], unknown[domeSlots];
    float paused[domeSlots][domeFlows];
    StormDomeHit contacts[domeSlots];
    StormHelixReference helixReferences[domeSlots];
    StormColumnBatch columns[columnSlots];
    StormOuterBatch outer;
    StormPositionBatch positions;
    float columnCaptures[CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT][columnMembers][CMI_RESOURCE_STORM_COLUMN_CAPTURE_FLOATS];
};
static const uint featherImpactLimit=CMI_RESOURCE_STORM_FEATHER_IMPACT_LIMIT;
struct StormFeatherCell { int state,x,y,z; float4 impact; };
struct StormFeatherReceipt { uint count,reserved[3]; float4 rows[featherImpactLimit]; };

// Member storage is stable across ticks. Identity guards births, deaths,
// feedback and the future GL projection; the layout is not a query row.
struct StormStepMember {
    float4 position, velocity, previous;
    float4 correctionPositionTime, correctionVelocityStrength;
    uint64_t identity, birthSequence;
    uint state, terrainState;
    float seed, hp, hurt, age;
    uint64_t recoveryTick, poofSequence;
    float damageLight;
    float4 outerTarget, spatialSteering;
    uint outerCycle, outerState;
};

enum StormMemberState { StormAbsent, StormAlive, StormCorpse, StormDead };
static const uint StormCoverageUnknown=1u;
static const uint StormTerrainClipped=CMI_RESOURCE_STORM_DISPLAY_TERRAIN_CLIPPED;
static const uint StormEmbedded=4u;
static const uint StormDiscontinuity=CMI_RESOURCE_STORM_DISPLAY_DISCONTINUITY;
#endif
