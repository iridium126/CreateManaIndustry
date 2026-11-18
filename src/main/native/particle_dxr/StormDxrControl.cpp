#include "StormDxrGpu.h"
#include "StormInput.h"
#include "StormDxrVersion.h"
#include <jni.h>
#include <gl/GL.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <thread>

#define STORM_JNI(name) Java_com_iridium126_createmanaindustry_client_particles_engine_spatial_StormDxrNative_##name

namespace stormDxr {
namespace {
using namespace stormInput;
constexpr int pending=1, complete=2, failed=4;

struct Tick {
    StormTick input{};
    std::atomic<int> state{};
    int display=-1;
    bool timed{};
    TickResult result;
    std::vector<unsigned char> payload;
    std::shared_ptr<const std::vector<SpatialParticle>> geometry;
    std::unique_ptr<DomeUpload> dome;
    unsigned delay{};
    std::string error;
};
struct TransferJob {
    unsigned slot;
    StormTick tick;
    bool timed;
};

class Session {
    unsigned capacity, probes;
    std::string id;
    uint64_t renderLuid;
    bool shared;
    std::wstring model;
    std::atomic<bool> initialized{};
    std::array<Tick, slots> ticks;
    std::array<DisplayTransfer, slots> displays;
    std::shared_ptr<const std::vector<SpatialParticle>> geometry;
    std::unique_ptr<DomeUpload> dome;
    bool domeStaged{}, active{}, stopped{};
    uint64_t submitted{}, consumed{}, tick{}, identity{};
    unsigned delay{};
    std::string error;

    std::mutex mutex;
    std::condition_variable computeReady, copyReady;
    std::deque<TransferJob> transfers;
    uint64_t queuedThrough{};
    bool closing{};
    std::atomic<unsigned> workersDone{};
    std::thread computeWorker, copyWorker;
    std::unique_ptr<Gpu> gpu;

    void run() {
        std::string failure;
        for (uint64_t next=1;;++next) {
            {
                std::unique_lock<std::mutex> lock(mutex);
                computeReady.wait(lock,[&]{return closing||next<=queuedThrough;});
                if (closing) break;
            }
            auto& job=ticks[(next-1)%slots];
            auto display=job.display<0?nullptr:&displays[job.display];
            try {
                if (!failure.empty()) throw std::runtime_error(failure);
                if (!gpu) {
                    auto devices=adapters(false);
                    auto selected=std::find_if(devices.begin(),devices.end(),[&](const Adapter& adapter){return adapter.id==id;});
                    if (selected==devices.end()) throw std::runtime_error("Selected Storm DXR device is unavailable: "+id);
                    const auto luid = uint64_t(uint32_t(selected->luid.HighPart)) << 32 | selected->luid.LowPart;
                    if (renderLuid && (luid == renderLuid) != shared)
                        throw std::runtime_error("Storm DXR display adapter changed");
                    gpu=std::make_unique<Gpu>(*selected,capacity,probes,displays,shared);
                    model=selected->model;
                    initialized=true;
                }
                if (job.delay) std::this_thread::sleep_for(std::chrono::milliseconds(job.delay));
                validatePayload(job.input,job.payload.data(),capacity);
                gpu->step(job.input,job.payload.data(),job.geometry,job.dome.get(),display,job.timed,job.result);
                if (display && shared) display->state=DisplayTransfer::Ready;
                else if (display) {
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        transfers.push_back({unsigned(job.display),job.input,job.timed});
                        display->state=DisplayTransfer::HeaderReady;
                    }
                    copyReady.notify_one();
                }
                job.state=complete;
            } catch (const std::exception& cause) {
                failure=cause.what();
                job.error="DXR Storm tick "+std::to_string(job.input.sequence)+": "+failure;
                if (display) display->state=DisplayTransfer::Failed;
                job.state=failed;
            }
        }
        ++workersDone;
    }
    void copy() {
        for (;;) {
            TransferJob job{};
            {
                std::unique_lock<std::mutex> lock(mutex);
                copyReady.wait(lock,[&]{return closing||!transfers.empty();});
                if (closing&&transfers.empty()) break;
                job=transfers.front();
                transfers.pop_front();
            }
            auto& display=displays[job.slot];
            display.state=DisplayTransfer::Copying;
            try {
                gpu->copyDisplay(display,job.tick,job.timed);
                display.state=DisplayTransfer::Ready;
            } catch (const std::exception& cause) {
                display.error=cause.what();
                display.state=DisplayTransfer::Failed;
            }
        }
        ++workersDone;
    }
    void stopWorkers() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            closing=true;
        }
        computeReady.notify_one();
        copyReady.notify_one();
    }

public:
    Session(unsigned capacity,unsigned probes,std::string id,const std::array<unsigned char*,slots>& maps,
            uint64_t renderLuid, unsigned renderNodes)
            : capacity(capacity),probes(probes),id(std::move(id)),renderLuid(renderLuid),shared(!maps[0]) {
        if (shared && (!renderLuid || renderNodes != 1))
            throw std::runtime_error("Storm DXR sharing requires OpenGL on D3D12 node 0");
        if (!capacity || capacity>131072 || (probes!=2&&probes!=4))
            throw std::runtime_error("Invalid DXR Storm capacity or probe count");
        for (unsigned slot=0;slot<slots;++slot) displays[slot].mapping=maps[slot];
        try {
            computeWorker=std::thread(&Session::run,this);
            if (!shared) copyWorker=std::thread(&Session::copy,this);
        } catch (...) {
            stopWorkers();
            if (computeWorker.joinable()) computeWorker.join();
            throw;
        }
    }
    ~Session() {
        if (computeWorker.joinable()) computeWorker.join();
        if (copyWorker.joinable()) copyWorker.join();
    }

    void installTerrain(const void* rows,unsigned count,size_t bytes) {
        if (count>CMI_RESOURCE_STORM_SPATIAL_TERRAIN_LIMIT||bytes<size_t(count)*sizeof(SpatialParticle)||(!rows&&count))
            throw std::runtime_error("Invalid DXR Storm terrain geometry range");
        auto snapshot=std::make_shared<std::vector<SpatialParticle>>(count);
        if (count) std::memcpy(snapshot->data(),rows,size_t(count)*sizeof(SpatialParticle));
        for (const auto& row : *snapshot) {
            if (!targetPointValid(row.position) || !vectorFinite(row.velocity)
                    || row.velocity.x<=0 || row.velocity.x>8 || row.velocity.y<=0 || row.velocity.y>8
                    || row.velocity.z<=0 || row.velocity.z>8 || !(row.identity.w&3u) || (row.identity.w&~3u))
                throw std::runtime_error("Invalid DXR Storm terrain geometry");
        }
        snapshot->erase(std::remove_if(snapshot->begin(),snapshot->end(),[](const SpatialParticle& row) {
            return (row.identity.w&1u)==0;
        }),snapshot->end());
        geometry=std::move(snapshot);
    }

    void installDome(JNIEnv* env,jfloatArray records,jintArray keys,jintArray flows) {
        if (stopped||domeStaged||!records||!keys||!flows) throw std::runtime_error("Invalid DXR Dome template installation");
        unsigned recordCount=env->GetArrayLength(records),keyCount=env->GetArrayLength(keys),flowCount=env->GetArrayLength(flows);
        if (recordCount!=domeMembers*CMI_RESOURCE_STORM_DOME_RECORD_WORDS || flowCount!=domeMembers
                || keyCount<domeMembers*CMI_RESOURCE_STORM_DOME_NODE_WORDS || keyCount>67108864)
            throw std::runtime_error("Invalid DXR Dome template layout");
        auto upload=std::make_unique<DomeUpload>();
        upload->records.resize(recordCount);upload->keys.resize(keyCount);upload->flows.resize(flowCount);
        env->GetFloatArrayRegion(records,0,recordCount,upload->records.data());
        env->GetIntArrayRegion(keys,0,keyCount,reinterpret_cast<jint*>(upload->keys.data()));
        env->GetIntArrayRegion(flows,0,flowCount,reinterpret_cast<jint*>(upload->flows.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("DXR Dome template copy failed");
        auto validSpan=[keyCount](float first,float count) {
            return std::isfinite(first)&&std::isfinite(count)&&first>=0.f&&count>=2.f&&count<=4096.f
                    &&(double(first)+double(count))*CMI_RESOURCE_STORM_DOME_NODE_WORDS<=keyCount;
        };
        for (unsigned rank=0;rank<domeMembers;++rank) {
            auto row=upload->records.data()+rank*CMI_RESOURCE_STORM_DOME_RECORD_WORDS;
            for (unsigned i=0;i<CMI_RESOURCE_STORM_DOME_RECORD_WORDS;++i)
                if (!std::isfinite(row[i])) throw std::runtime_error("Nonfinite DXR Dome template record");
            if (upload->flows[rank]>=domeFlows||row[3]<0||row[3]>rank||row[7]<12||row[3]+row[7]>domeMembers
                    ||row[10]<=0.f||!validSpan(row[0],row[1])||!validSpan(row[8],row[12]))
                throw std::runtime_error("Invalid DXR Dome template record");
        }
        dome=std::move(upload);
        domeStaged=true;
    }

    bool prepareDisplay(unsigned slot) const {
        return !stopped&&displays.at(slot).state==DisplayTransfer::Idle;
    }

    bool submit(const void* source,size_t bytes,int slot,bool timed) {
        if (stopped||submitted-consumed==slots) return false;
        if (!source||bytes<sizeof(StormTick)) throw std::runtime_error("DXR Storm input buffer too small");
        StormTick input;
        std::memcpy(&input,source,sizeof(input));
        validateHeader(input,bytes,capacity,submitted,tick,identity,active,domeStaged);
        if (slot < -1||slot>=int(slots)||slot>=0&&!prepareDisplay(slot))
            throw std::runtime_error("DXR Storm display slot still has an owner");
        auto& job=ticks[submitted%slots];
        auto payload=static_cast<const unsigned char*>(source)+sizeof(input);
        job.payload.assign(payload,static_cast<const unsigned char*>(source)+bytes);
        job.timed=timed;
        job.input=input;
        job.display=slot;
        job.geometry=geometry;
        job.dome=std::move(dome);
        job.delay=delay;delay=0;
        job.error.clear();
        job.state=pending;
        if (slot>=0) {
            auto& display=displays[slot];
            display.sequence=input.sequence;
            display.error.clear();
            display.state=DisplayTransfer::Computing;
        }
        submitted=input.sequence;tick=input.tick;identity=input.identity;active=(input.active&1u)!=0;
        {
            std::lock_guard<std::mutex> lock(mutex);
            queuedThrough=submitted;
        }
        computeReady.notify_one();
        return true;
    }

    int poll(uint64_t sequence) {
        if (sequence!=consumed+1||sequence>submitted) throw std::runtime_error("DXR Storm receipt is not the oldest input");
        auto& job=ticks[consumed%slots];
        int state=job.state;
        if (state==failed) {
            stopped=true;
            if (error.empty()) error=job.error;
        }
        return state;
    }
    int consume(uint64_t sequence) {
        int status=poll(sequence);
        if (status==pending) throw std::runtime_error("DXR Storm receipt still pending");
        auto& job=ticks[consumed%slots];
        job.geometry.reset();
        job.dome.reset();
        job.state=0;
        ++consumed;
        return status;
    }
    const Tick& completed(uint64_t sequence) {
        if (poll(sequence)!=complete) throw std::runtime_error("DXR Storm receipt is not ready");
        return ticks[consumed%slots];
    }
    bool copyBatch(uint64_t sequence,void* destination,size_t bytes) {
        const auto& job=completed(sequence);
        if (!job.result.hasBatch) return false;
        if (!destination||bytes<sizeof(StormDomeBatch)) throw std::runtime_error("DXR Storm batch buffer too small");
        std::memcpy(destination,&job.result.batch,batchBytes(job.input));
        return true;
    }
    unsigned copyFeathers(uint64_t sequence,void* destination,size_t bytes) {
        const auto& result=completed(sequence).result.feathers;
        if (result.empty()) return 0;
        const size_t size=result.size()*sizeof(float4);
        if (!destination||bytes<size) throw std::runtime_error("DXR Storm feather buffer too small");
        std::memcpy(destination,result.data(),size);
        return unsigned(result.size());
    }
    int64_t displayTime(unsigned slot,uint64_t sequence,jlongArray metadata,JNIEnv* env) {
        const auto& display=displays.at(slot);
        if (shared||display.sequence!=sequence||!metadata||env->GetArrayLength(metadata)<2)
            throw std::runtime_error("Invalid DXR Storm display request");
        auto state=display.state.load();
        if (state==DisplayTransfer::Failed) throw std::runtime_error(display.error.empty()?error:display.error);
        if (state!=DisplayTransfer::Ready) return -1;
        const auto& header=display.header;
        const uint64_t columns=uint64_t(header.columnMask[0])|(uint64_t(header.columnMask[1])<<32u);
        jlong values[]{jlong(header.count),jlong(columns)};
        env->SetLongArrayRegion(metadata,0,2,values);
        return display.copyNanos;
    }
    bool recycle(unsigned slot,uint64_t sequence,void* fence) {
        auto& display=displays.at(slot);
        if (display.sequence!=sequence||(!shared&&!fence)) throw std::runtime_error("Invalid DXR Storm display retirement");
        auto state=display.state.load();
        if (sequence>consumed || (state!=DisplayTransfer::Ready&&state!=DisplayTransfer::Failed)) return false;
        for (const auto& earlier : displays) if (earlier.sequence&&earlier.sequence<sequence) return false;
        if (shared) {
            if (fence) {
                const auto completed=display.sharedFence->GetCompletedValue();
                if (completed!=UINT64_MAX && completed<sequence*2) return false;
            }
            display.sequence=0;
            display.state=DisplayTransfer::Idle;
            return true;
        }
        using Wait=unsigned (APIENTRY*)(void*,unsigned,unsigned long long);
        auto wait=reinterpret_cast<Wait>(wglGetProcAddress("glClientWaitSync"));
        if (!wait) throw std::runtime_error("OpenGL Storm retirement is unavailable");
        unsigned status=wait(fence,1u,0);
        if (status==0x911Bu) return false;
        if (status!=0x911Au&&status!=0x911Cu) throw std::runtime_error("DXR Storm OpenGL retirement fence failed");
        display.sequence=0;
        display.state=DisplayTransfer::Idle;
        return true;
    }
    jlongArray sharedHandles(JNIEnv* env, unsigned slot) const {
        const auto& display=displays.at(slot);
        if (!shared || display.state!=DisplayTransfer::Ready)
            throw std::runtime_error("Storm DXR display is not ready for import");
        ComPtr<ID3D12Device> device;
        check(display.snapshot.resource->GetDevice(IID_PPV_ARGS(&device)), "Reading Storm shared buffer device");
        const auto desc=display.snapshot.resource->GetDesc();
        const auto allocation=device->GetResourceAllocationInfo(1,1,&desc);
        jlongArray result=env->NewLongArray(4);
        if (!result) return nullptr;

        HANDLE resource{}, fence{};
        check(device->CreateSharedHandle(display.snapshot.resource.Get(),nullptr,GENERIC_ALL,nullptr,&resource),
                "Exporting Storm shared buffer");
        try {
            check(device->CreateSharedHandle(display.sharedFence.Get(),nullptr,GENERIC_ALL,nullptr,&fence),
                    "Exporting Storm shared fence");
        } catch (...) {
            CloseHandle(resource);
            throw;
        }
        const jlong values[]{reinterpret_cast<jlong>(resource),reinterpret_cast<jlong>(fence),
                jlong(allocation.SizeInBytes),jlong(display.snapshot.size)};
        env->SetLongArrayRegion(result,0,4,values);
        if (env->ExceptionCheck()) {
            CloseHandle(resource);
            CloseHandle(fence);
            return nullptr;
        }
        return result;
    }
    void delayNext(unsigned milliseconds) {
        if (milliseconds>500) throw std::runtime_error("Storm test delay must be within 0..500 ms");
        delay=milliseconds;
    }
    const std::string& diagnostics() const { return error; }
    const std::wstring* gpuModel() const { return initialized ? &model : nullptr; }
    bool close() {
        if (consumed!=submitted) return false;
        for (const auto& display : displays) if (display.state!=DisplayTransfer::Idle) return false;
        stopWorkers();
        return workersDone==(shared?1:2);
    }
};

Session& session(jlong handle) {
    if (!handle) throw std::runtime_error("Missing DXR Storm session");
    return *reinterpret_cast<Session*>(handle);
}
size_t size(JNIEnv* env,jobject buffer) {
    return size_t(std::max<jlong>(0,env->GetDirectBufferCapacity(buffer)));
}
void fail(JNIEnv* env,const std::exception& cause) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),cause.what());
}
}
}

using namespace stormDxr;
extern "C" {
JNIEXPORT jstring JNICALL STORM_JNI(version)(JNIEnv* env,jclass) {
    return env->NewStringUTF(CMI_DXR_VERSION);
}
JNIEXPORT jint JNICALL STORM_JNI(abi)(JNIEnv*,jclass) {
    return CMI_RESOURCE_ABI;
}
JNIEXPORT jstring JNICALL STORM_JNI(gpuModel)(JNIEnv* env,jclass,jlong handle) {
    try {
        auto model=session(handle).gpuModel();
        return model ? env->NewString(reinterpret_cast<const jchar*>(model->data()),jsize(model->size())) : nullptr;
    } catch (const std::exception& cause) { fail(env,cause);return nullptr; }
}
JNIEXPORT jobjectArray JNICALL STORM_JNI(devices)(JNIEnv* env,jclass,jboolean probe) {
    try {
        auto devices=adapters(probe);
        jobjectArray result=env->NewObjectArray(jsize(devices.size()*5),env->FindClass("java/lang/String"),nullptr);
        auto set=[&](jsize index,jstring value) {
            env->SetObjectArrayElement(result,index,value);
            env->DeleteLocalRef(value);
        };
        for (jsize i=0;i<jsize(devices.size());++i) {
            const auto& device=devices[i];
            set(i*5,env->NewStringUTF(device.id.c_str()));
            set(i*5+1,env->NewString(reinterpret_cast<const jchar*>(device.model.data()),jsize(device.model.size())));
            set(i*5+2,env->NewStringUTF(device.pci.c_str()));
            set(i*5+3,env->NewStringUTF(adapterLuid(device.luid).c_str()));
            set(i*5+4,env->NewStringUTF(device.dxr ? "true" : "false"));
        }
        return result;
    } catch (const std::exception& cause) { fail(env,cause);return nullptr; }
}
JNIEXPORT jlong JNICALL STORM_JNI(create)(JNIEnv* env,jclass,jint capacity,jstring gpu,jobjectArray mappings,jint probes,jlong luid,jint nodeMask) {
    try {
        if (!gpu||(mappings&&env->GetArrayLength(mappings)!=slots)||capacity<1||capacity>131072)
            throw std::runtime_error("Invalid DXR Storm display layout");
        const size_t bytes=sizeof(StormDisplayHeader)+size_t(capacity)*sizeof(StormDisplayRecord)+columnSlots*columnBytes;
        std::array<unsigned char*,slots> addresses{};
        for (unsigned index=0;mappings&&index<slots;++index) {
            auto map=env->GetObjectArrayElement(mappings,index);
            auto length=size(env,map);
            addresses[index]=static_cast<unsigned char*>(env->GetDirectBufferAddress(map));
            env->DeleteLocalRef(map);
            if (!addresses[index]||length<bytes) throw std::runtime_error("Missing DXR Storm persistent GL mapping");
        }
        auto chars=env->GetStringUTFChars(gpu,nullptr);
        if (!chars) return 0;
        std::string id(chars);
        env->ReleaseStringUTFChars(gpu,chars);
        return reinterpret_cast<jlong>(new Session(capacity,probes,std::move(id),addresses,uint64_t(luid),unsigned(nodeMask)));
    } catch (const std::exception& cause) { fail(env,cause);return 0; }
}
JNIEXPORT jlongArray JNICALL STORM_JNI(sharedDisplayHandles)(JNIEnv* env,jclass,jlong worker,jint slot) {
    try { return session(worker).sharedHandles(env,slot); }
    catch (const std::exception& cause) { fail(env,cause);return nullptr; }
}
JNIEXPORT void JNICALL STORM_JNI(closeSharedDisplayHandles)(JNIEnv*,jclass,jlong resource,jlong fence) {
    CloseHandle(reinterpret_cast<HANDLE>(resource));
    CloseHandle(reinterpret_cast<HANDLE>(fence));
}
JNIEXPORT void JNICALL STORM_JNI(installStormTerrain)(JNIEnv* env,jclass,jlong handle,jlong,jobject rows,jint count) {
    try { session(handle).installTerrain(env->GetDirectBufferAddress(rows),unsigned(count),size(env,rows)); }
    catch (const std::exception& cause) { fail(env,cause); }
}
JNIEXPORT void JNICALL STORM_JNI(installStormDome)(JNIEnv* env,jclass,jlong handle,jfloatArray records,jintArray keys,jintArray flows) {
    try { session(handle).installDome(env,records,keys,flows); }
    catch (const std::exception& cause) { fail(env,cause); }
}
JNIEXPORT jboolean JNICALL STORM_JNI(submitStormTick)(JNIEnv* env,jclass,jlong handle,jobject packet,jint bytes,jint slot,jboolean timed) {
    try {
        if (bytes<0||size(env,packet)<size_t(bytes)) throw std::runtime_error("Invalid DXR Storm tick buffer length");
        return session(handle).submit(env->GetDirectBufferAddress(packet),bytes,slot,timed);
    } catch (const std::exception& cause) { fail(env,cause);return false; }
}
JNIEXPORT jboolean JNICALL STORM_JNI(prepareStormDisplaySlot)(JNIEnv* env,jclass,jlong handle,jint slot) {
    try { return session(handle).prepareDisplay(slot); }
    catch (const std::exception& cause) { fail(env,cause);return false; }
}
JNIEXPORT jint JNICALL STORM_JNI(pollStormTick)(JNIEnv* env,jclass,jlong handle,jlong sequence) {
    try { return session(handle).poll(sequence); }
    catch (const std::exception& cause) { fail(env,cause);return failed; }
}
JNIEXPORT jint JNICALL STORM_JNI(consumeStormTick)(JNIEnv* env,jclass,jlong handle,jlong sequence) {
    try { return session(handle).consume(sequence); }
    catch (const std::exception& cause) { fail(env,cause);return failed; }
}
JNIEXPORT jboolean JNICALL STORM_JNI(copyStormDomeBatch)(JNIEnv* env,jclass,jlong handle,jlong sequence,jobject output) {
    try { return session(handle).copyBatch(sequence,env->GetDirectBufferAddress(output),size(env,output)); }
    catch (const std::exception& cause) { fail(env,cause);return false; }
}
JNIEXPORT jint JNICALL STORM_JNI(copyStormFeatherImpacts)(JNIEnv* env,jclass,jlong handle,jlong sequence,jobject output) {
    try { return session(handle).copyFeathers(sequence,env->GetDirectBufferAddress(output),size(env,output)); }
    catch (const std::exception& cause) { fail(env,cause);return 0; }
}
JNIEXPORT jlong JNICALL STORM_JNI(stagedStormDisplayTime)(JNIEnv* env,jclass,jlong handle,jint slot,jlong sequence,jlongArray metadata) {
    try { return session(handle).displayTime(slot,sequence,metadata,env); }
    catch (const std::exception& cause) { fail(env,cause);return -1; }
}
JNIEXPORT jboolean JNICALL STORM_JNI(recycleStormDisplay)(JNIEnv* env,jclass,jlong handle,jint slot,jlong sequence,jlong fence) {
    try { return session(handle).recycle(slot,sequence,reinterpret_cast<void*>(fence)); }
    catch (const std::exception& cause) { fail(env,cause);return false; }
}
JNIEXPORT jdoubleArray JNICALL STORM_JNI(stormTickTiming)(JNIEnv* env,jclass,jlong handle,jlong sequence) {
    try {
        const auto& job=session(handle).completed(sequence);
        if (!job.timed) throw std::runtime_error("DXR Storm tick timing was not requested");
        auto values=env->NewDoubleArray(jsize(job.result.timing.size()));
        env->SetDoubleArrayRegion(values,0,jsize(job.result.timing.size()),job.result.timing.data());
        return values;
    } catch (const std::exception& cause) { fail(env,cause);return nullptr; }
}
JNIEXPORT void JNICALL STORM_JNI(delayStormControl)(JNIEnv* env,jclass,jlong handle,jint milliseconds) {
    try { session(handle).delayNext(milliseconds); }
    catch (const std::exception& cause) { fail(env,cause); }
}
JNIEXPORT jstring JNICALL STORM_JNI(stormControlDiagnostics)(JNIEnv* env,jclass,jlong handle) {
    try { return env->NewStringUTF(session(handle).diagnostics().c_str()); }
    catch (const std::exception& cause) { fail(env,cause);return nullptr; }
}
JNIEXPORT jboolean JNICALL STORM_JNI(closeStormControl)(JNIEnv* env,jclass,jlong handle) {
    try {
        auto& worker=session(handle);
        if (!worker.close()) return false;
        delete &worker;
        return true;
    } catch (const std::exception& cause) { fail(env,cause);return false; }
}
}
