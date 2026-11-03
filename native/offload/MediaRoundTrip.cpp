#include "Common.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <memory>
#include <vector>
#if __has_include("nvcuvid.h") && __has_include("nvEncodeAPI.h")
#include "nvEncodeAPI.h"
#include "nvcuvid.h"
namespace {
using Clock=std::chrono::steady_clock;
double elapsed(Clock::time_point start){return std::chrono::duration<double,std::milli>(Clock::now()-start).count();}
void enc(NVENCSTATUS status,const char* op){if(status!=NV_ENC_SUCCESS)throw std::runtime_error(std::string(op)+": NVENC status "+std::to_string(status));}
uint64_t hash(const unsigned char* p,size_t n){uint64_t h=14695981039346656037ull;for(size_t i=0;i<n;i++)h=(h^p[i])*1099511628211ull;return h;}
struct Library {
 HMODULE value;explicit Library(const wchar_t* path):value(LoadLibraryW(path)){if(!value)throw std::runtime_error("Video driver DLL unavailable");}
 ~Library(){FreeLibrary(value);}
 template<class T>T get(const char* name){auto f=GetProcAddress(value,name);if(!f)throw std::runtime_error(std::string("Video symbol unavailable: ")+name);return reinterpret_cast<T>(f);}
};
struct Media {
 Library encoder{L"nvEncodeAPI64.dll"},decoderLib{L"nvcuvid.dll"};
 NV_ENCODE_API_FUNCTION_LIST api{};void* session{};CUcontext context{};CUdeviceptr input{};
 NV_ENC_REGISTERED_PTR registered{};NV_ENC_INPUT_PTR mapped{};NV_ENC_OUTPUT_PTR output{};
 CUvideoparser parser{};CUvideodecoder decoder{};
 decltype(&cuvidCreateVideoParser) createParser=decoderLib.get<decltype(&cuvidCreateVideoParser)>("cuvidCreateVideoParser");
 decltype(&cuvidDestroyVideoParser) destroyParser=decoderLib.get<decltype(&cuvidDestroyVideoParser)>("cuvidDestroyVideoParser");
 decltype(&cuvidParseVideoData) parse=decoderLib.get<decltype(&cuvidParseVideoData)>("cuvidParseVideoData");
 decltype(&cuvidCreateDecoder) createDecoder=decoderLib.get<decltype(&cuvidCreateDecoder)>("cuvidCreateDecoder");
 decltype(&cuvidDestroyDecoder) destroyDecoder=decoderLib.get<decltype(&cuvidDestroyDecoder)>("cuvidDestroyDecoder");
 decltype(&cuvidDecodePicture) decodePicture=decoderLib.get<decltype(&cuvidDecodePicture)>("cuvidDecodePicture");
 decltype(&cuvidMapVideoFrame) map=decoderLib.get<decltype(&cuvidMapVideoFrame)>("cuvidMapVideoFrame64");
 decltype(&cuvidUnmapVideoFrame) unmap=decoderLib.get<decltype(&cuvidUnmapVideoFrame)>("cuvidUnmapVideoFrame64");
 unsigned width{},height{},capacity{},frames{},displayed{};size_t owned{};bool failed{},predictive{};
 std::vector<unsigned char> raw,recovered,bitstream;std::string callbackError;double initialization{};
 Media()=default;
 void initialize(unsigned w,unsigned bytes,bool usePrediction){
  predictive=usePrediction;
  auto start=Clock::now();if(w<256||w>4096||w%64||bytes==0||bytes>(16u<<20))throw std::runtime_error("Bounded media dimensions required");
  width=w;capacity=bytes;height=std::max(160u,unsigned((uint64_t(bytes)+64+w-1)/w));height=(height+31)&~31u;if(height>4096)throw std::runtime_error("Media height exceeds bounded surface");
  context=acquireContext();api.version=NV_ENCODE_API_FUNCTION_LIST_VER;
  enc(encoder.get<decltype(&NvEncodeAPICreateInstance)>("NvEncodeAPICreateInstance")(&api),"create API");
  NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS open{};open.version=NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;open.device=context;open.deviceType=NV_ENC_DEVICE_TYPE_CUDA;open.apiVersion=NVENCAPI_VERSION;
  enc(api.nvEncOpenEncodeSessionEx(&open,&session),"open encoder");
  NV_ENC_CAPS_PARAM caps{};caps.version=NV_ENC_CAPS_PARAM_VER;caps.capsToQuery=NV_ENC_CAPS_SUPPORT_LOSSLESS_ENCODE;int lossless{};
  enc(api.nvEncGetEncodeCaps(session,NV_ENC_CODEC_HEVC_GUID,&caps,&lossless),"lossless capability");if(!lossless)throw std::runtime_error("HEVC lossless unavailable");
  NV_ENC_PRESET_CONFIG preset{};preset.version=NV_ENC_PRESET_CONFIG_VER;preset.presetCfg.version=NV_ENC_CONFIG_VER;
  enc(api.nvEncGetEncodePresetConfig(session,NV_ENC_CODEC_HEVC_GUID,NV_ENC_PRESET_LOSSLESS_HP_GUID,&preset),"lossless preset");
  auto config=preset.presetCfg;config.frameIntervalP=1;config.gopLength=NVENC_INFINITE_GOPLENGTH;config.rcParams.rateControlMode=NV_ENC_PARAMS_RC_CONSTQP;
  config.rcParams.constQP={0,0,0};config.rcParams.enableLookahead=0;config.rcParams.lookaheadDepth=0;config.encodeCodecConfig.hevcConfig.chromaFormatIDC=1;
  config.encodeCodecConfig.hevcConfig.pixelBitDepthMinus8=0;config.encodeCodecConfig.hevcConfig.repeatSPSPPS=1;
  NV_ENC_INITIALIZE_PARAMS init{};init.version=NV_ENC_INITIALIZE_PARAMS_VER;init.encodeGUID=NV_ENC_CODEC_HEVC_GUID;init.presetGUID=NV_ENC_PRESET_LOSSLESS_HP_GUID;
  init.encodeWidth=width;init.encodeHeight=height;init.darWidth=width;init.darHeight=height;init.frameRateNum=60;init.frameRateDen=1;init.enablePTD=1;init.encodeConfig=&config;
  enc(api.nvEncInitializeEncoder(session,&init),"initialize lossless encoder");
  size_t surface=size_t(width)*height*3/2;reserveBytes(surface);owned=surface;checked(cuMemAlloc(&input,surface));raw.resize(surface,128);recovered.resize(size_t(width)*height);
  NV_ENC_REGISTER_RESOURCE resource{};resource.version=NV_ENC_REGISTER_RESOURCE_VER;resource.resourceType=NV_ENC_INPUT_RESOURCE_TYPE_CUDADEVICEPTR;resource.width=width;resource.height=height;resource.pitch=width;resource.resourceToRegister=(void*)input;resource.bufferFormat=NV_ENC_BUFFER_FORMAT_NV12;
  enc(api.nvEncRegisterResource(session,&resource),"register input");registered=resource.registeredResource;
  NV_ENC_CREATE_BITSTREAM_BUFFER stream{};stream.version=NV_ENC_CREATE_BITSTREAM_BUFFER_VER;enc(api.nvEncCreateBitstreamBuffer(session,&stream),"create bitstream");output=stream.bitstreamBuffer;
  CUVIDPARSERPARAMS params{};params.CodecType=cudaVideoCodec_HEVC;params.ulMaxNumDecodeSurfaces=8;params.ulMaxDisplayDelay=0;params.ulErrorThreshold=0;params.pUserData=this;
  params.pfnSequenceCallback=sequence;params.pfnDecodePicture=decode;params.pfnDisplayPicture=display;checked(createParser(&parser,&params));initialization=elapsed(start);
 }
 static int CUDAAPI sequence(void* opaque,CUVIDEOFORMAT* format){auto& s=*(Media*)opaque;try{
  if(format->codec!=cudaVideoCodec_HEVC||format->chroma_format!=cudaVideoChromaFormat_420||format->bit_depth_luma_minus8||format->coded_width!=s.width||format->coded_height!=s.height)throw std::runtime_error("Unexpected media format/resize");
  if(s.decoder)return 8;
  CUVIDDECODECREATEINFO info{};info.CodecType=cudaVideoCodec_HEVC;info.ChromaFormat=cudaVideoChromaFormat_420;info.OutputFormat=cudaVideoSurfaceFormat_NV12;info.ulWidth=s.width;info.ulHeight=s.height;
  info.ulTargetWidth=s.width;info.ulTargetHeight=s.height;info.ulNumDecodeSurfaces=8;info.ulNumOutputSurfaces=1;info.DeinterlaceMode=cudaVideoDeinterlaceMode_Weave;info.ulCreationFlags=cudaVideoCreate_PreferCUVID;
  checked(s.createDecoder(&s.decoder,&info));return 8;
 }catch(const std::exception& e){s.callbackError=e.what();return 0;}}
 static int CUDAAPI decode(void* opaque,CUVIDPICPARAMS* picture){auto& s=*(Media*)opaque;try{checked(s.decodePicture(s.decoder,picture));return 1;}catch(const std::exception& e){s.callbackError=e.what();return 0;}}
 static int CUDAAPI display(void* opaque,CUVIDPARSERDISPINFO* picture){auto& s=*(Media*)opaque;CUdeviceptr pointer{};try{
  CUVIDPROCPARAMS params{};params.progressive_frame=picture->progressive_frame;params.top_field_first=picture->top_field_first;params.unpaired_field=picture->repeat_first_field<0;
  unsigned pitch{};checked(s.map(s.decoder,picture->picture_index,&pointer,&pitch,&params));
  CUDA_MEMCPY2D copy{};copy.srcMemoryType=CU_MEMORYTYPE_DEVICE;copy.srcDevice=pointer;copy.srcPitch=pitch;copy.dstMemoryType=CU_MEMORYTYPE_HOST;copy.dstHost=s.recovered.data();copy.dstPitch=s.width;copy.WidthInBytes=s.width;copy.Height=s.height;
  checked(cuMemcpy2D(&copy));checked(s.unmap(s.decoder,pointer));pointer=0;++s.displayed;return 1;
 }catch(const std::exception& e){if(pointer)s.unmap(s.decoder,pointer);s.callbackError=e.what();return 0;}}
 std::string run(const unsigned char* bytes,unsigned length,uint64_t nonce,uint64_t epoch,int fault){
  if(failed||!nonce||!epoch||length>capacity||!length||fault<0||fault>3)throw std::runtime_error("Invalid media frame/session");checked(cuCtxSetCurrent(context));auto start=Clock::now();
  std::fill(raw.begin(),raw.begin()+size_t(width)*height,0);uint64_t header[]={0x394d4944434d4931ull,nonce,epoch,length,hash(bytes,length),0,0,0};
  std::memcpy(raw.data(),header,64);std::memcpy(raw.data()+64,bytes,length);double pack=elapsed(start);
  auto uploadStart=Clock::now();checked(cuMemcpyHtoD(input,raw.data(),raw.size()));double upload=elapsed(uploadStart);
  auto encodeStart=Clock::now();NV_ENC_MAP_INPUT_RESOURCE resource{};resource.version=NV_ENC_MAP_INPUT_RESOURCE_VER;resource.registeredResource=registered;enc(api.nvEncMapInputResource(session,&resource),"map encoder input");mapped=resource.mappedResource;
  NV_ENC_PIC_PARAMS picture{};picture.version=NV_ENC_PIC_PARAMS_VER;picture.inputWidth=width;picture.inputHeight=height;picture.inputPitch=width;picture.inputBuffer=mapped;picture.bufferFmt=NV_ENC_BUFFER_FORMAT_NV12;picture.outputBitstream=output;picture.pictureStruct=NV_ENC_PIC_STRUCT_FRAME;picture.inputTimeStamp=nonce;
  picture.encodePicFlags=(!predictive||frames==0)?NV_ENC_PIC_FLAG_FORCEIDR|NV_ENC_PIC_FLAG_OUTPUT_SPSPPS:0;enc(api.nvEncEncodePicture(session,&picture),"encode frame");
  NV_ENC_LOCK_BITSTREAM locked{};locked.version=NV_ENC_LOCK_BITSTREAM_VER;locked.outputBitstream=output;enc(api.nvEncLockBitstream(session,&locked),"lock bitstream");
  bitstream.assign((unsigned char*)locked.bitstreamBufferPtr,(unsigned char*)locked.bitstreamBufferPtr+locked.bitstreamSizeInBytes);enc(api.nvEncUnlockBitstream(session,output),"unlock bitstream");enc(api.nvEncUnmapInputResource(session,mapped),"unmap encoder input");mapped=nullptr;double encodeMs=elapsed(encodeStart);
  auto decodeStart=Clock::now();displayed=0;callbackError.clear();CUVIDSOURCEDATAPACKET packet{};packet.flags=CUVID_PKT_TIMESTAMP|CUVID_PKT_ENDOFPICTURE;packet.timestamp=nonce;packet.payload=bitstream.data();packet.payload_size=(unsigned)bitstream.size();
  if(fault==1)packet.payload_size/=2;if(fault==3)std::fill(bitstream.begin(),bitstream.end(),0);
  auto status=parse(parser,&packet);
  double decodeMs=elapsed(decodeStart);auto verifyStart=Clock::now();bool valid=status==CUDA_SUCCESS&&callbackError.empty()&&displayed==1&&std::memcmp(raw.data(),recovered.data(),64+length)==0;
  uint64_t recoveredHeader[8]{};std::memcpy(recoveredHeader,recovered.data(),64);valid=valid&&recoveredHeader[1]==nonce+unsigned(fault==2)&&recoveredHeader[2]==epoch&&recoveredHeader[3]==length&&recoveredHeader[4]==hash(recovered.data()+64,length);
  if(!valid)failed=true;++frames;double verifyMs=elapsed(verifyStart);
  std::ostringstream out;out<<"{\"abi\":10,\"valid\":"<<(valid?"true":"false")<<",\"fault\":"<<fault<<",\"bytes\":"<<length<<",\"bitstream_bytes\":"<<bitstream.size()<<",\"nonce\":"<<nonce<<",\"epoch\":"<<epoch<<",\"width\":"<<width<<",\"height\":"<<height<<",\"frames\":"<<frames<<",\"displayed\":"<<displayed<<",\"initialization_ms\":"<<initialization<<",\"pack_ms\":"<<pack<<",\"upload_ms\":"<<upload<<",\"encode_and_bitstream_cpu_copy_ms\":"<<encodeMs<<",\"decode_and_readback_ms\":"<<decodeMs<<",\"verify_ms\":"<<verifyMs<<",\"total_cpu_ms\":"<<elapsed(start)<<",\"scope\":\"CPU snapshot pack/upload; actual NVENC HEVC lossless NV12 luma bytes; CPU bitstream; actual NVDEC; D2H byte verification; no live particle/FPS claim\"}";return out.str();
 }
 ~Media(){if(context)cuCtxSetCurrent(context);if(parser)destroyParser(parser);if(decoder)destroyDecoder(decoder);if(mapped)api.nvEncUnmapInputResource(session,mapped);if(registered)api.nvEncUnregisterResource(session,registered);if(output)api.nvEncDestroyBitstreamBuffer(session,output);if(session)api.nvEncDestroyEncoder(session);if(input)cuMemFree(input);offloadOwnedBytes-=owned;if(context)releaseContext();}
};
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createMedia)(JNIEnv* e,jclass,jint width,jint bytes,jboolean predictive){try{auto media=std::make_unique<Media>();media->initialize(width,bytes,predictive);return (jlong)media.release();}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(mediaRoundTrip)(JNIEnv* e,jclass,jlong handle,jobject input,jint bytes,jlong nonce,jlong epoch,jint fault){try{auto* pointer=(unsigned char*)e->GetDirectBufferAddress(input);if(!handle||!pointer||bytes<=0||e->GetDirectBufferCapacity(input)<bytes)throw std::runtime_error("Direct bounded media input required");auto text=((Media*)handle)->run(pointer,bytes,nonce,epoch,fault);return e->NewStringUTF(text.c_str());}catch(const std::exception& x){if(handle)((Media*)handle)->failed=true;fail(e,x);return nullptr;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(closeMedia)(JNIEnv*,jclass,jlong handle){delete (Media*)handle;}
#else
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createMedia)(JNIEnv* e,jclass,jint,jint,jboolean){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"Pinned video parser headers unavailable");return 0;}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(mediaRoundTrip)(JNIEnv* e,jclass,jlong,jobject,jint,jlong,jlong,jint){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"Media codec unavailable");return nullptr;}
extern "C" JNIEXPORT void JNICALL JNI_NAME(closeMedia)(JNIEnv*,jclass,jlong){}
#endif
