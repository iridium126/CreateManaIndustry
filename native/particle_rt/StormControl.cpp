#include "Common.h"
#include "ParticleDevice.h"
#include "StormStep.h"
#include "StormSpatial.h"
#include "Interop.h"
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace {
constexpr unsigned slots=CMI_RESOURCE_RT_SLOTS, block=256;
constexpr int idle=0, pending=1, complete=2, failed=4, undrained=5, queued=6, reserved=7;
void CUDA_CB injectedDelay(void* value){Sleep(DWORD(reinterpret_cast<uintptr_t>(value)));}
struct DomeUpload {
    std::vector<unsigned char> bytes;
    size_t records{},keys{},flows{};
};
struct TickSlot {
    CUevent done{};
    std::array<CUevent,8> marks{};
    bool timed{};
    StormStepReceipt* receipt{};
    CUdeviceptr deviceReceipt{};
    void* terrainUpload{};
    void* controlUpload{};
    void* playerUpload{};
    StormDomeBatch* domeBatch{};
    StormFeatherReceipt* featherReceipt{};
    StormTick tick{};
    std::atomic<int> state{};
    int display=-1;
    BridgeTicket ticket{};
    std::vector<unsigned char> payload;
    std::shared_ptr<const TerrainSnapshot> geometry;
    std::unique_ptr<DomeUpload> templateUpload;
    std::string error;
    unsigned delay{};
};
struct DisplaySlot {
    BridgeTicket ticket{};
    uint64_t sequence{};
    int state{};
};

// The session is owned by the Java render thread. All commands and all
// simulation ticks use one CUDA stream, even when three ticks are queued.
// The command input is separate from the space snapshot ring.
class StormControlSession {
    CUcontext context{};
    CUstream stream{};
    CUmodule module{};
    CUfunction control{},controlBatch{},advance{},seal{},domePrepare{},domeFinish{},positionSnapshot{},prepareSpatial{},captureColumns{};
    CUfunction displayPrepare{},displayPublish{},displaySeal{};
    CUfunction compactFeather{};
    CUdeviceptr members{},deviceControls{},devicePlayers{};
    CUdeviceptr terrainRows{},terrainCells{};
    unsigned terrainMask{},terrainRevision{};
    CUdeviceptr domeAttacks{},helixAttacks{},domeAssignments{},domeRoutes{},domeSearches{},domeBatch{};
    CUdeviceptr columns{},columnStates{},columnRestores{};
    CUdeviceptr featherCells{},featherResult{};
    CUdeviceptr domeRecords{},domeKeys{},domeFlowsDevice{};
    void* domeUpload{};
    CUevent domeUploadDone{};
    size_t owned{};
    unsigned capacity;
    std::vector<uint32_t> memberMarks;
    uint32_t controlGroup{};
    std::array<TickSlot,slots> ring{};
    std::array<DisplaySlot,slots> displayRing{};
    void* displayBridge{};
    StormSpatial* spatial{};
    std::shared_ptr<const TerrainSnapshot> geometry;
    uint64_t submitted{},consumed{},tick{},identity{};
    bool active{},stopped{};
    unsigned delayNext{};
    std::string error;
    std::thread worker;
    std::mutex queueMutex;
    std::condition_variable queueReady;
    uint64_t queuedThrough{};
    bool closing{},domeStaged{};
    std::atomic<bool> workerFailed{};
    std::unique_ptr<DomeUpload> pendingDome;

    void work() {
        for(uint64_t next=1;;++next){
            {
                std::unique_lock<std::mutex> lock(queueMutex);
                queueReady.wait(lock,[&]{return closing||next<=queuedThrough;});
                if(closing)return;
            }
            run(ring[(next-1)%slots]);
        }
    }

    void allocate(CUdeviceptr& dst,size_t bytes) {
        reserveBytes(bytes);
        try { checked(cuMemAlloc(&dst,bytes)); }
        catch (...) { particleRtOwnedBytes-=bytes; throw; }
        owned+=bytes;
    }
    void bind() { checked(cuCtxSetCurrent(context)); }
    void remember(const char* cause) {
        stopped=true;
        if(error.empty())error=cause;
    }
    void rememberTickFailure(const char* stage,const StormTick& input,const char* cause) {
        auto message=std::string(stage)+" (sequence="+std::to_string(input.sequence)
            +", tick="+std::to_string(input.tick)+", identity="+std::to_string(input.identity)+"): "+cause;
        remember(message.c_str());
    }
    static bool vectorFinite(float4 v) {
        return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z)&&std::isfinite(v.w);
    }
    static bool targetPointValid(float4 v){
        return std::isfinite(v.x)&&std::abs(v.x)<=31000000.f
            &&std::isfinite(v.y)&&std::abs(v.y)<=31000000.f
            &&std::isfinite(v.z)&&std::abs(v.z)<=31000000.f;
    }
    static size_t rowBytes(unsigned count){return size_t(count)*CMI_RESOURCE_STORM_TERRAIN_ROW_BYTES;}
    static size_t cellBytes(unsigned count){return size_t(count)*CMI_RESOURCE_STORM_TERRAIN_PAGE_BYTES;}
    static size_t commandBytes(unsigned count){return size_t(count)*sizeof(StormControl);}
    static size_t playerBytes(unsigned count){return size_t(count)*sizeof(float4);}
    static size_t batchBytes(const StormTick& input){
        if(input.columnCaptureCount)return sizeof(StormDomeBatch);
        return input.positionSnapshot?offsetof(StormDomeBatch,columnCaptures):offsetof(StormDomeBatch,positions);
    }
    static size_t maxTerrainUpload(){return rowBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS)+cellBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES);}
    static bool regionalCommand(StormControl op){
        return op.op>=CMI_RESOURCE_STORM_CONTROL_DOME_STATE&&op.op<=CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW;
    }
    static bool columnCommand(StormControl op){
        return op.op>=CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE&&op.op<=CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW;
    }
    static bool outerCommand(StormControl op){
        return op.op==CMI_RESOURCE_STORM_CONTROL_OUTER_START||op.op==CMI_RESOURCE_STORM_CONTROL_OUTER_RELEASE;
    }
    static bool attackWide(StormControl op){
        switch(op.op){
            case CMI_RESOURCE_STORM_CONTROL_DOME_STATE:
            case CMI_RESOURCE_STORM_CONTROL_DOME_PAUSE:
            case CMI_RESOURCE_STORM_CONTROL_HELIX_STATE:
            case CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME:
            case CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY:
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE:
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE:
            case CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW:return true;
            default:return false;
        }
    }
    static bool memberOnly(StormControl op){
        return op.span==1&&!attackWide(op);
    }
    static bool payloadPart(StormControl op){
        return op.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME||op.op==CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY
            ||op.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW;
    }
    StormDomeView domeView(bool hasTemplate,bool feathers=false) const {
        return {
            hasTemplate?reinterpret_cast<const float*>(domeRecords):nullptr,
            hasTemplate?reinterpret_cast<const uint32_t*>(domeKeys):nullptr,
            hasTemplate?reinterpret_cast<const uint32_t*>(domeFlowsDevice):nullptr,
            reinterpret_cast<StormDome*>(domeAttacks),reinterpret_cast<StormHelix*>(helixAttacks),
            reinterpret_cast<StormColumn*>(columns),reinterpret_cast<StormColumnMember*>(columnStates),
            reinterpret_cast<StormColumnRestore*>(columnRestores),
            reinterpret_cast<StormDomeMember*>(domeAssignments),
            reinterpret_cast<StormRoute*>(domeRoutes),reinterpret_cast<uint32_t*>(domeSearches),
            reinterpret_cast<StormDomeBatch*>(domeBatch),feathers?reinterpret_cast<StormFeatherCell*>(featherCells):nullptr
        };
    }
    void ensureColumns(){
        if(columns)return;
        allocate(columnStates,columnSlots*columnMembers*sizeof(StormColumnMember));
        allocate(columnRestores,columnSlots*columnMembers*sizeof(StormColumnRestore));
        allocate(columns,columnSlots*sizeof(StormColumn));
        checked(cuMemsetD8Async(columns,0,columnSlots*sizeof(StormColumn),stream));
        checked(cuMemsetD8Async(columnStates,0,columnSlots*columnMembers*sizeof(StormColumnMember),stream));
        checked(cuMemsetD8Async(columnRestores,0,columnSlots*columnMembers*sizeof(StormColumnRestore),stream));
    }
    void ensureBatch(){
        if(domeBatch)return;
        allocate(domeBatch,sizeof(StormDomeBatch));
        checked(cuMemsetD8Async(domeBatch,0,sizeof(StormDomeBatch),stream));
    }
    void ensureDome(){
        if(domeAttacks)return;
        ensureBatch();
        allocate(domeAssignments,capacity*sizeof(StormDomeMember));
        allocate(domeRoutes,capacity*sizeof(StormRoute));
        allocate(helixAttacks,domeSlots*sizeof(StormHelix));
        allocate(domeAttacks,domeSlots*sizeof(StormDome));
        checked(cuMemsetD8Async(domeAssignments,0,capacity*sizeof(StormDomeMember),stream));
        checked(cuMemsetD8Async(domeAttacks,0,domeSlots*sizeof(StormDome),stream));
    }
    void collectDomeUpload(){
        if(!domeUploadDone)return;
        auto status=cuEventQuery(domeUploadDone);
        if(status==CUDA_ERROR_NOT_READY)return;
        checked(status);
        checked(cuMemFreeHost(domeUpload));domeUpload=nullptr;
        checked(cuEventDestroy(domeUploadDone));domeUploadDone=nullptr;
    }
    void validateHeader(const StormTick& input,size_t bytes) {
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
            ||(input.terrainRows==0?input.terrainPages!=0:input.terrainRows<16||(input.terrainRows&(input.terrainRows-1))!=0)
            ||input.playerCount>CMI_RESOURCE_STORM_PLAYER_LIMIT||input.positionSnapshot>1u
            ||input.columnCaptureCount>CMI_RESOURCE_STORM_COLUMN_CAPTURE_LIMIT
            ||input.featherImpacts>1u
            ||(input.positionSnapshot&&!input.playerCount)||(!(input.active&1u)&&(input.playerCount||input.columnCaptureCount))
            ||bytes!=sizeof(StormTick)+commandBytes(input.controls)+rowBytes(input.terrainRows)+cellBytes(input.terrainPages)
                +playerBytes(input.playerCount))reject("layout or feedback counts");
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
    void validatePayload(const StormTick& input,const unsigned char* commands) {
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
                unsigned limit=columnCommand(op)?columnSlots:domeSlots;
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
        for(unsigned i=0;i<input.playerCount;i++){
            float4 row;std::memcpy(&row,players+playerBytes(i),sizeof(row));
            if(!targetPointValid(row)||!std::isfinite(row.w)||std::abs(row.w-row.y)>8.f)
                throw std::runtime_error("Invalid Storm player sample");
        }
    }
    void launch(CUfunction kernel,unsigned count,void** args) {
        checked(cuLaunchKernel(kernel,(count+block-1)/block,1,1,block,1,1,0,stream,args,nullptr));
    }
    void applyControls(TickSlot& slot,StormTick& input,const unsigned char* commands){
        bool uploaded=false;
        auto read=[commands](unsigned index){
            StormControl result;
            std::memcpy(&result,commands+size_t(index)*sizeof(result),sizeof(result));
            return result;
        };
        for(unsigned first=0;first<input.controls;){
            auto op=read(first);
            unsigned end=first+1;
            if(op.identity!=input.identity||op.op==CMI_RESOURCE_STORM_CONTROL_LIFECYCLE){first=end;continue;}
            bool needDome=regionalCommand(op);
            bool needColumns=columnCommand(op);
            bool needOuter=outerCommand(op);
            if(memberOnly(op)){
                if(++controlGroup==0){
                    std::fill(memberMarks.begin(),memberMarks.end(),0u);controlGroup=1;
                }
                memberMarks[op.member]=controlGroup;
                for(;end<input.controls;end++){
                    auto next=read(end);
                    if(next.identity!=input.identity||next.op==CMI_RESOURCE_STORM_CONTROL_LIFECYCLE
                        ||!memberOnly(next)||memberMarks[next.member]==controlGroup)break;
                    memberMarks[next.member]=controlGroup;
                    needDome|=regionalCommand(next);
                    needColumns|=columnCommand(next);
                    needOuter|=outerCommand(next);
                }
            }else if(payloadPart(op)){
                std::array<uint64_t,4> mask{};
                mask[op.member/64]=1ull<<(op.member&63u);
                for(;end<input.controls;end++){
                    auto next=read(end);
                    if(next.identity!=input.identity||next.op!=op.op||next.scope!=op.scope)break;
                    auto& seen=mask[next.member/64];
                    auto bit=1ull<<(next.member&63u);
                    if(seen&bit)break;
                    seen|=bit;
                }
            }
            if(needDome)ensureDome();
            if(needColumns)ensureColumns();
            if(needOuter)ensureBatch();
            auto view=domeView((input.active&2u)!=0);
            unsigned count=end-first;
            if(count==1){
                void* args[]={&members,&input,&op,&view};
                launch(control,op.span,args);
            }else{
                if(!uploaded){
                    if(!deviceControls)allocate(deviceControls,commandBytes(CMI_RESOURCE_STORM_MAX_CONTROLS));
                    if(!slot.controlUpload)checked(cuMemAllocHost(&slot.controlUpload,commandBytes(CMI_RESOURCE_STORM_MAX_CONTROLS)));
                    auto size=commandBytes(input.controls);
                    std::memcpy(slot.controlUpload,commands,size);
                    checked(cuMemcpyHtoDAsync(deviceControls,slot.controlUpload,size,stream));
                    uploaded=true;
                }
                void* args[]={&members,&input,&deviceControls,&first,&count,&view};
                launch(controlBatch,count,args);
            }
            first=end;
        }
    }
public:
    StormControlSession(unsigned cap,void* bridge,unsigned probes):capacity(cap) {
        if(!capacity||capacity>131072||(probes!=2&&probes!=4))
            throw std::runtime_error("Invalid native Storm capacity or probe count");
        memberMarks.resize(capacity);
        try {
            if(bridge){
                validateBridgeCapacity(bridge,sizeof(StormDisplayHeader)+capacity*sizeof(StormDisplayRecord)
                    +columnSlots*columnMembers*sizeof(StormColumnDisplay),slots);
                acquireBridgeLease(bridge);displayBridge=bridge;
            }
            context=acquireContext();
            checked(cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));
            spatial=createStormSpatial(capacity,probes,stream);
            auto bytes=embeddedDevice(103);
            checked(cuModuleLoadData(&module,bytes.data()));
            checked(cuModuleGetFunction(&control,module,"applyStormControl"));
            checked(cuModuleGetFunction(&controlBatch,module,"applyStormControlBatch"));
            checked(cuModuleGetFunction(&advance,module,"advanceStormVortex"));
            checked(cuModuleGetFunction(&compactFeather,module,"compactStormFeatherImpacts"));
            checked(cuModuleGetFunction(&prepareSpatial,module,"prepareStormSpatial"));
            checked(cuModuleGetFunction(&positionSnapshot,module,"publishStormPositions"));
            checked(cuModuleGetFunction(&captureColumns,module,"captureStormColumns"));
            checked(cuModuleGetFunction(&domePrepare,module,"prepareStormDome"));
            checked(cuModuleGetFunction(&domeFinish,module,"finishStormDome"));
            checked(cuModuleGetFunction(&seal,module,"sealStormTick"));
            checked(cuModuleGetFunction(&displayPrepare,module,"prepareStormDisplay"));
            checked(cuModuleGetFunction(&displayPublish,module,"publishStormDisplay"));
            checked(cuModuleGetFunction(&displaySeal,module,"sealStormDisplay"));
            allocate(members,size_t(capacity)*sizeof(StormStepMember));
            allocate(domeSearches,2*sizeof(uint32_t));
            checked(cuMemsetD8Async(members,0,size_t(capacity)*sizeof(StormStepMember),stream));
            for(auto& s:ring){
                checked(cuEventCreate(&s.done,CU_EVENT_DISABLE_TIMING));
                checked(cuMemAllocHost(reinterpret_cast<void**>(&s.receipt),sizeof(StormStepReceipt)));
                allocate(s.deviceReceipt,sizeof(StormStepReceipt));
            }
            worker=std::thread([this]{work();});
        } catch (...) {
            if(stream)cuStreamSynchronize(stream); // failed initialization owns no shared slots
            release();
            throw;
        }
    }
    void release() {
        if(worker.joinable()){
            {
                std::lock_guard<std::mutex> lock(queueMutex);
                closing=true;
            }
            queueReady.notify_one();
            worker.join();
        }
        if(context)cuCtxSetCurrent(context);
        destroyStormSpatial(spatial);
        for(auto& s:ring){
            if(s.deviceReceipt)cuMemFree(s.deviceReceipt);
            if(s.receipt)cuMemFreeHost(s.receipt);
            if(s.terrainUpload)cuMemFreeHost(s.terrainUpload);
            if(s.controlUpload)cuMemFreeHost(s.controlUpload);
            if(s.playerUpload)cuMemFreeHost(s.playerUpload);
            if(s.domeBatch)cuMemFreeHost(s.domeBatch);
            if(s.featherReceipt)cuMemFreeHost(s.featherReceipt);
            if(s.done)cuEventDestroy(s.done);
            for(auto mark:s.marks)if(mark)cuEventDestroy(mark);
        }
        if(terrainRows)cuMemFree(terrainRows);
        if(terrainCells)cuMemFree(terrainCells);
        if(deviceControls)cuMemFree(deviceControls);
        if(devicePlayers)cuMemFree(devicePlayers);
        for(auto allocation:{columns,columnStates,columnRestores})if(allocation)cuMemFree(allocation);
        for(auto allocation:{domeAttacks,helixAttacks,domeAssignments,domeRoutes,domeSearches,domeBatch,domeRecords,domeKeys,domeFlowsDevice,featherCells,featherResult})
            if(allocation)cuMemFree(allocation);
        if(domeUpload)cuMemFreeHost(domeUpload);
        if(domeUploadDone)cuEventDestroy(domeUploadDone);
        if(members)cuMemFree(members);
        particleRtOwnedBytes-=owned;
        if(module)cuModuleUnload(module);
        if(stream)cuStreamDestroy(stream);
        if(context)releaseContext();
        if(displayBridge)releaseBridgeLease(displayBridge);
    }
    void installTerrain(uint64_t revision,const SpatialParticle* rows,int count,size_t bytes){
        if(stopped)throw std::runtime_error("Storm terrain submitted after a native fault");
        geometry=copyTerrainSnapshot(1,revision,rows,count,bytes,CMI_RESOURCE_STORM_SPATIAL_TERRAIN_LIMIT);
    }
    void installDome(JNIEnv* env,jfloatArray records,jintArray keys,jintArray flows){
        if(stopped||domeStaged)throw std::runtime_error("Dome table installation is one-shot");
        if(!records||!keys||!flows)throw std::runtime_error("Missing Dome template arrays");
        auto recordCount=env->GetArrayLength(records),keyCount=env->GetArrayLength(keys),flowCount=env->GetArrayLength(flows);
        if(recordCount!=domeMembers*CMI_RESOURCE_STORM_DOME_RECORD_WORDS||flowCount!=domeMembers
            ||keyCount<domeMembers*CMI_RESOURCE_STORM_DOME_NODE_WORDS||keyCount>67108864)
            throw std::runtime_error("Invalid Dome template layout");
        size_t recordBytes=size_t(recordCount)*4,keyBytes=size_t(keyCount)*4,flowBytes=size_t(flowCount)*4;
        try {
            auto upload=std::make_unique<DomeUpload>();
            upload->bytes.resize(recordBytes+keyBytes+flowBytes);
            upload->records=recordBytes;upload->keys=keyBytes;upload->flows=flowBytes;
            auto bytes=upload->bytes.data();
            env->GetFloatArrayRegion(records,0,recordCount,reinterpret_cast<jfloat*>(bytes));
            env->GetIntArrayRegion(keys,0,keyCount,reinterpret_cast<jint*>(bytes+recordBytes));
            env->GetIntArrayRegion(flows,0,flowCount,reinterpret_cast<jint*>(bytes+recordBytes+keyBytes));
            if(env->ExceptionCheck())throw std::runtime_error("Dome template copy failed");
            auto rec=reinterpret_cast<const float*>(bytes);
            auto flow=reinterpret_cast<const int*>(bytes+recordBytes+keyBytes);
            auto validSpan=[keyCount](float first,float count){
                return std::isfinite(first)&&std::isfinite(count)&&first>=0.f&&count>=2.f&&count<=4096.f
                        &&(double(first)+double(count))*CMI_RESOURCE_STORM_DOME_NODE_WORDS<=keyCount;
            };
            for(unsigned rank=0;rank<domeMembers;rank++){
                auto row=rec+rank*CMI_RESOURCE_STORM_DOME_RECORD_WORDS;
                for(unsigned i=0;i<CMI_RESOURCE_STORM_DOME_RECORD_WORDS;i++)if(!std::isfinite(row[i]))
                    throw std::runtime_error("Nonfinite Dome template record");
                if(flow[rank]<0||flow[rank]>=domeFlows||row[3]<0||row[3]>rank||row[7]<12
                    ||row[3]+row[7]>domeMembers||row[10]<=0.f||!validSpan(row[0],row[1])||!validSpan(row[8],row[12]))
                    throw std::runtime_error("Invalid Dome template record");
            }
            pendingDome=std::move(upload);
            domeStaged=true;
        }catch(const std::exception& e){remember(e.what());throw;}
    }
    void uploadDome(DomeUpload& upload){
        auto records=upload.records,keys=upload.keys,flows=upload.flows;
        checked(cuMemAllocHost(&domeUpload,upload.bytes.size()));
        std::memcpy(domeUpload,upload.bytes.data(),upload.bytes.size());
        auto bytes=static_cast<unsigned char*>(domeUpload);
        allocate(domeRecords,records);allocate(domeKeys,keys);allocate(domeFlowsDevice,flows);
        checked(cuEventCreate(&domeUploadDone,CU_EVENT_DISABLE_TIMING));
        checked(cuMemcpyHtoDAsync(domeRecords,bytes,records,stream));
        checked(cuMemcpyHtoDAsync(domeKeys,bytes+records,keys,stream));
        checked(cuMemcpyHtoDAsync(domeFlowsDevice,bytes+records+keys,flows,stream));
        checked(cuEventRecord(domeUploadDone,stream));
    }
    bool prepareDisplay(unsigned index){
        if(!displayBridge||stopped)return false;
        auto& display=displayRing.at(index);
        if(display.state==idle){
            display.ticket=releaseQueryInput(displayBridge,index);
            display.state=reserved;
        }
        return display.state==reserved&&queryInputReleased(displayBridge,index,display.ticket.value);
    }
    bool submit(const void* source,size_t bytes,int displayIndex,bool timed) {
        if(stopped||submitted-consumed==slots)return false;
        if(!source||bytes<sizeof(StormTick))throw std::runtime_error("Storm input buffer too small");
        StormTick input;std::memcpy(&input,source,sizeof(input));
        auto commands=static_cast<const unsigned char*>(source)+sizeof(input);
        validateHeader(input,bytes);
        if(displayIndex< -1||displayIndex>=int(slots)||(displayIndex>=0&&!displayBridge))
            throw std::runtime_error("Invalid native display slot");
        if(displayIndex>=0){
            const auto& display=displayRing[displayIndex];
            if(display.state!=idle&&(display.state!=reserved||!queryInputReleased(displayBridge,unsigned(displayIndex),display.ticket.value)))
                throw std::runtime_error("Native display slot still has an owner");
        }
        auto& s=ring[submitted%slots];
        s.payload.assign(commands,static_cast<const unsigned char*>(source)+bytes);
        s.timed=timed;
        s.ticket={};
        s.error.clear();
        if(displayIndex>=0){
            auto& display=displayRing[displayIndex];
            if(display.state==idle)display.ticket=releaseQueryInput(displayBridge,unsigned(displayIndex));
            display.sequence=input.sequence;
            display.state=pending;
            s.ticket=display.ticket;
        }
        s.tick=input;
        s.display=displayIndex;
        s.geometry=geometry;
        s.templateUpload=std::move(pendingDome);
        s.delay=delayNext;delayNext=0;
        s.state=queued;
        submitted=input.sequence;tick=input.tick;identity=input.identity;active=(input.active&1u)!=0;
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            queuedThrough=submitted;
        }
        queueReady.notify_one();
        return true;
    }
    void run(TickSlot& s){
        auto& input=s.tick;
        auto commands=s.payload.data();
        bool timed=s.timed;
        bool active=(input.active&1u)!=0;
        auto mark=[&](unsigned stage){if(timed)checked(cuEventRecord(s.marks[stage],stream));};
        const char* stage="Storm input validation";
        try {
            bind();
            if(workerFailed)throw std::runtime_error("Cancelled after prior native Storm fault");
            validatePayload(input,commands);
            stage="Storm input upload";
            collectDomeUpload();
            if(s.templateUpload){
                uploadDome(*s.templateUpload);
                s.templateUpload.reset();
            }
            if(timed)for(auto& event:s.marks)if(!event)checked(cuEventCreate(&event,CU_EVENT_DEFAULT));
            if(input.terrainRows){
                if(!terrainRows)allocate(terrainRows,rowBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS));
                if(!terrainCells)allocate(terrainCells,cellBytes(CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES));
                if(!s.terrainUpload)checked(cuMemAllocHost(&s.terrainUpload,maxTerrainUpload()));
                std::memcpy(s.terrainUpload,commands+commandBytes(input.controls),rowBytes(input.terrainRows)+cellBytes(input.terrainPages));
            }
            if(input.playerCount){
                size_t limit=playerBytes(CMI_RESOURCE_STORM_PLAYER_LIMIT);
                if(!devicePlayers)allocate(devicePlayers,limit);
                if(!s.playerUpload)checked(cuMemAllocHost(&s.playerUpload,limit));
                auto start=commands+commandBytes(input.controls)+rowBytes(input.terrainRows)+cellBytes(input.terrainPages);
                std::memcpy(s.playerUpload,start,playerBytes(input.playerCount));
            }
            if(s.delay)
                checked(cuLaunchHostFunc(stream,injectedDelay,reinterpret_cast<void*>(uintptr_t(s.delay))));
            mark(0);
            if(input.terrainRows){
                auto rows=rowBytes(input.terrainRows);
                checked(cuMemcpyHtoDAsync(terrainRows,s.terrainUpload,rows,stream));
                if(input.terrainPages)checked(cuMemcpyHtoDAsync(terrainCells,static_cast<unsigned char*>(s.terrainUpload)+rows,cellBytes(input.terrainPages),stream));
                terrainMask=input.terrainRows-1;
                terrainRevision++;
            }
            if(input.playerCount)checked(cuMemcpyHtoDAsync(devicePlayers,s.playerUpload,playerBytes(input.playerCount),stream));
            stage="Storm controls";
            applyControls(s,input,commands);
            if(input.positionSnapshot||input.columnCaptureCount)ensureBatch();
            bool feathers=input.featherImpacts&&active&&input.population;
            if(feathers){
                if(!featherCells)allocate(featherCells,sizeof(StormFeatherCell)*featherImpactLimit);
                if(!featherResult)allocate(featherResult,sizeof(StormFeatherReceipt));
                checked(cuMemsetD8Async(featherCells,0,sizeof(StormFeatherCell)*featherImpactLimit,stream));
                checked(cuMemsetD32Async(featherResult,0,1,stream));
            }
            auto view=domeView((input.active&2u)!=0,feathers);
            if(view.batch){
                void* args[]={&view,&input};
                checked(cuLaunchKernel(domePrepare,domeSlots,1,1,128,1,1,0,stream,args,nullptr));
            }
            mark(1);
            if(active&&input.population){
                stage="Storm spatial query";
                checked(cuMemsetD32Async(domeSearches,0,2,stream));
                StormTerrain terrain{reinterpret_cast<const int4*>(terrainRows),reinterpret_cast<const uint32_t*>(terrainCells),terrainMask,terrainRevision};
                auto hints=queryStormSpatial(*spatial,unsigned((input.sequence-1)%slots),prepareSpatial,members,input,
                    s.geometry.get(),timed?s.marks.data()+2:nullptr);
                stage="Storm member advance";
                void* args[]={&members,&input,&terrain,&view,&devicePlayers,&hints};launch(advance,input.population,args);
                mark(5);
                if(input.positionSnapshot){
                    void* snapshotArgs[]={&members,&input,&devicePlayers,&domeBatch};
                    launch(positionSnapshot,input.population,snapshotArgs);
                }
            }else for(unsigned stage=2;stage<=5;stage++)mark(stage);
            stage="Storm batch readback";
            if(feathers){
                if(!s.featherReceipt)checked(cuMemAllocHost(reinterpret_cast<void**>(&s.featherReceipt),sizeof(StormFeatherReceipt)));
                void* args[]={&featherCells,&featherResult};
                launch(compactFeather,featherImpactLimit,args);
                checked(cuMemcpyDtoHAsync(s.featherReceipt,featherResult,sizeof(StormFeatherReceipt),stream));
            }
            if(view.batch){
                if(input.columnCaptureCount){
                    void* args[]={&members,&input,&view};
                    checked(cuLaunchKernel(captureColumns,input.columnCaptureCount,1,1,columnMembers,1,1,0,stream,args,nullptr));
                }
                if(view.records&&view.attacks){
                    void* args[]={&view};
                    checked(cuLaunchKernel(domeFinish,domeSlots,1,1,128,1,1,0,stream,args,nullptr));
                }
                if(!s.domeBatch)checked(cuMemAllocHost(reinterpret_cast<void**>(&s.domeBatch),sizeof(StormDomeBatch)));
                checked(cuMemcpyDtoHAsync(s.domeBatch,domeBatch,batchBytes(input),stream));
            }
            mark(6);
            stage="Storm display and receipt";
            CUdeviceptr output{};
            const auto* ticket=s.display<0?nullptr:&s.ticket;
            if(ticket){
                CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};
                wait.params.fence.value=ticket->value;
                checked(cuWaitExternalSemaphoresAsync(&ticket->inputReady,&wait,1,stream));
                output=ticket->output;
                void* prepareArgs[]={&output,&input,&capacity};
                checked(cuLaunchKernel(displayPrepare,1,1,1,1,1,1,0,stream,prepareArgs,nullptr));
                if(active&&input.population){
                    void* publishArgs[]={&output,&members,&input,&view};
                    launch(displayPublish,input.population,publishArgs);
                }
                void* sealArgs[]={&output};
                checked(cuLaunchKernel(displaySeal,1,1,1,1,1,1,0,stream,sealArgs,nullptr));
            }
            void* args[]={&s.deviceReceipt,&input,&output};
            checked(cuLaunchKernel(seal,1,1,1,1,1,1,0,stream,args,nullptr));
            if(ticket){
                CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};
                signal.params.fence.value=ticket->value;
                checked(cuSignalExternalSemaphoresAsync(&ticket->outputReady,&signal,1,stream));
            }
            checked(cuMemcpyDtoHAsync(s.receipt,s.deviceReceipt,sizeof(StormStepReceipt),stream));
            mark(7);
            checked(cuEventRecord(s.done,stream));
            s.state=pending;
        } catch(const std::exception& e) {
            s.error=std::string(stage)+": "+e.what();
            workerFailed=true;
            s.state=cuEventRecord(s.done,stream)==CUDA_SUCCESS?failed:undrained;
        }
    }
    int poll(uint64_t sequence) {
        if(sequence!=consumed+1||sequence>submitted)throw std::runtime_error("Storm receipt is not the oldest input");
        auto& s=ring[consumed%slots];
        if(s.state==queued)return pending;
        if(!s.error.empty())rememberTickFailure("Storm enqueue failed",s.tick,s.error.c_str());
        if(s.state==pending){
            bind();
            auto state=cuEventQuery(s.done);
            if(state==CUDA_ERROR_NOT_READY)return pending;
            if(state!=CUDA_SUCCESS){
                const char* name=nullptr;
                const char* description=nullptr;
                cuGetErrorName(state,&name);
                cuGetErrorString(state,&description);
                auto detail=std::string(name?name:"CUDA error")+" ("+std::to_string(int(state))+")";
                if(description)detail+=std::string(": ")+description;
                rememberTickFailure("Storm CUDA completion failed",s.tick,detail.c_str());
                workerFailed=true;
                s.state=undrained;
            }
            else if(s.receipt->sequence!=s.tick.sequence||s.receipt->tick!=s.tick.tick||s.receipt->identity!=s.tick.identity){
                remember("Storm CUDA receipt identity mismatch");s.state=failed;
            } else s.state=complete;
        }
        if(s.state==failed){
            bind();
            auto state=cuEventQuery(s.done);
            if(state==CUDA_ERROR_NOT_READY)return pending;
            if(state!=CUDA_SUCCESS)s.state=undrained;
        }
        return s.state==complete?complete:s.state==pending?pending:failed;
    }
    int consume(uint64_t sequence) {
        int result=poll(sequence);
        if(result==pending)throw std::runtime_error("Storm receipt still pending");
        auto& s=ring[consumed%slots];
        if(s.display>=0){
            auto& display=displayRing[s.display];
            if(result==complete)consumeQueryOutput(displayBridge,s.display,display.ticket.value);
            else if(s.state!=undrained)retireQueryWithoutOutput(displayBridge,s.display,display.ticket.value);
            display.state=s.state==undrained?undrained:complete;
        }
        if(s.state!=undrained)s.state=idle;
        s.geometry.reset();
        ++consumed;
        return result;
    }
    bool recycleDisplay(unsigned index,uint64_t sequence,void* fence){
        auto& display=displayRing.at(index);
        if(display.sequence!=sequence||!fence)throw std::runtime_error("Invalid native display retirement");
        if(display.state!=complete)return false;
        for(const auto& earlier:displayRing)if(earlier.sequence&&earlier.sequence<sequence)return false;
        using Wait=unsigned(APIENTRY*)(void*,unsigned,unsigned long long);
        auto wait=reinterpret_cast<Wait>(wglGetProcAddress("glClientWaitSync"));
        if(!wait)throw std::runtime_error("Display fence polling unavailable");
        auto status=wait(fence,1u,0);
        if(status==0x911Bu)return false;
        if(status!=0x911Au&&status!=0x911Cu)throw std::runtime_error("Native display fence failed");
        display={};
        if(!stopped)prepareDisplay(index);
        return true;
    }
    bool copyDomeBatch(uint64_t sequence,void* destination,size_t bytes){
        if(poll(sequence)!=complete)throw std::runtime_error("Dome result is not ready");
        auto& s=ring[consumed%slots];
        if(!s.domeBatch)return false;
        if(!destination||bytes<sizeof(StormDomeBatch))throw std::runtime_error("Dome result buffer too small");
        std::memcpy(destination,s.domeBatch,batchBytes(s.tick));
        return true;
    }
    unsigned copyFeatherImpacts(uint64_t sequence,void* destination,size_t bytes){
        if(poll(sequence)!=complete)throw std::runtime_error("Feather result is not ready");
        auto& s=ring[consumed%slots];
        if(!s.tick.featherImpacts||!s.tick.population||!(s.tick.active&1u)||!s.featherReceipt)return 0;
        unsigned count=std::min(s.featherReceipt->count,featherImpactLimit);
        if(count){
            if(!destination||bytes<sizeof(float4)*count)throw std::runtime_error("Feather result buffer too small");
            std::memcpy(destination,s.featherReceipt->rows,sizeof(float4)*count);
        }
        return count;
    }
    std::array<double,8> timing(uint64_t sequence){
        if(poll(sequence)!=complete)throw std::runtime_error("Storm timing requires a completed receipt");
        auto& s=ring[consumed%slots];
        if(!s.timed)throw std::runtime_error("Storm tick timing was not requested");
        std::array<double,8> values{};
        for(unsigned i=0;i<7;i++){
            float ms;
            checked(cuEventElapsedTime(&ms,s.marks[i],s.marks[i+1]));
            values[i]=ms;
        }
        values[7]=s.receipt->displayRecords;
        return values;
    }
    const std::string& diagnostics() const { return error; }
    void injectDelay(unsigned milliseconds){
        if(milliseconds>500)throw std::runtime_error("Storm test delay must be within 0..500 ms");
        delayNext=milliseconds;
    }
    bool close() {
        if(consumed!=submitted)return false;
        for(unsigned index=0;index<slots;++index){
            auto& display=displayRing[index];
            if(display.state==reserved){
                if(!queryInputReleased(displayBridge,index,display.ticket.value))return false;
                retireQueryWithoutOutput(displayBridge,index,display.ticket.value);
                display={};
            }
            if(display.state!=idle)return false;
        }
        bind();
        // Without a trustworthy fence, keep the allocations quarantined
        // rather than allowing them to alias another session.
        if(cuStreamQuery(stream)!=CUDA_SUCCESS)return false;
        release();
        return true;
    }
};
StormControlSession& session(jlong handle) {
    if(!handle)throw std::runtime_error("Missing native Storm session");
    return *reinterpret_cast<StormControlSession*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createStormControl)(JNIEnv* env,jclass,jint capacity,jlong displayBridge,jint probes) {
    try { return reinterpret_cast<jlong>(new StormControlSession(unsigned(capacity),reinterpret_cast<void*>(displayBridge),unsigned(probes))); }
    catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(installStormTerrain)(JNIEnv* env,jclass,jlong handle,jlong revision,
        jobject rows,jint count){
    try{
        auto bytes=env->GetDirectBufferCapacity(rows);
        auto data=static_cast<const SpatialParticle*>(env->GetDirectBufferAddress(rows));
        session(handle).installTerrain(revision,data,count,size_t(std::max<jlong>(0,bytes)));
    }catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(submitStormTick)(JNIEnv* env,jclass,jlong handle,jobject packet,jint bytes,jint display,jboolean timed) {
    try {
        if(!packet||bytes<0||env->GetDirectBufferCapacity(packet)<bytes)throw std::runtime_error("Storm input must be a direct buffer");
        return session(handle).submit(env->GetDirectBufferAddress(packet),size_t(bytes),display,timed);
    } catch(const std::exception& e){fail(env,e);return JNI_FALSE;}
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(prepareStormDisplaySlot)(JNIEnv* env,jclass,jlong handle,jint index) {
    try { return session(handle).prepareDisplay(unsigned(index)); }
    catch(const std::exception& e){fail(env,e);return JNI_FALSE;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_NAME(stormTickTiming)(JNIEnv* env,jclass,jlong handle,jlong sequence){
    try{
        auto values=session(handle).timing(sequence);
        auto result=env->NewDoubleArray(jsize(values.size()));
        if(result)env->SetDoubleArrayRegion(result,0,jsize(values.size()),values.data());
        return result;
    }catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(delayStormControl)(JNIEnv* env,jclass,jlong handle,jint milliseconds){
    try{session(handle).injectDelay(unsigned(milliseconds));}
    catch(const std::exception& error){fail(env,error);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(recycleStormDisplay)(JNIEnv* env,jclass,jlong handle,jint slot,jlong sequence,jlong fence) {
    try{return session(handle).recycleDisplay(unsigned(slot),uint64_t(sequence),reinterpret_cast<void*>(fence));}
    catch(const std::exception& e){fail(env,e);return JNI_FALSE;}
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(pollStormTick)(JNIEnv* env,jclass,jlong handle,jlong sequence) {
    try { return session(handle).poll(uint64_t(sequence)); }
    catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(consumeStormTick)(JNIEnv* env,jclass,jlong handle,jlong sequence) {
    try { return session(handle).consume(uint64_t(sequence)); }
    catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(installStormDome)(JNIEnv* env,jclass,jlong handle,jfloatArray records,jintArray keys,jintArray flows) {
    try{session(handle).installDome(env,records,keys,flows);}
    catch(const std::exception& e){if(!env->ExceptionCheck())fail(env,e);}
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(copyStormDomeBatch)(JNIEnv* env,jclass,jlong handle,jlong sequence,jobject destination) {
    try {
        auto size=destination?env->GetDirectBufferCapacity(destination):-1;
        if(size<0)throw std::runtime_error("Dome result must be a direct buffer");
        return session(handle).copyDomeBatch(uint64_t(sequence),env->GetDirectBufferAddress(destination),size_t(size));
    }catch(const std::exception& e){fail(env,e);return JNI_FALSE;}
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(copyStormFeatherImpacts)(JNIEnv* env,jclass,jlong handle,jlong sequence,jobject destination) {
    try {
        auto bytes=destination?env->GetDirectBufferCapacity(destination):-1;
        if(bytes<0)throw std::runtime_error("Feather result must be a direct buffer");
        return jint(session(handle).copyFeatherImpacts(sequence,env->GetDirectBufferAddress(destination),size_t(bytes)));
    } catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(stormControlDiagnostics)(JNIEnv* env,jclass,jlong handle) {
    try { return env->NewStringUTF(session(handle).diagnostics().c_str()); }
    catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(closeStormControl)(JNIEnv* env,jclass,jlong handle) {
    try {
        auto& s=session(handle);
        if(!s.close())return JNI_FALSE;
        delete &s;
        return JNI_TRUE;
    } catch(const std::exception& e){fail(env,e);return JNI_FALSE;}
}
