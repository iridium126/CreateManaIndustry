#include "Common.h"
#include "Interop.h"
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <GL/gl.h>
#include <array>
#include <vector>
#include <iomanip>
using Microsoft::WRL::ComPtr;
static void hr(HRESULT value){if(FAILED(value)){std::ostringstream s;s<<"D3D12 HRESULT 0x"<<std::hex<<value;throw std::runtime_error(s.str());}}
template<class T>static T glfn(const char* name){auto p=wglGetProcAddress(name);if(!p||p==(PROC)1||p==(PROC)2||p==(PROC)3||p==(PROC)-1)throw std::runtime_error(std::string("Missing GL entry point: ")+name);return reinterpret_cast<T>(p);}
struct GLImports {
    template<class T>T get(const char* s){return glfn<T>(s);}
    using Names=void(APIENTRY*)(int,unsigned*);using Bind=void(APIENTRY*)(unsigned,unsigned);using MemoryImport=void(APIENTRY*)(unsigned,unsigned long long,unsigned,void*);using MemoryParam=void(APIENTRY*)(unsigned,unsigned,const int*);using Storage=void(APIENTRY*)(unsigned,long long,unsigned,unsigned long long);using SemImport=void(APIENTRY*)(unsigned,unsigned,void*);using SemValue=void(APIENTRY*)(unsigned,unsigned,const unsigned long long*);using SemOp=void(APIENTRY*)(unsigned,unsigned,const unsigned*,unsigned,const unsigned*,const unsigned*);using Bytes=void(APIENTRY*)(unsigned,unsigned char*);
    Names createMemory=get<Names>("glCreateMemoryObjectsEXT"),deleteMemory=get<Names>("glDeleteMemoryObjectsEXT"),genBuffers=get<Names>("glGenBuffers"),deleteBuffers=get<Names>("glDeleteBuffers"),genSem=get<Names>("glGenSemaphoresEXT"),deleteSem=get<Names>("glDeleteSemaphoresEXT");
    Bind bind=get<Bind>("glBindBuffer");MemoryImport importMemory=get<MemoryImport>("glImportMemoryWin32HandleEXT");MemoryParam memoryParam=get<MemoryParam>("glMemoryObjectParameterivEXT");Storage storage=get<Storage>("glBufferStorageMemEXT");SemImport importSem=get<SemImport>("glImportSemaphoreWin32HandleEXT");SemValue semValue=get<SemValue>("glSemaphoreParameterui64vEXT");SemOp signal=get<SemOp>("glSignalSemaphoreEXT"),wait=get<SemOp>("glWaitSemaphoreEXT");Bytes bytes=get<Bytes>("glGetUnsignedBytevEXT");
};
struct SharedBuffer {ComPtr<ID3D12Resource> resource;CUexternalMemory external{};CUdeviceptr pointer{};unsigned memory{},gl{};size_t allocation{};};
struct SharedFence {ComPtr<ID3D12Fence> fence;CUexternalSemaphore cuda{};unsigned gl{};};
struct SnapshotSlot {SharedBuffer source,destination;SharedFence input,done;unsigned long long value{};};
struct Bridge {
    GLImports gl;CUcontext context{};ComPtr<IDXGIFactory4> factory;ComPtr<IDXGIAdapter1> adapter;ComPtr<ID3D12Device> device;std::vector<SnapshotSlot> slots;size_t bytes{};std::string luid;unsigned nodeMask{};std::atomic<unsigned> leases{0};
    Bridge(size_t size,unsigned count):bytes(size){
        if(!bytes||bytes>(64ull<<20)||count<1||count>3)throw std::runtime_error("Interop dimensions exceed bounded ring");
        context=acquireContext();
        try{char cudaLuid[8]{};unsigned node{};checked(cuDeviceGetLuid(cudaLuid,&node,sharedDevice));unsigned char glLuid[8]{};gl.bytes(0x9599,glLuid);if(memcmp(cudaLuid,glLuid,8))throw std::runtime_error("OpenGL/CUDA LUID mismatch");LUID selected{};memcpy(&selected,cudaLuid,8);std::ostringstream id;for(unsigned char v:glLuid)id<<std::hex<<std::setw(2)<<std::setfill('0')<<unsigned(v);luid=id.str();
            hr(CreateDXGIFactory1(IID_PPV_ARGS(&factory)));hr(factory->EnumAdapterByLuid(selected,IID_PPV_ARGS(&adapter)));hr(D3D12CreateDevice(adapter.Get(),D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&device)));
            auto actual=device->GetAdapterLuid();if(memcmp(&selected,&actual,8))throw std::runtime_error("D3D12/CUDA LUID mismatch");
            int glNode{};glGetIntegerv(0x959A,&glNode);if(unsigned(glNode)!=node)throw std::runtime_error("OpenGL/CUDA node-mask mismatch");nodeMask=node;
            slots.resize(count);for(auto& s:slots){makeBuffer(s.source);makeBuffer(s.destination);makeFence(s.input);makeFence(s.done);}
            if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("OpenGL external-object initialization failed");
        }catch(...){cleanup();throw;}
    }
    void makeBuffer(SharedBuffer& b){D3D12_HEAP_PROPERTIES hp{};hp.Type=D3D12_HEAP_TYPE_DEFAULT;D3D12_RESOURCE_DESC rd{};rd.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;rd.Width=bytes;rd.Height=1;rd.DepthOrArraySize=1;rd.MipLevels=1;rd.SampleDesc.Count=1;rd.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;rd.Flags=D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
        auto info=device->GetResourceAllocationInfo(0,1,&rd);reserveBytes(info.SizeInBytes);b.allocation=info.SizeInBytes;hr(device->CreateCommittedResource(&hp,D3D12_HEAP_FLAG_SHARED,&rd,D3D12_RESOURCE_STATE_COMMON,nullptr,IID_PPV_ARGS(&b.resource)));HANDLE handle{};hr(device->CreateSharedHandle(b.resource.Get(),nullptr,GENERIC_ALL,nullptr,&handle));
        try{CUDA_EXTERNAL_MEMORY_HANDLE_DESC desc{};desc.type=CU_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE;desc.handle.win32.handle=handle;desc.size=b.allocation;desc.flags=CUDA_EXTERNAL_MEMORY_DEDICATED;checked(cuImportExternalMemory(&b.external,&desc));CUDA_EXTERNAL_MEMORY_BUFFER_DESC mapped{};mapped.size=bytes;checked(cuExternalMemoryGetMappedBuffer(&b.pointer,b.external,&mapped));gl.createMemory(1,&b.memory);int dedicated=1;gl.memoryParam(b.memory,0x9581,&dedicated);gl.importMemory(b.memory,b.allocation,0x958A,handle);int previous{};glGetIntegerv(0x90D3,&previous);gl.genBuffers(1,&b.gl);gl.bind(0x90D2,b.gl);gl.storage(0x90D2,bytes,b.memory,0);gl.bind(0x90D2,previous);}catch(...){CloseHandle(handle);throw;}CloseHandle(handle);
    }
    void makeFence(SharedFence& f){hr(device->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&f.fence)));HANDLE handle{};hr(device->CreateSharedHandle(f.fence.Get(),nullptr,GENERIC_ALL,nullptr,&handle));try{CUDA_EXTERNAL_SEMAPHORE_HANDLE_DESC desc{};desc.type=CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE;desc.handle.win32.handle=handle;checked(cuImportExternalSemaphore(&f.cuda,&desc));gl.genSem(1,&f.gl);gl.importSem(f.gl,0x9594,handle);}catch(...){CloseHandle(handle);throw;}CloseHandle(handle);}
    void await(unsigned index){auto& s=slots.at(index);if(!s.value)throw std::runtime_error("Interop slot has no submission");gl.semValue(s.done.gl,0x9595,&s.value);gl.wait(s.done.gl,1,&s.destination.gl,0,nullptr,nullptr);}
    void cleanup(){if(context)cuCtxSetCurrent(context);for(auto& s:slots){for(auto* f:{&s.input,&s.done}){if(f->gl)gl.deleteSem(1,&f->gl);if(f->cuda)cuDestroyExternalSemaphore(f->cuda);f->gl=0;f->cuda=nullptr;}for(auto* b:{&s.source,&s.destination}){if(b->gl)gl.deleteBuffers(1,&b->gl);if(b->memory)gl.deleteMemory(1,&b->memory);if(b->pointer)cuMemFree(b->pointer);if(b->external)cuDestroyExternalMemory(b->external);if(b->allocation)particleRtOwnedBytes-=b->allocation;*b={};}}slots.clear();if(context){releaseContext();context=nullptr;}}
    ~Bridge(){cleanup();}
};
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createBuffers)(JNIEnv* e,jclass,jlong bytes,jint slots){try{return (jlong)new Bridge(size_t(bytes),unsigned(slots));}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jintArray JNICALL JNI_NAME(buffers)(JNIEnv* e,jclass,jlong h,jint index){try{auto& s=((Bridge*)h)->slots.at(index);jint a[]={(jint)s.source.gl,(jint)s.destination.gl};auto out=e->NewIntArray(2);e->SetIntArrayRegion(out,0,2,a);return out;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(closeBuffers)(JNIEnv* e,jclass,jlong h){auto* b=(Bridge*)h;if(b->leases.load()){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"Shared ring is leased by an unretired query worker");return;}for(auto& s:b->slots)if(s.value&&s.done.fence->GetCompletedValue()<s.value){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"Shared ring retains unretired shared work");return;}delete b;}

void acquireBridgeLease(void* h){((Bridge*)h)->leases.fetch_add(1);}
void releaseBridgeLease(void* h){((Bridge*)h)->leases.fetch_sub(1);}
void validateBridgeCapacity(void* h,size_t bytes,unsigned slots){auto b=(Bridge*)h;if(!b||b->bytes<bytes||b->slots.size()!=slots)throw std::runtime_error("Shared bridge capacity/slot receipt mismatch");}
BridgeTicket releaseQueryInput(void* h,unsigned index){
    auto* b=(Bridge*)h;auto& s=b->slots.at(index);if(s.value&&s.done.fence->GetCompletedValue()<s.value)throw std::runtime_error("Shared query slot is not retired");
    ++s.value;unsigned releasing[]={s.source.gl,s.destination.gl};b->gl.semValue(s.input.gl,0x9595,&s.value);b->gl.signal(s.input.gl,2,releasing,0,nullptr,nullptr);glFlush();
    return {b->context,s.source.pointer,s.destination.pointer,s.input.cuda,s.done.cuda,b->bytes,s.value};
}
void consumeQueryOutput(void* h,unsigned slot,uint64_t value){auto* b=(Bridge*)h;if(b->slots.at(slot).value!=value)throw std::runtime_error("Shared query fence generation mismatch");b->await(slot);}
void retireQueryWithoutOutput(void* h,unsigned slot,uint64_t value){auto* b=(Bridge*)h;auto& s=b->slots.at(slot);if(s.value!=value)throw std::runtime_error("Failed query fence generation mismatch");hr(s.done.fence->Signal(value));}
