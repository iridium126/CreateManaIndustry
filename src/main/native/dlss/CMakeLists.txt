cmake_minimum_required(VERSION 3.24)
project(CmiDlssSr LANGUAGES CXX)
find_package(JNI REQUIRED)

set(DLSS_SDK_DIR "" CACHE PATH "NVIDIA DLSS SDK v310.6.0 directory")
find_path(DLSS_INCLUDE nvsdk_ngx_helpers.h PATHS "${DLSS_SDK_DIR}/include" NO_DEFAULT_PATH REQUIRED)
find_library(NGX_LIBRARY nvsdk_ngx_s PATHS "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64" NO_DEFAULT_PATH REQUIRED)

add_library(CmiDlssSr SHARED DlssSr.cpp)
target_compile_features(CmiDlssSr PRIVATE cxx_std_17)
target_compile_definitions(CmiDlssSr PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_include_directories(CmiDlssSr PRIVATE "${DLSS_INCLUDE}" ${JNI_INCLUDE_DIRS})
target_link_libraries(CmiDlssSr PRIVATE d3d12 dxgi advapi32 "${NGX_LIBRARY}")
set_property(TARGET CmiDlssSr PROPERTY MSVC_RUNTIME_LIBRARY MultiThreaded)
