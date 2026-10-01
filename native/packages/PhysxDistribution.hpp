#pragma once
#include <ovphysx/ovphysx.h>
#include <ovphysx/ovphysx_config.h>
#include <ovphysx/population/Population.hpp>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

// Only public virtual interfaces cross this boundary. Linking a separately
// built PhysX host SDK to the precompiled GPU DLL is not ABI-safe.
static void ovCheck(ovphysx_result_t result, const char* operation) {
    if (result.status != OVPHYSX_API_SUCCESS) {
        auto error = ovphysx_get_last_error();
        throw std::runtime_error(std::string(operation) + ": " +
            (error.ptr ? std::string(error.ptr, error.length) : std::to_string(result.status)));
    }
}

struct PhysxRuntime {
    explicit PhysxRuntime(bool gpu) {
        ovCheck(ovphysx_initialize(), "Runtime initialize");
        if (!gpu) ovCheck(ovphysx_set_cpu_mode(true), "CPU-only mode");
    }
    ~PhysxRuntime() { ovphysx_shutdown(); }
};

struct PhysxDistribution {
    ovphysx_handle_t handle = 0;
    ovstage_instance_t* stage = nullptr;
    physx::PxPhysics* physics = nullptr; // borrowed
    physx::PxCudaContextManager* manager = nullptr; // borrowed
    void init(bool gpu) {
        ovphysx_create_args args = OVPHYSX_CREATE_ARGS_DEFAULT;
        auto config = ovphysx_config_entry_carbonite(OVPHYSX_LITERAL("/physics/suppressReadback"), OVPHYSX_LITERAL("true"));
        if (gpu) { args.config_entries = &config; args.config_entry_count = 1; }
        ovCheck(ovphysx_create_instance(&args, &handle), "Runtime instance");
        ovstage_instance_desc_t desc{}; desc.name = "CMI benchmark factory bootstrap";
        if (ovstage_create_instance(&desc, &stage) != OVSTAGE_OK) throw std::runtime_error("Stage create failed");
        // Empty bootstrap only. The timed scene is created directly through
        // PxPhysics; no USD or population work occurs inside measured steps.
        using namespace ovphysx::population;
        PhysicsSceneArgs gravity;
        gravity.gravityDirection = std::vector<std::array<float, 3>>{{0, -1, 0}};
        gravity.gravityMagnitude = std::vector<float>{32};
        PhysxSceneAPIArgs flags;
        flags.enableGpudynamics = std::vector<uint8_t>{uint8_t(gpu)};
        flags.disableSleeping = std::vector<uint8_t>{1};
        flags.broadphaseType = std::vector<std::string>{gpu ? "GPU" : "MBP"};
        createPhysicsScenes(stage, {"/World/Factory"}, 1, gravity, flags);
        ovstage_write_floor_desc_t floor{}; floor.ordinal = 1; floor.scope = OVSTAGE_SCOPE_ALL;
        detail::waitOp(stage, ovstage_advance_write_floor(stage, &floor), "Bootstrap floor");
        ovCheck(ovphysx_attach_ovstage(handle, stage, 1), "Attach bootstrap");
        ovCheck(ovphysx_step_sync(handle, 1.f / 20), "Initialize bootstrap");
        void* pointer = nullptr;
        ovCheck(ovphysx_get_physx_ptr(handle, {nullptr, 0}, OVPHYSX_PHYSX_TYPE_PHYSICS, &pointer), "Physics factory");
        physics = static_cast<physx::PxPhysics*>(pointer);
        if (gpu) {
            ovCheck(ovphysx_get_physx_ptr(handle, OVPHYSX_LITERAL("/World/Factory"), OVPHYSX_PHYSX_TYPE_SCENE, &pointer), "Bootstrap scene");
            manager = static_cast<physx::PxScene*>(pointer)->getCudaContextManager();
            if (!manager || !manager->contextIsValid()) throw std::runtime_error("GPU bootstrap unavailable");
        }
    }
    ~PhysxDistribution() {
        if (handle) {
            // Keep caller-owned stage alive until instance destruction, even
            // when detachment fails; borrowed PhysX pointers are never released.
            ovphysx_detach_ovstage(handle); ovphysx_destroy_instance(handle);
        }
        if (stage) ovstage_destroy_instance(stage);
    }
};

// Standalone benchmark pool; no second SDK/foundation via PhysXExtensions.
struct WorkerDispatcher final : physx::PxCpuDispatcher {
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<physx::PxBaseTask*> queue;
    std::vector<std::thread> threads;
    bool stopping = false;
    explicit WorkerDispatcher(unsigned count) {
        for (unsigned i = 0; i < count; ++i) threads.emplace_back([this] {
            for (;;) {
                physx::PxBaseTask* task;
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    wake.wait(lock, [this] { return stopping || !queue.empty(); });
                    if (stopping && queue.empty()) return;
                    task = queue.front(); queue.pop_front();
                }
                task->run(); task->release();
            }
        });
    }
    void submitTask(physx::PxBaseTask& task) override {
        { std::lock_guard<std::mutex> lock(mutex); queue.push_back(&task); }
        wake.notify_one();
    }
    uint32_t getWorkerCount() const override { return uint32_t(threads.size()); }
    ~WorkerDispatcher() {
        { std::lock_guard<std::mutex> lock(mutex); stopping = true; }
        wake.notify_all(); for (auto& thread : threads) thread.join();
    }
};

static physx::PxFilterFlags packageFilter(physx::PxFilterObjectAttributes, physx::PxFilterData,
    physx::PxFilterObjectAttributes, physx::PxFilterData, physx::PxPairFlags& pair,
    const void*, physx::PxU32) {
    pair = physx::PxPairFlag::eCONTACT_DEFAULT;
    return physx::PxFilterFlag::eDEFAULT;
}
