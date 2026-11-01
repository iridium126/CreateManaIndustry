#pragma once
#include <windows.h>
#include <jni.h>
#include <cuda.h>
#include <cudaGL.h>
#include <stdexcept>
#include <string>
#include <sstream>
#include <atomic>
#define JNI_NAME_(name) Java_com_iridium126_createmanaindustry_client_particles_engine_hardware_HardwareNative_##name
#define JNI_NAME(name) JNI_NAME_(name)
inline void fail(JNIEnv* e,const std::exception& error){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),error.what());}
inline void checked(CUresult r){if(r!=CUDA_SUCCESS){const char* s=nullptr;cuGetErrorString(r,&s);throw std::runtime_error(s?s:"CUDA error");}}
inline std::string utf(JNIEnv* e,jstring s){const char* c=e->GetStringUTFChars(s,nullptr);std::string result(c);e->ReleaseStringUTFChars(s,c);return result;}
inline std::atomic<size_t> offloadOwnedBytes{0};
inline void reserveBytes(size_t n){if(offloadOwnedBytes.fetch_add(n)+n>(512ull<<20)){offloadOwnedBytes-=n;throw std::runtime_error("R7 512 MiB native resource budget exceeded");}}
inline SRWLOCK contextMutex=SRWLOCK_INIT;
struct ContextLock {ContextLock(){AcquireSRWLockExclusive(&contextMutex);}~ContextLock(){ReleaseSRWLockExclusive(&contextMutex);}};
inline CUcontext sharedContext{};inline CUdevice sharedDevice{};inline unsigned contextUsers{};
inline CUcontext acquireContext(){ContextLock lock;checked(cuInit(0));unsigned count=0;CUdevice device{};checked(cuGLGetDevices(&count,&device,1,CU_GL_DEVICE_LIST_CURRENT_FRAME));if(count!=1)throw std::runtime_error("One matching OpenGL/CUDA device required");if(contextUsers&&device!=sharedDevice)throw std::runtime_error("R7 CUDA device changed");if(!contextUsers){checked(cuCtxCreate(&sharedContext,0,device));sharedDevice=device;}++contextUsers;checked(cuCtxSetCurrent(sharedContext));return sharedContext;}
inline void releaseContext(){ContextLock lock;if(contextUsers&&!--contextUsers){cuCtxDestroy(sharedContext);sharedContext=nullptr;}}
