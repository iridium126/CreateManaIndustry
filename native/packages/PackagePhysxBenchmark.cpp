#include "PxPhysicsAPI.h"
#include "cudamanager/PxCudaContext.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <random>
#include <vector>
#define NOMINMAX
#include <windows.h>
#include <dbghelp.h>
#include "PhysxDistribution.hpp"

// Keep native failures reviewable even when a worker crashes before CSV flush.
// The diagnostic runs only in this standalone benchmark, never in Minecraft.
static LONG WINAPI nativeFailure(EXCEPTION_POINTERS* exception) {
    auto process = GetCurrentProcess();
    SymInitialize(process, nullptr, TRUE);
    std::cerr << "Native exception: 0x" << std::hex << exception->ExceptionRecord->ExceptionCode << "\n";
    void* frames[32]; auto size = CaptureStackBackTrace(0, 32, frames, nullptr);
    for (USHORT i = 0; i < size; ++i) {
        DWORD64 address = reinterpret_cast<DWORD64>(frames[i]);
        IMAGEHLP_MODULE64 moduleInfo{}; moduleInfo.SizeOfStruct = sizeof(moduleInfo);
        if (SymGetModuleInfo64(process, address, &moduleInfo))
            std::cerr << moduleInfo.ModuleName << "+0x" << address - moduleInfo.BaseOfImage << '\n';
        alignas(SYMBOL_INFO) char storage[sizeof(SYMBOL_INFO) + MAX_SYM_NAME]{};
        auto* symbol = reinterpret_cast<SYMBOL_INFO*>(storage);
        symbol->SizeOfStruct = sizeof(SYMBOL_INFO); symbol->MaxNameLen = MAX_SYM_NAME;
        DWORD64 displacement = 0;
        if (SymFromAddr(process, address, &displacement, symbol))
            std::cerr << "  " << symbol->Name << "+0x" << displacement << '\n';
    }
    std::cerr << std::dec;
    SymCleanup(process);
    return EXCEPTION_EXECUTE_HANDLER;
}

using namespace physx;
using Clock = std::chrono::steady_clock;
static_assert(sizeof(PxVec3) == 12 && sizeof(PxTransform) == 28, "Direct GPU/PTX layout contract changed");
static constexpr size_t guardBytes = 64;
static double ms(Clock::time_point from) { return std::chrono::duration<double, std::milli>(Clock::now() - from).count(); }
static void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
static void cudaCheck(PxCUresult result) { if (result != 0) throw std::runtime_error("CUDA operation failed: " + std::to_string(result)); }
static double percentile(std::vector<double> values, double p) { std::sort(values.begin(), values.end()); return values[std::min(values.size() - 1, size_t(std::ceil(p * values.size()) - 1))]; }

// Small portable PTX kernel: the same per-layer velocity impulse used by the
// compute fixture. It is compiled by the driver, not by a local CUDA Toolkit.
static const char* drivePtx = R"PTX(
.version 6.0
.target sm_70
.address_size 64
.visible .entry drive(.param .u64 velocities, .param .u32 count, .param .f32 phase) {
 .reg .pred %p;
 .reg .b32 %r<7>;
 .reg .b64 %d<4>;
 .reg .f32 %f<9>;
 ld.param.u64 %d0, [velocities];
 ld.param.u32 %r0, [count];
 ld.param.f32 %f0, [phase];
 mov.u32 %r1, %ctaid.x; mov.u32 %r2, %ntid.x; mov.u32 %r3, %tid.x;
 mad.lo.u32 %r4, %r1, %r2, %r3;
 setp.ge.u32 %p, %r4, %r0; @%p bra done;
 shr.u32 %r5, %r4, 12; cvt.rn.f32.u32 %f1, %r5;
 fma.rn.f32 %f2, %f1, 0f3DF5C28F, %f0;
 cos.approx.f32 %f3, %f2; sin.approx.f32 %f4, %f2;
 mul.wide.u32 %d1, %r4, 12; add.u64 %d2, %d0, %d1;
 ld.global.f32 %f5, [%d2]; ld.global.f32 %f6, [%d2+8];
 fma.rn.f32 %f7, %f3, 0f3DA3D70A, %f5;
 fma.rn.f32 %f8, %f4, 0f3D75C28F, %f6;
 st.global.f32 [%d2], %f7; st.global.f32 [%d2+8], %f8;
done: ret;
}
)PTX";

struct Errors : PxErrorCallback {
    std::atomic<unsigned> failures{0};
    void reportError(PxErrorCode::Enum code, const char* message, const char* file, int line) override {
        // Contact/patch overflows are warnings. Treat them as failed quality,
        // never a successful, faster measurement after discarded contacts.
        if (code != PxErrorCode::eDEBUG_INFO && code != PxErrorCode::eNO_ERROR) failures++;
        std::cerr << "PhysX " << code << " " << file << ":" << line << " " << message << '\n';
    }
};

struct ContextLock {
    PxCudaContextManager* manager;
    explicit ContextLock(PxCudaContextManager* m) : manager(m) { manager->acquireContext(); }
    ~ContextLock() { manager->releaseContext(); }
};

struct Simulation {
    PhysxDistribution distribution;
    Errors errors;
    PxFoundation* foundation = nullptr;
    PxPhysics* physics = nullptr;
    WorkerDispatcher* dispatcher = nullptr;
    PxScene* scene = nullptr;
    PxRigidStatic* floor = nullptr;
    PxMaterial* material = nullptr;
    PxCudaContextManager* manager = nullptr;
    std::vector<PxRigidDynamic*> bodies;
    CUdeviceptr indicesD = 0, posesD = 0, velocitiesD = 0;
    CUstream stream = nullptr;
    CUevent copied = nullptr, ready = nullptr, applied = nullptr;
    CUmodule module = nullptr;
    CUfunction driveKernel = nullptr;
    PxTransform* hostPoses = nullptr;
    PxVec3* hostVelocities = nullptr;
    bool gpu = false;
    bool stepped = false;
    bool registeredErrors = false;
    unsigned count = 0;

    void init(unsigned n, bool useGpu, bool tgs, unsigned iterations, bool staggered, unsigned threads, bool driven) {
        gpu = useGpu; count = n;
        distribution.init(gpu);
        physics = distribution.physics; manager = distribution.manager;
        if (manager) std::cout << "GPU: " << manager->getDeviceName() << "; CUDA driver=" << manager->getDriverVersion()
                               << "; device memory=" << manager->getDeviceTotalMemBytes() << " B\n";
        foundation = &physics->getFoundation();
        foundation->registerErrorCallback(errors); registeredErrors = true;
        require(physics != nullptr, "Physics allocation failed");
        dispatcher = new WorkerDispatcher(threads);
        PxSceneDesc desc(physics->getTolerancesScale());
        desc.gravity = PxVec3(0, -32, 0);
        desc.cpuDispatcher = dispatcher;
        desc.filterShader = packageFilter;
        desc.solverType = tgs ? PxSolverType::eTGS : PxSolverType::ePGS;
        desc.flags |= PxSceneFlag::eENABLE_PCM | PxSceneFlag::eDISABLE_SLEEPING;
        if (gpu) {
            desc.cudaContextManager = manager;
            desc.flags |= PxSceneFlag::eENABLE_GPU_DYNAMICS | PxSceneFlag::eENABLE_DIRECT_GPU_API;
            desc.broadPhaseType = PxBroadPhaseType::eGPU;
            desc.gpuDynamicsConfig.maxRigidContactCount = std::max(524288u, n * 64u);
            desc.gpuDynamicsConfig.maxRigidPatchCount = std::max(81920u, n * 16u);
            desc.gpuDynamicsConfig.foundLostPairsCapacity = std::max(262144u, n * 16u);
            desc.gpuDynamicsConfig.heapCapacity = 128u * 1024u * 1024u;
        }
        // createScene validates the descriptor in the distribution's host SDK.
        // desc.isValid() calls a non-virtual helper not exported by ovphysx.
        scene = physics->createScene(desc);
        require(scene != nullptr, "Scene allocation failed");
        material = physics->createMaterial(.6f, .6f, 0);
        floor = physics->createRigidStatic(PxTransform(PxVec3(32, .5f, 32)));
        auto* floorShape = physics->createShape(PxBoxGeometry(256, .5f, 256), *material, true);
        floorShape->setRestOffset(0); floorShape->setContactOffset(.02f);
        floor->attachShape(*floorShape); floorShape->release(); scene->addActor(*floor);
        bodies.reserve(n);
        for (unsigned i = 0; i < n; ++i) {
            unsigned layer = i / 4096;
            float shift = staggered && (layer & 1) ? .4f : 0;
            auto* body = physics->createRigidDynamic(PxTransform(PxVec3(2 + (i % 64) * 1.03125f + shift,
                1.5f + layer * 1.03125f, 2 + ((i / 64) % 64) * 1.03125f + shift)));
            auto* shape = physics->createShape(PxBoxGeometry(.5f, .5f, .5f), *material, true);
            shape->setRestOffset(0); shape->setContactOffset(.02f);
            body->attachShape(*shape); shape->release();
            body->setMass(1); body->setMassSpaceInertiaTensor(PxVec3(1.0f / 6.0f));
            body->setRigidDynamicLockFlags(PxRigidDynamicLockFlag::eLOCK_ANGULAR_X | PxRigidDynamicLockFlag::eLOCK_ANGULAR_Y | PxRigidDynamicLockFlag::eLOCK_ANGULAR_Z);
            body->setSolverIterationCounts(iterations, 1);
            body->setLinearDamping(.4f); body->setAngularDamping(0);
            float phase = layer * .12f;
            body->setLinearVelocity(PxVec3(driven ? .08f * std::cos(phase) : 0, -1,
                                           driven ? .06f * std::sin(phase) : 0));
            body->setMaxDepenetrationVelocity(32);
            bodies.push_back(body);
        }
        std::vector<PxActor*> actors(bodies.begin(), bodies.end());
        if (n) scene->addActors(actors.data(), n);
        if (gpu && n) {
            ContextLock lock(manager); auto* cuda = manager->getCudaContext();
            cudaCheck(cuda->streamCreate(&stream, 1));
            cudaCheck(cuda->eventCreate(&copied, 2)); cudaCheck(cuda->eventCreate(&ready, 2)); cudaCheck(cuda->eventCreate(&applied, 2));
            cudaCheck(cuda->memAlloc(&indicesD, size_t(n) * sizeof(PxU32)));
            cudaCheck(cuda->memAlloc(&posesD, size_t(n) * sizeof(PxTransform) + guardBytes));
            cudaCheck(cuda->memAlloc(&velocitiesD, size_t(n) * sizeof(PxVec3) + guardBytes));
            cudaCheck(cuda->memsetD8(posesD + size_t(n) * sizeof(PxTransform), 0xa5, guardBytes));
            cudaCheck(cuda->memsetD8(velocitiesD + size_t(n) * sizeof(PxVec3), 0xa5, guardBytes));
            std::vector<PxU32> indices; indices.reserve(n);
            for (auto* body : bodies) indices.push_back(body->getGPUIndex());
            auto unique = indices; std::sort(unique.begin(), unique.end());
            require(unique.back() != 0xffffffffu && std::adjacent_find(unique.begin(), unique.end()) == unique.end(), "GPU actor index invalid or duplicated");
            cudaCheck(cuda->memcpyHtoD(indicesD, indices.data(), indices.size() * sizeof(PxU32)));
            cudaCheck(cuda->memHostAlloc(reinterpret_cast<void**>(&hostPoses), size_t(n) * sizeof(PxTransform) + guardBytes, 0));
            cudaCheck(cuda->memHostAlloc(reinterpret_cast<void**>(&hostVelocities), size_t(n) * sizeof(PxVec3) + guardBytes, 0));
            cudaCheck(cuda->moduleLoadDataEx(&module, drivePtx, 0, nullptr, nullptr));
            cudaCheck(cuda->moduleGetFunction(&driveKernel, module, "drive"));
        }
        require(errors.failures == 0, "Invalid PhysX setup");
    }

    struct Sample { double wall, submit, fetch, drive; };
    Sample step(float dt, unsigned stepIndex, bool driven) {
        auto begin = Clock::now();
        if (driven && stepped && count) {
            float phase = stepIndex * .15f;
            if (gpu) {
                ContextLock lock(manager); auto* cuda = manager->getCudaContext();
                auto& api = scene->getDirectGPUAPI();
                require(api.getRigidDynamicData(reinterpret_cast<void*>(velocitiesD), reinterpret_cast<PxRigidDynamicGPUIndex*>(indicesD),
                    PxRigidDynamicGPUAPIReadType::eLINEAR_VELOCITY, count, nullptr, copied), "GPU velocity request failed");
                cudaCheck(cuda->streamWaitEvent(stream, copied, 0));
                void* args[] = { &velocitiesD, &count, &phase };
                cudaCheck(cuda->launchKernel(driveKernel, (count + 63) / 64, 1, 1, 64, 1, 1, 0, stream, args, nullptr, __FILE__, __LINE__));
                cudaCheck(cuda->eventRecord(ready, stream));
                require(api.setRigidDynamicData(reinterpret_cast<void*>(velocitiesD), reinterpret_cast<PxRigidDynamicGPUIndex*>(indicesD),
                    PxRigidDynamicGPUAPIWriteType::eLINEAR_VELOCITY, count, ready, applied), "GPU impulse request failed");
                // Standalone measurement thread only. Production must poll these
                // events from a worker; main/render threads may never call wait.
                cudaCheck(cuda->eventSynchronize(applied));
            } else for (unsigned i = 0; i < count; ++i) {
                float p = phase + (i / 4096) * .12f;
                auto v = bodies[i]->getLinearVelocity();
                bodies[i]->setLinearVelocity(v + PxVec3(.08f * std::cos(p), 0, .06f * std::sin(p)), false);
            }
        }
        double drive = ms(begin); auto submitAt = Clock::now();
        scene->simulate(dt); double submit = ms(submitAt); auto fetchAt = Clock::now();
        PxU32 status = 0; require(scene->fetchResults(true, &status), "Scene fetch failed");
        require(status == 0 && errors.failures == 0, "Simulation reported errors or discarded contacts");
        stepped = true;
        return { ms(begin), submit, ms(fetchAt), drive };
    }

    double read(std::vector<PxTransform>& poses, std::vector<PxVec3>& velocities) {
        auto begin = Clock::now(); poses.resize(count); velocities.resize(count);
        if (!count) return ms(begin);
        if (gpu) {
            ContextLock lock(manager); auto* cuda = manager->getCudaContext(); auto& api = scene->getDirectGPUAPI();
            require(api.getRigidDynamicData(reinterpret_cast<void*>(posesD), reinterpret_cast<PxRigidDynamicGPUIndex*>(indicesD),
                PxRigidDynamicGPUAPIReadType::eGLOBAL_POSE, count, nullptr, copied), "GPU pose request failed");
            cudaCheck(cuda->streamWaitEvent(stream, copied, 0));
            cudaCheck(cuda->memcpyDtoHAsync(hostPoses, posesD, size_t(count) * sizeof(PxTransform) + guardBytes, stream));
            require(api.getRigidDynamicData(reinterpret_cast<void*>(velocitiesD), reinterpret_cast<PxRigidDynamicGPUIndex*>(indicesD),
                PxRigidDynamicGPUAPIReadType::eLINEAR_VELOCITY, count, nullptr, copied), "GPU velocity read failed");
            cudaCheck(cuda->streamWaitEvent(stream, copied, 0));
            cudaCheck(cuda->memcpyDtoHAsync(hostVelocities, velocitiesD, size_t(count) * sizeof(PxVec3) + guardBytes, stream));
            cudaCheck(cuda->streamSynchronize(stream));
            auto* poseGuard = reinterpret_cast<unsigned char*>(hostPoses) + size_t(count) * sizeof(PxTransform);
            auto* velocityGuard = reinterpret_cast<unsigned char*>(hostVelocities) + size_t(count) * sizeof(PxVec3);
            for (size_t i = 0; i < guardBytes; ++i) require(poseGuard[i] == 0xa5 && velocityGuard[i] == 0xa5, "Direct GPU output sentinel overwritten");
            std::copy(hostPoses, hostPoses + count, poses.begin());
            std::copy(hostVelocities, hostVelocities + count, velocities.begin());
        } else for (unsigned i = 0; i < count; ++i) { poses[i] = bodies[i]->getGlobalPose(); velocities[i] = bodies[i]->getLinearVelocity(); }
        return ms(begin);
    }

    ~Simulation() {
        if (scene) scene->release();
        if (floor) floor->release();
        for (auto* body : bodies) body->release();
        if (material) material->release();
        delete dispatcher;
        if (manager) {
            { ContextLock lock(manager); auto* cuda = manager->getCudaContext();
                if (indicesD) cuda->memFree(indicesD); if (posesD) cuda->memFree(posesD); if (velocitiesD) cuda->memFree(velocitiesD);
                if (hostPoses) cuda->memFreeHost(hostPoses); if (hostVelocities) cuda->memFreeHost(hostVelocities);
                if (module) cuda->moduleUnload(module); if (stream) cuda->streamDestroy(stream);
                if (copied) cuda->eventDestroy(copied); if (ready) cuda->eventDestroy(ready); if (applied) cuda->eventDestroy(applied);
            }
        }
        if (registeredErrors) foundation->deregisterErrorCallback(errors);
    }
};

struct Cell { int x, y, z; bool operator==(const Cell& other) const { return x == other.x && y == other.y && z == other.z; } };
struct Hash { size_t operator()(const Cell& c) const { return size_t(uint32_t(c.x) * 73856093u ^ uint32_t(c.y) * 19349663u ^ uint32_t(c.z) * 83492791u); } };
struct Quality { unsigned pairs = 0, moving = 0, nonfinite = 0; float overlap = 0, ground = 0, angular = 0; };
static Quality inspect(const std::vector<PxTransform>& poses, const std::vector<PxVec3>& velocities) {
    Quality q; std::unordered_map<Cell, std::vector<unsigned>, Hash> cells;
    for (unsigned i = 0; i < poses.size(); ++i) {
        const auto& p = poses[i]; const auto& v = velocities[i];
        if (!p.isFinite() || !v.isFinite()) { q.nonfinite++; continue; }
        q.ground = std::max(q.ground, 1.5f - p.p.y);
        q.angular = std::max(q.angular, std::abs(p.q.x) + std::abs(p.q.y) + std::abs(p.q.z));
        if (v.magnitudeSquared() > 1e-8f) q.moving++;
        cells[{int(std::floor(p.p.x)), int(std::floor(p.p.y)), int(std::floor(p.p.z))}].push_back(i);
    }
    for (unsigned i = 0; i < poses.size(); ++i) {
        const auto& p = poses[i].p; if (!p.isFinite()) continue;
        Cell center{int(std::floor(p.x)), int(std::floor(p.y)), int(std::floor(p.z))};
        for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) for (int z = -1; z <= 1; ++z) {
            auto found = cells.find({center.x + x, center.y + y, center.z + z}); if (found == cells.end()) continue;
            for (unsigned j : found->second) if (j > i) {
                auto delta = p - poses[j].p;
                float depth = std::min({1 - std::abs(delta.x), 1 - std::abs(delta.y), 1 - std::abs(delta.z)});
                q.overlap = std::max(q.overlap, depth); if (depth > 1e-4f) q.pairs++;
            }
        }
    }
    return q;
}

static void accumulate(Quality& peak, const Quality& q) {
    peak.pairs = std::max(peak.pairs, q.pairs); peak.moving = std::min(peak.moving, q.moving);
    peak.nonfinite = std::max(peak.nonfinite, q.nonfinite); peak.overlap = std::max(peak.overlap, q.overlap);
    peak.ground = std::max(peak.ground, q.ground); peak.angular = std::max(peak.angular, q.angular);
}

static void probeReference() {
    std::mt19937 random(567);
    for (unsigned n : {0u, 1u, 63u, 64u, 65u, 129u}) {
        std::vector<PxTransform> poses; std::vector<PxVec3> velocities(n, PxVec3(1, 0, 0));
        auto coordinate = [&] { return float(random() % 10000) / 2000 - 2.5f; };
        for (unsigned i = 0; i < n; ++i) poses.emplace_back(PxVec3(coordinate(), coordinate(), coordinate()));
        auto q = inspect(poses, velocities); unsigned pairs = 0; float overlap = 0, ground = 0;
        for (unsigned i = 0; i < n; ++i) {
            ground = std::max(ground, 1.5f - poses[i].p.y);
            for (unsigned j = i + 1; j < n; ++j) {
                auto d = poses[i].p - poses[j].p;
                float depth = std::min({1 - std::abs(d.x), 1 - std::abs(d.y), 1 - std::abs(d.z)});
                overlap = std::max(overlap, depth); if (depth > 1e-4f) pairs++;
            }
        }
        require(q.pairs == pairs && std::abs(q.overlap - overlap) < 1e-6f && q.ground == ground && q.moving == n && q.nonfinite == 0, "Contact grid reference mismatch");
    }
    std::vector<PxTransform> bad{PxTransform(PxVec3(0, 1.5f, 0))};
    std::vector<PxVec3> velocity{PxVec3(std::numeric_limits<float>::quiet_NaN(), 0, 0)};
    require(inspect(bad, velocity).nonfinite == 1, "Nonfinite state was accepted");
}

int main(int argc, char** argv) {
    SetUnhandledExceptionFilter(nativeFailure);
    std::cout << std::unitbuf;
    std::cerr << std::unitbuf;
    try {
        bool gpu = true, tgs = true, driven = true, staggered = true;
        unsigned count = 10000, iterations = 4, warm = 50, samples = 40, trials = 3, hz = 20, threads = 8;
        std::string output = "build/package-physx.csv";
        for (int i = 1; i < argc; ++i) {
            std::string arg = argv[i]; require(i + 1 < argc, "Arguments require values"); std::string value = argv[++i];
            if (arg == "--backend") { require(value == "gpu" || value == "cpu", "Unknown backend"); gpu = value == "gpu"; }
            else if (arg == "--solver") { require(value == "tgs" || value == "pgs", "Unknown solver"); tgs = value == "tgs"; }
            else if (arg == "--count") count = std::stoul(value);
            else if (arg == "--iterations") iterations = std::stoul(value);
            else if (arg == "--warm") warm = std::stoul(value);
            else if (arg == "--samples") samples = std::stoul(value);
            else if (arg == "--trials") trials = std::stoul(value);
            else if (arg == "--hz") hz = std::stoul(value);
            else if (arg == "--threads") threads = std::stoul(value);
            else if (arg == "--output") output = value;
            else if (arg == "--scenario") { require(value == "aligned_still" || value == "staggered_still" || value == "staggered_driven", "Unknown scenario"); driven = value == "staggered_driven"; staggered = value != "aligned_still"; }
            else throw std::runtime_error("Unknown argument: " + arg);
        }
        require(count <= 131072 && iterations > 0 && iterations <= 64 && samples > 0 && warm > 0 && trials > 0 && (hz == 20 || hz == 30 || hz == 60) && threads > 0 && threads <= 64, "Invalid benchmark size or parameters");
        probeReference();
        std::ofstream rows(output), raw(output + ".samples.csv"); require(rows.good() && raw.good(), "Output directory unavailable");
        PhysxRuntime runtime(gpu);
        rows << "backend,solver,count,scenario,hz,iterations,run,step_wall_p50_ms,step_wall_p95_ms,submit_cpu_p50_ms,submit_cpu_p95_ms,drive_wall_p50_ms,drive_wall_p95_ms,fetch_p50_ms,fetch_p95_ms,validation_readback_ms,gpu_heap_bytes,contacts,patches,overlap_max,ground_max,penetrating_pairs,moving,nonfinite,angular_error,quality_pass\n";
        raw << "backend,solver,count,hz,iterations,run,sample,step_wall_ms,submit_cpu_ms,drive_wall_ms,fetch_ms\n";
        std::cout << "PhysX " << PX_PHYSICS_VERSION_MAJOR << '.' << PX_PHYSICS_VERSION_MINOR << '.' << PX_PHYSICS_VERSION_BUGFIX << " native comparison; blocking waits belong to this benchmark worker only\n";
        bool allQuality = true;
        for (unsigned run = 1; run <= trials; ++run) {
            Simulation simulation; auto setup = Clock::now(); simulation.init(count, gpu, tgs, iterations, staggered, threads, driven);
            std::cout << "Setup run " << run << ": " << ms(setup) << " ms\n";
            for (unsigned i = 0; i < warm; ++i) simulation.step(1.f / hz, i, driven);
            std::vector<PxTransform> poses; std::vector<PxVec3> velocities;
            double readback = simulation.read(poses, velocities); auto q = inspect(poses, velocities);
            std::vector<double> wall, submit, fetch, drive;
            for (unsigned i = 0; i < samples; ++i) {
                auto s = simulation.step(1.f / hz, warm + i, driven);
                wall.push_back(s.wall); submit.push_back(s.submit); fetch.push_back(s.fetch); drive.push_back(s.drive);
                raw << (gpu ? "physx_gpu" : "physx_cpu") << ',' << (tgs ? "tgs" : "pgs") << ',' << count << ',' << hz << ',' << iterations << ',' << run << ',' << i << ',' << s.wall << ',' << s.submit << ',' << s.drive << ',' << s.fetch << '\n';
                if ((i + 1) % 8 == 0 || i + 1 == samples) {
                    // Validation is outside timed samples. Keep peaks rather
                    // than accepting a stack that settles after penetrating.
                    readback += simulation.read(poses, velocities);
                    accumulate(q, inspect(poses, velocities));
                }
            }
            PxSimulationStatistics stats; simulation.scene->getSimulationStatistics(stats);
            auto& memory = stats.gpuDynamicsMemoryConfigStatistics;
            bool quality = q.overlap < .002f && q.ground < 1e-4f && q.nonfinite == 0 && q.angular < 1e-6f && (!driven || !count || q.moving > count * .99);
            allQuality &= quality;
            std::string scenario = driven ? "staggered_driven" : staggered ? "staggered_still" : "aligned_still";
            rows << (gpu ? "physx_gpu" : "physx_cpu") << ',' << (tgs ? "tgs" : "pgs") << ',' << count << ',' << scenario << ',' << hz << ',' << iterations << ',' << run << ','
                 << percentile(wall, .5) << ',' << percentile(wall, .95) << ',' << percentile(submit, .5) << ',' << percentile(submit, .95) << ','
                 << percentile(drive, .5) << ',' << percentile(drive, .95) << ',' << percentile(fetch, .5) << ',' << percentile(fetch, .95) << ','
                 << readback << ',' << stats.gpuMemHeap << ',' << memory.rigidContactCount << ',' << memory.rigidPatchCount << ','
                 << q.overlap << ',' << q.ground << ',' << q.pairs << ',' << q.moving << ',' << q.nonfinite << ',' << q.angular << ',' << quality << '\n';
            rows.flush(); raw.flush();
            std::cout << "run=" << run << " p95=" << percentile(wall, .95) << " ms; overlap=" << q.overlap << " ground=" << q.ground << " moving=" << q.moving << " quality=" << quality << '\n';
        }
        return allQuality ? 0 : 2;
    } catch (const std::exception& failure) { std::cerr << "Benchmark failed: " << failure.what() << '\n'; return 1; }
}
