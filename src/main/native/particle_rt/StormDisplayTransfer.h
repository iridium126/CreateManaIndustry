#pragma once
#include "Common.h"
#include "StormStep.h"
#include <array>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <immintrin.h>
#include <mutex>
#include <thread>

// The worker is the sole writer of the persistent GL mappings. A slot remains
// leased until the render thread has consumed the result and retired its GL fence.
class StormDisplayTransfer {
    static constexpr unsigned slotCount=CMI_RESOURCE_RT_SLOTS;
    static constexpr unsigned columns=CMI_RESOURCE_STORM_COLUMN_SLOTS;
    static constexpr size_t columnBytes=CMI_RESOURCE_STORM_COLUMN_MEMBERS*sizeof(StormColumnDisplay);
    static_assert(columns==64);
    enum State { Idle, Pending, Ready, Failed };
    struct Slot {
        CUdeviceptr output{};
        unsigned char* host{};
        unsigned char* mapping{};
        CUevent produced{}, copied{};
        CUevent start{}, headerEnd{}, bodyStart{};
        bool timed{};
        uint64_t sequence{},tick{},identity{};
        long long copyNanos{};
        std::string failure;
        std::atomic<State> state{Idle};
    };
    CUcontext context;
    CUstream stream{};
    unsigned capacity;
    size_t columnBase;
    size_t owned{};
    std::array<Slot,slotCount> slots;
    std::mutex mutex;
    std::condition_variable available;
    std::deque<unsigned> jobs;
    bool closing{};
    std::thread worker;

    void copy(Slot& slot) {
        checked(cuStreamWaitEvent(stream,slot.produced,0));
        if(slot.timed)checked(cuEventRecord(slot.start,stream));
        checked(cuMemcpyDtoHAsync(slot.host,slot.output,sizeof(StormDisplayHeader),stream));
        checked(cuEventRecord(slot.timed?slot.headerEnd:slot.copied,stream));
        checked(cuEventSynchronize(slot.timed?slot.headerEnd:slot.copied));
        const auto& header=*reinterpret_cast<const StormDisplayHeader*>(slot.host);
        if(header.magic!=CMI_RESOURCE_STORM_DISPLAY_MAGIC||header.abi!=CMI_RESOURCE_ABI
                ||header.ready!=1||header.capacity!=capacity||header.count>capacity
                ||header.sequence!=slot.sequence||header.tick!=slot.tick||header.identity!=slot.identity)
            throw std::runtime_error("Invalid staged Storm display header");
        const size_t memberBytes=size_t(header.count)*sizeof(StormDisplayRecord);
        const uint64_t mask=uint64_t(header.columnMask[0])|(uint64_t(header.columnMask[1])<<32);
        std::array<std::pair<size_t,size_t>,columns/2> ranges{};
        unsigned rangeCount=0;
        for(unsigned column=0;column<columns;){
            if(!(mask&(uint64_t(1)<<column))){++column;continue;}
            unsigned first=column++;
            while(column<columns&&(mask&(uint64_t(1)<<column)))++column;
            ranges[rangeCount++]={columnBase+first*columnBytes,(column-first)*columnBytes};
        }
        if(slot.timed)checked(cuEventRecord(slot.bodyStart,stream));
        if(memberBytes)checked(cuMemcpyDtoHAsync(slot.host+sizeof(header),slot.output+sizeof(header),memberBytes,stream));
        for(unsigned i=0;i<rangeCount;++i){
            auto [offset,bytes]=ranges[i];
            checked(cuMemcpyDtoHAsync(slot.host+offset,slot.output+offset,bytes,stream));
        }
        checked(cuEventRecord(slot.copied,stream));
        checked(cuEventSynchronize(slot.copied));
        if(slot.timed){
            float head{},body{};
            checked(cuEventElapsedTime(&head,slot.start,slot.headerEnd));
            checked(cuEventElapsedTime(&body,slot.bodyStart,slot.copied));
            slot.copyNanos=static_cast<long long>((head+body)*1e6);
        }
        std::memcpy(slot.mapping,slot.host,sizeof(header)+memberBytes);
        for(unsigned i=0;i<rangeCount;++i){
            auto [offset,bytes]=ranges[i];
            std::memcpy(slot.mapping+offset,slot.host+offset,bytes);
        }
        _mm_sfence(); // The driver may expose a write-combined upload mapping.
    }

    void work() {
        for(;;){
            unsigned index;
            {
                std::unique_lock<std::mutex> lock(mutex);
                available.wait(lock,[&]{return closing||!jobs.empty();});
                if(closing&&jobs.empty())return;
                index=jobs.front();jobs.pop_front();
            }
            auto& slot=slots[index];
            try{
                checked(cuCtxSetCurrent(context));
                copy(slot);
                slot.state=Ready;
            }catch(const std::exception& failure){
                cuStreamSynchronize(stream);
                slot.failure=failure.what();
                slot.state=Failed;
            }
        }
    }

    void release() {
        if(worker.joinable()){
            {std::lock_guard<std::mutex> lock(mutex);closing=true;}
            available.notify_one();
            worker.join();
        }
        for(auto& slot:slots){
            for(auto event:{slot.produced,slot.copied,slot.start,slot.headerEnd,slot.bodyStart})
                if(event)cuEventDestroy(event);
            if(slot.host)cuMemFreeHost(slot.host);
            if(slot.output)cuMemFree(slot.output);
        }
        particleRtOwnedBytes-=owned;
        if(stream)cuStreamDestroy(stream);
    }

public:
    StormDisplayTransfer(CUcontext owner,unsigned cap,const std::array<unsigned char*,slotCount>& mappings):
        context(owner),capacity(cap),columnBase(sizeof(StormDisplayHeader)+cap*sizeof(StormDisplayRecord)) {
        try{
            checked(cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));
            size_t bytes=columnBase+columns*columnBytes;
            for(unsigned i=0;i<slotCount;++i){
                auto& slot=slots[i];
                slot.mapping=mappings[i];
                reserveBytes(bytes);owned+=bytes;
                checked(cuMemAlloc(&slot.output,bytes));
                checked(cuMemAllocHost(reinterpret_cast<void**>(&slot.host),bytes));
                checked(cuEventCreate(&slot.produced,CU_EVENT_DISABLE_TIMING));
                checked(cuEventCreate(&slot.copied,CU_EVENT_DEFAULT));
                checked(cuEventCreate(&slot.start,CU_EVENT_DEFAULT));
                checked(cuEventCreate(&slot.headerEnd,CU_EVENT_DEFAULT));
                checked(cuEventCreate(&slot.bodyStart,CU_EVENT_DEFAULT));
            }
            worker=std::thread([this]{work();});
        }catch(...){release();throw;}
    }
    ~StormDisplayTransfer(){release();}
    void reserve(unsigned index,const StormTick& tick,bool timed){
        auto& slot=slots[index];
        slot.sequence=tick.sequence;slot.tick=tick.tick;slot.identity=tick.identity;
        slot.timed=timed;slot.copyNanos=0;slot.failure.clear();slot.state=Pending;
    }
    CUdeviceptr output(unsigned index) const{return slots[index].output;}
    void seal(unsigned index,CUstream producer){checked(cuEventRecord(slots[index].produced,producer));}
    void enqueue(unsigned index){
        {std::lock_guard<std::mutex> lock(mutex);jobs.push_back(index);}
        available.notify_one();
    }
    void cancel(unsigned index){slots[index].state=Failed;}
    bool pending(unsigned index) const{return slots[index].state==Pending;}
    bool drained() const{return cuStreamQuery(stream)==CUDA_SUCCESS;}
    long long copyTime(unsigned index,jlongArray metadata,JNIEnv* env) const {
        const auto& slot=slots[index];
        auto state=slot.state.load();
        if(state==Failed)throw std::runtime_error("Storm display transfer failed: "+slot.failure);
        if(state!=Ready)return -1;
        const auto& header=*reinterpret_cast<const StormDisplayHeader*>(slot.host);
        jlong values[]={header.count,static_cast<jlong>(uint64_t(header.columnMask[0])|(uint64_t(header.columnMask[1])<<32))};
        env->SetLongArrayRegion(metadata,0,2,values);
        return slot.copyNanos;
    }
};
