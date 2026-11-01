#include "Common.h"
#include "AsyncApi.h"
#include <process.h>
#include <array>
#include <memory>
#include <chrono>
#include <algorithm>

namespace {
struct Guard {SRWLOCK* lock;explicit Guard(SRWLOCK& value):lock(&value){AcquireSRWLockExclusive(lock);}~Guard(){ReleaseSRWLockExclusive(lock);}};
enum State {Free,Pending,Ready,Fallback,Quarantined,Consumed,Preparing};
struct Job {State state=Free;BridgeTicket ticket;uint64_t nonce{};int backend{};bool refit{};std::array<double,7> timing{};std::array<jlong,7> stats{{-1,-1,-1,-1,-1,-1,-1}};std::string error;};
struct Session {
    SRWLOCK lock=SRWLOCK_INIT;std::array<Job,3> jobs;void* bridge{};void* query{};
    HANDLE notify{},completed{},thread{};CUcontext context{};CUevent readDone{};unsigned* header{};
    unsigned nCapacity{},qCapacity{},allowedMask=31;uint64_t epoch{};bool closing{},faulted{};unsigned timeoutMs=5000,testDelayMs=0;
    Session(void* shared,unsigned n,unsigned q,unsigned capacity,uint64_t resourceEpoch,const std::string& cuda,const std::string& optix,const std::string& source,const std::string& crt,unsigned mask=31):bridge(shared),nCapacity(n),qCapacity(q),epoch(resourceEpoch){
        allowedMask=mask;acquireBridgeLease(bridge);
        try{query=createDetachedQuery(n,q,capacity,cuda,optix,source,crt,mask);checked(cuCtxGetCurrent(&context));checked(cuMemHostAlloc((void**)&header,64,0));checked(cuEventCreate(&readDone,CU_EVENT_DISABLE_TIMING));notify=CreateEventW(nullptr,FALSE,FALSE,nullptr);completed=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!notify||!completed)throw std::runtime_error("Worker event allocation failed");uintptr_t id=_beginthreadex(nullptr,0,entry,this,0,nullptr);if(!id)throw std::runtime_error("Query worker creation failed");thread=(HANDLE)id;}
        catch(...){if(readDone)cuEventDestroy(readDone);if(header)cuMemFreeHost(header);if(query)destroyDetachedQuery(query);if(notify)CloseHandle(notify);if(completed)CloseHandle(completed);releaseBridgeLease(bridge);throw;}
    }
    static unsigned __stdcall entry(void* p){((Session*)p)->work();return 0;}
    static void CUDA_CB signalCompleted(void* event){SetEvent((HANDLE)event);}
    static void CUDA_CB diagnosticDelay(void* duration){Sleep(DWORD(uintptr_t(duration)));}
    void injectDelay(unsigned delay,unsigned timeout){Guard held(lock);for(auto& job:jobs)if(job.state!=Free)throw std::runtime_error("Fault injection requires an idle query session");if(delay>10000||timeout<1||timeout>5000||delay<=timeout)throw std::runtime_error("Bounded fault injection only");testDelayMs=delay;timeoutMs=timeout;}
    template<class Poll>void wait(Poll poll){ResetEvent(completed);checked(cuLaunchHostFunc(detachedQueryStream(query),signalCompleted,completed));DWORD status=WaitForSingleObject(completed,timeoutMs);if(status!=WAIT_OBJECT_0)throw std::runtime_error("Native query worker GPU timeout; resources quarantined");checked(poll());}
    void read(CUdeviceptr source){checked(cuMemcpyDtoHAsync(header,source,64,detachedQueryStream(query)));checked(cuEventRecord(readDone,detachedQueryStream(query)));wait([&]{return cuEventQuery(readDone);});}
    QueryView view(const Job& job){
        if(header[0]!=0x52374931u||(header[1]!=1u&&header[1]!=2u)||header[2]!=unsigned(job.nonce)||header[3]!=unsigned(job.nonce>>32)||header[14]!=unsigned(epoch)||header[15]!=unsigned(epoch>>32)||header[13])throw std::runtime_error("Input packet frame/stage/resource generation invalid");
        unsigned n=header[4],q=header[5];if(n>nCapacity||q>qCapacity||n>header[12]||(!n&&q))throw std::runtime_error("Input packet population invalid");
        auto range=[&](unsigned offset,uint64_t size,unsigned alignment){if(offset<64||offset%alignment||uint64_t(offset)+size>job.ticket.bytes)throw std::runtime_error("Input packet range/alignment invalid");return job.ticket.input+offset;};
        float radius{};memcpy(&radius,header+7,4);QueryView v{};v.points=range(header[8],n*16ull,16);v.requests=range(header[9],q*16ull,16);v.owners=range(header[10],q*4ull,4);v.poolIndices=range(header[11],n*4ull,4);
        // Reject aliasing ranges as well as out-of-bounds descriptors.
        std::array<std::pair<uint64_t,uint64_t>,4> ranges={{{header[8],header[8]+n*16ull},{header[9],header[9]+q*16ull},{header[10],header[10]+q*4ull},{header[11],header[11]+n*4ull}}};
        for(unsigned i=0;i<ranges.size();i++)for(unsigned j=i+1;j<ranges.size();j++)if(std::max(ranges[i].first,ranges[j].first)<std::min(ranges[i].second,ranges[j].second))throw std::runtime_error("Input packet aliases immutable ranges");
        uint64_t ids=64+((16+(q+1)*8ull+255)&~255ull);if(ids>job.ticket.bytes||job.ticket.bytes>(64ull<<20))throw std::runtime_error("Output slot capacity invalid");
        v.diagnosticSort=(header[6]&1u)!=0;if(header[6]&2u){unsigned at=header[6]>>2;if(uint64_t(at)*4>job.ticket.bytes)throw std::runtime_error("Query lookup offset overflow");v.queryLookup=range(at*4u,131072ull*4,4);for(auto r:ranges)if(std::max(uint64_t(at)*4,r.first)<std::min(uint64_t(at)*4+131072ull*4,r.second))throw std::runtime_error("Query lookup aliases immutable descriptors");v.lookupOffsetWords=unsigned(job.ticket.bytes/4-131072u);}else if(header[6]&~1u)throw std::runtime_error("Unknown query packet options");v.epoch=epoch;v.stage=header[1];v.n=n;v.q=q;v.live=header[12];if(v.queryLookup&&ids+131072ull*4>job.ticket.bytes)throw std::runtime_error("Query lookup exceeds output budget");v.capacity=unsigned((job.ticket.bytes-ids-(v.queryLookup?131072ull*4:0))/4);v.radius=radius;v.nonce=job.nonce;v.output=job.ticket.output;v.idsOffsetWords=unsigned(ids/4);return v;
    }
    void work(){
        for(;;){unsigned index=3;Job job;{Guard held(lock);for(unsigned i=0;i<jobs.size();i++)if(jobs[i].state==Pending){index=i;job=jobs[i];break;}if(index==3&&closing)return;}
            if(index==3){WaitForSingleObject(notify,100);continue;}
            auto started=std::chrono::steady_clock::now();State state=Ready;std::string error;std::array<double,7> timing{};std::array<jlong,7> stats{{-1,-1,-1,-1,-1,-1,-1}};bool buffersRetired=false;
            try{
                checked(cuCtxSetCurrent(job.ticket.context));CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS input{};input.params.fence.value=job.ticket.value;checked(cuWaitExternalSemaphoresAsync(&job.ticket.inputReady,&input,1,detachedQueryStream(query)));
                unsigned delay{};{Guard held(lock);delay=testDelayMs;testDelayMs=0;}if(delay)checked(cuLaunchHostFunc(detachedQueryStream(query),diagnosticDelay,(void*)uintptr_t(delay)));
                read(job.ticket.input);buffersRetired=true;auto packet=view(job);stats={{packet.n,packet.q,packet.live,packet.capacity,packet.stage,jlong(packet.epoch),0}};buffersRetired=false;enqueueDetachedQuery(query,packet,job.backend,job.refit,job.ticket.outputReady,job.ticket.value);wait([&]{return detachedQueryReady(query);});timing=detachedQueryTiming(query);
                read(job.ticket.output);buffersRetired=true;if(header[0]!=0x52375131u||header[1]!=unsigned(job.nonce)||header[2]!=unsigned(job.nonce>>32)||header[3]!=packet.stage||header[9]!=1||header[10]!=unsigned(epoch)||header[11]!=unsigned(epoch>>32))throw std::runtime_error("Output publication envelope invalid");
                stats[6]=header[8];if(header[8]){state=Fallback;error="candidate capacity exceeded; consume complete GL reference";}
            }catch(const std::exception& failure){error=failure.what();CUresult idle=cuStreamQuery(detachedQueryStream(query));state=buffersRetired||idle==CUDA_SUCCESS?Fallback:Quarantined;if(state==Fallback){try{retireQueryWithoutOutput(bridge,index,job.ticket.value);}catch(const std::exception& e){state=Quarantined;error+=std::string("; ")+e.what();}}}
            timing[1]=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();{Guard held(lock);auto& result=jobs[index];result.state=state;result.error=error;result.timing=timing;result.stats=stats;if(state==Quarantined)faulted=true;}
        }
    }
    bool submit(unsigned index,uint64_t nonce,int backend,bool refit){
        if(!nonce||index>=jobs.size()||backend<0||backend>4||(allowedMask&(1u<<backend))==0u)throw std::runtime_error("Invalid query submission/backend");
        {Guard held(lock);if(closing||faulted||jobs[index].state!=Free)return false;jobs[index].state=Preparing;}
        BridgeTicket ticket;try{ticket=releaseQueryInput(bridge,index);}catch(...){Guard held(lock);jobs[index].state=Free;throw;}
        {Guard held(lock);auto& job=jobs[index];job.ticket=ticket;job.nonce=nonce;job.backend=backend;job.refit=refit;job.error.clear();job.state=Pending;}SetEvent(notify);return true;
    }
    Job snapshot(unsigned index,uint64_t nonce){Guard held(lock);auto job=jobs.at(index);if(job.nonce!=nonce)throw std::runtime_error("Query slot nonce mismatch");return job;}
    void consume(unsigned index,uint64_t nonce){auto job=snapshot(index,nonce);if(job.state!=Ready)throw std::runtime_error("Cannot consume incomplete/failed native output");consumeQueryOutput(bridge,index,job.ticket.value);Guard held(lock);jobs[index].state=Consumed;}
    bool recycle(unsigned index,uint64_t nonce,void* glFence){auto job=snapshot(index,nonce);if(job.state==Consumed){if(!glFence)return false;using Wait=unsigned(APIENTRY*)(void*,unsigned,unsigned long long);auto wait=(Wait)wglGetProcAddress("glClientWaitSync");if(!wait)throw std::runtime_error("GL fence polling unavailable");unsigned status=wait(glFence,0,0);if(status==0x911B)return false;if(status!=0x911A&&status!=0x911C)throw std::runtime_error("Consumer retirement fence failed");}else if(job.state!=Ready&&job.state!=Fallback)return false;Guard held(lock);jobs[index]=Job{};return true;}
    bool retire(){
        {Guard held(lock);closing=true;}SetEvent(notify);if(WaitForSingleObject(thread,0)!=WAIT_OBJECT_0)return false;
        {Guard held(lock);for(auto& job:jobs)if(job.state==Consumed||job.state==Preparing||job.state==Pending)return false;}
        // A timeout is not permission to destroy buffers or a live CUDA stream.
        checked(cuCtxSetCurrent(context));if(cuStreamQuery(detachedQueryStream(query))!=CUDA_SUCCESS)return false;
        {Guard held(lock);for(unsigned i=0;i<jobs.size();i++)if(jobs[i].state==Quarantined){retireQueryWithoutOutput(bridge,i,jobs[i].ticket.value);jobs[i]=Job{};}}
        destroyDetachedQuery(query);query=nullptr;cuEventDestroy(readDone);cuMemFreeHost(header);CloseHandle(thread);CloseHandle(notify);CloseHandle(completed);releaseBridgeLease(bridge);return true;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createAsyncQuery)(JNIEnv* e,jclass,jlong bridge,jint n,jint q,jint capacity,jlong epoch,jstring cuda,jstring optix,jstring source,jstring crt){try{return (jlong)new Session((void*)bridge,n,q,capacity,epoch,utf(e,cuda),utf(e,optix),utf(e,source),utf(e,crt));}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(submitAsyncQuery)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jint backend,jboolean refit){try{return ((Session*)h)->submit(slot,nonce,backend,refit);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(pollAsyncQuery)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{return ((Session*)h)->snapshot(slot,nonce).state;}catch(const std::exception& x){fail(e,x);return -1;}}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(asyncQueryError)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{auto s=((Session*)h)->snapshot(slot,nonce).error;return e->NewStringUTF(s.c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_NAME(asyncQueryTiming)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{auto s=((Session*)h)->snapshot(slot,nonce).timing;auto a=e->NewDoubleArray(s.size());e->SetDoubleArrayRegion(a,0,s.size(),s.data());return a;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(asyncQueryStats)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{auto s=((Session*)h)->snapshot(slot,nonce).stats;auto a=e->NewLongArray(s.size());e->SetLongArrayRegion(a,0,s.size(),s.data());return a;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(consumeAsyncQuery)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{((Session*)h)->consume(slot,nonce);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(recycleAsyncQuery)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong fence){try{return ((Session*)h)->recycle(slot,nonce,(void*)fence);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(closeAsyncQuery)(JNIEnv* e,jclass,jlong h){try{auto* p=(Session*)h;if(!p->retire())return false;delete p;return true;}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(injectAsyncQueryDelay)(JNIEnv* e,jclass,jlong h,jint delay,jint timeout){try{((Session*)h)->injectDelay(delay,timeout);}catch(const std::exception& x){fail(e,x);}}

extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createAsyncQueryForBackend)(JNIEnv* e,jclass,jlong bridge,jint n,jint q,jint capacity,jlong epoch,jint backend,jstring cuda,jstring optix,jstring source,jstring crt){try{if(backend<0||backend>4)throw std::runtime_error("Invalid dedicated backend");return (jlong)new Session((void*)bridge,n,q,capacity,epoch,utf(e,cuda),utf(e,optix),utf(e,source),utf(e,crt),1u<<backend);}catch(const std::exception& x){fail(e,x);return 0;}}
