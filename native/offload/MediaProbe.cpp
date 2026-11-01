#include "Common.h"
#include <vector>
#include <iomanip>
#if __has_include("nvOpticalFlowCuda.h") && __has_include("nvEncodeAPI.h") && __has_include("cuviddec.h")
#include "nvOpticalFlowCuda.h"
#include "nvEncodeAPI.h"
#include "cuviddec.h"
namespace {
struct Library {HMODULE value{};explicit Library(const wchar_t* name):value(LoadLibraryW(name)){}~Library(){if(value)FreeLibrary(value);}template<class T>T function(const char* name){return value?(T)GetProcAddress(value,name):nullptr;}};
struct Context {Context(){reserveBytes(64ull<<20);try{acquireContext();}catch(...){offloadOwnedBytes-=64ull<<20;throw;}}~Context(){releaseContext();offloadOwnedBytes-=64ull<<20;}};
std::string optical(CUcontext context){
    Library lib(L"nvofapi64.dll");if(!lib.value)lib.value=LoadLibraryW(L"nvOFAPI.dll");
    auto create=lib.function<decltype(&NvOFAPICreateInstanceCuda)>("NvOFAPICreateInstanceCuda");if(!create)return "{\"api_present\":false}";
    NV_OF_CUDA_API_FUNCTION_LIST api{};auto status=create(NV_OF_API_VERSION,&api);std::ostringstream out;out<<"{\"api_present\":true,\"api_version\":"<<NV_OF_API_VERSION<<",\"create_api_status\":"<<status;
    if(status==NV_OF_SUCCESS){NvOFHandle handle{};status=api.nvCreateOpticalFlowCuda(context,&handle);out<<",\"create_device_status\":"<<status;if(status==NV_OF_SUCCESS){
        out<<",\"caps\":{";bool first=true;for(auto pair:{std::pair<const char*,NV_OF_CAPS>{"output_grid_sizes",NV_OF_CAPS_SUPPORTED_OUTPUT_GRID_SIZES},{"hint_grid_sizes",NV_OF_CAPS_SUPPORTED_HINT_GRID_SIZES},{"width_min",NV_OF_CAPS_WIDTH_MIN},{"height_min",NV_OF_CAPS_HEIGHT_MIN},{"width_max",NV_OF_CAPS_WIDTH_MAX},{"height_max",NV_OF_CAPS_HEIGHT_MAX},{"roi",NV_OF_CAPS_SUPPORT_ROI}}){
            if(!first)out<<',';first=false;unsigned size=0;auto result=api.nvOFGetCaps(handle,pair.second,nullptr,&size);out<<'"'<<pair.first<<"\":{\"status\":"<<result<<",\"values\":[";if(result==NV_OF_SUCCESS&&size<=128){std::vector<unsigned> values(size);result=api.nvOFGetCaps(handle,pair.second,values.data(),&size);if(result==NV_OF_SUCCESS)for(unsigned i=0;i<size;i++){if(i)out<<',';out<<values[i];}}out<<"]}";
        }out<<"}";out<<",\"destroy_status\":"<<api.nvOFDestroy(handle);
    }}out<<",\"input_contract\":\"two 2D image surfaces; not an arbitrary 3D point/neighbor interface\",\"scope\":\"actual device capability API; no optical-flow execution or particle acceleration claim\"}";return out.str();
}
std::string encoder(CUcontext context){
    Library lib(L"nvEncodeAPI64.dll");auto create=lib.function<decltype(&NvEncodeAPICreateInstance)>("NvEncodeAPICreateInstance");if(!create)return "{\"api_present\":false}";
    NV_ENCODE_API_FUNCTION_LIST api{};api.version=NV_ENCODE_API_FUNCTION_LIST_VER;auto status=create(&api);std::ostringstream out;out<<"{\"api_present\":true,\"header_api_version\":"<<NVENCAPI_VERSION<<",\"create_api_status\":"<<status;
    if(status==NV_ENC_SUCCESS){NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS params{};params.version=NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;params.device=context;params.deviceType=NV_ENC_DEVICE_TYPE_CUDA;params.apiVersion=NVENCAPI_VERSION;void* session{};status=api.nvEncOpenEncodeSessionEx(&params,&session);out<<",\"open_device_status\":"<<status;if(status==NV_ENC_SUCCESS){
        unsigned count=0;status=api.nvEncGetEncodeGUIDCount(session,&count);out<<",\"codec_query_status\":"<<status<<",\"codecs\":[";if(status==NV_ENC_SUCCESS&&count<=64){std::vector<GUID> codecs(count);status=api.nvEncGetEncodeGUIDs(session,codecs.data(),count,&count);if(status==NV_ENC_SUCCESS)for(unsigned i=0;i<count;i++){if(i)out<<',';auto g=codecs[i];out<<"{\"guid\":\""<<std::hex<<std::setfill('0')<<std::setw(8)<<g.Data1<<'-'<<std::setw(4)<<g.Data2<<'-'<<std::setw(4)<<g.Data3<<'-';for(auto byte:g.Data4)out<<std::setw(2)<<unsigned(byte);out<<std::dec<<"\",\"caps\":{";bool first=true;for(auto pair:{std::pair<const char*,NV_ENC_CAPS>{"width_max",NV_ENC_CAPS_WIDTH_MAX},{"height_max",NV_ENC_CAPS_HEIGHT_MAX},{"lossless",NV_ENC_CAPS_SUPPORT_LOSSLESS_ENCODE}}){if(!first)out<<',';first=false;NV_ENC_CAPS_PARAM cap{};cap.version=NV_ENC_CAPS_PARAM_VER;cap.capsToQuery=pair.second;int value{};auto result=api.nvEncGetEncodeCaps(session,g,&cap,&value);out<<'"'<<pair.first<<"\":{\"status\":"<<result<<",\"value\":"<<value<<'}';}out<<"}}";}}out<<"],\"destroy_status\":"<<api.nvEncDestroyEncoder(session);
    }}out<<",\"scope\":\"actual device codec capabilities; no encode/particle work performed\"}";return out.str();
}
std::string decoder(){Library lib(L"nvcuvid.dll");auto caps=lib.function<decltype(&cuvidGetDecoderCaps)>("cuvidGetDecoderCaps");if(!caps)return "{\"api_present\":false}";std::ostringstream out;out<<"{\"api_present\":true,\"codecs\":[";bool first=true;for(auto pair:{std::pair<const char*,cudaVideoCodec>{"h264",cudaVideoCodec_H264},{"hevc",cudaVideoCodec_HEVC},{"vp9",cudaVideoCodec_VP9}})for(unsigned depth:{0u,2u}){if(!first)out<<',';first=false;CUVIDDECODECAPS value{};value.eCodecType=pair.second;value.eChromaFormat=cudaVideoChromaFormat_420;value.nBitDepthMinus8=depth;auto status=caps(&value);out<<"{\"codec\":\""<<pair.first<<"\",\"bit_depth\":"<<8+depth<<",\"status\":"<<status<<",\"supported\":"<<(value.bIsSupported?"true":"false")<<",\"max_width\":"<<value.nMaxWidth<<",\"max_height\":"<<value.nMaxHeight<<'}';}out<<"],\"scope\":\"actual 4:2:0 decoder capability API; no decode/particle work performed\"}";return out.str();}
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(mediaProbe)(JNIEnv* e,jclass){try{Context guard;CUcontext context{};checked(cuCtxGetCurrent(&context));auto result="{\"optical_flow\":"+optical(context)+",\"nvenc\":"+encoder(context)+",\"nvdec\":"+decoder()+",\"particle_backend_admitted\":false}";return e->NewStringUTF(result.c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
#else
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(mediaProbe)(JNIEnv* e,jclass){return e->NewStringUTF("{\"available\":false,\"reason\":\"optional pinned media capability headers were not included in this native build\"}");}
#endif
